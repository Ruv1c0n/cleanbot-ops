"""Веб-чат GUI агента cleanbot-ops (вне ТЗ лабы, коммит по запросу).

stdlib-сервер: python webui.py [--port 8765] — браузер на http://localhost:8765.
Страница agent/webui.html. API:
- GET  /api/info — активная модель + fallback (расширенный);
- GET  /api/models — список моделей обоих бэкендов (дедуп по id);
- GET  /api/sessions?q= — список чатов (pinned первыми, updated_at desc);
- GET/POST /api/sessions, GET/PATCH/DELETE /api/sessions/<id>,
  POST /api/sessions/<id>/fork — управление чатами; метаданные — sidecar
  sessions/<id>.meta.json (agent.load_meta/save_meta);
- POST /api/chat {session, message, model?, regenerate?, stream?} — стрим
  (SSE: log/delta/done/error) или прежний JSON {answer, log};
- POST /api/stop {session} — cancel-флаг активной генерации сессии.

Логика агента переиспользуется как есть (run_agent_stream — стриминговый
twin run_agent). Лок per-session, а не глобальный: стрим одной сессии не
блокирует клики по другим чатам; отдельные локи — на _ensure_backend и
списки моделей. SSE выбран простейший рабочий вариант: HTTP/1.0 без
Content-Length, конец потока = закрытие соединения, flush после каждого
кадра (иначе fetch-ридер на клиенте не увидит событие вовремя).
"""
import argparse
import contextlib
import io
import json
import queue
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlsplit

import agent
import context

HTML_FILE = Path(__file__).parent / "webui.html"
SESSION_RE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
# маршруты с <id> разбираются регэкспом, форма id проверяется SESSION_RE:
# id попадает в путь файла сессии
SESSION_PATH = re.compile(r"^/api/sessions/([^/]+)$")
FORK_PATH = re.compile(r"^/api/sessions/([^/]+)/fork$")
backend_ready = False

backend_lock = threading.Lock()          # select_backend один раз на процесс
models_lock = threading.Lock()           # /api/models: двойной сетевой опрос

# лок per-session: dict session_id -> Lock (создаётся лениво под guard-ом)
_session_locks: dict = {}
_cancel_events: dict = {}                # session_id -> Event активной генерации
_locks_guard = threading.Lock()

# сервер слушает только 127.0.0.1 — запросы с этих хостов «свои».
# ::1 включён: на Windows localhost может резолвиться в IPv6
ALLOWED_HOSTS = {"localhost", "127.0.0.1", "::1"}


def _hostname(netloc: str) -> str | None:
    """Хост из authority: без порта и userinfo (urlsplit, а не разрез по
    первому ':': 'localhost:8765@evil.com' — реальный хост evil.com, а
    наивный парсинг отдавал 'localhost'). IPv6 отдаётся без скобок;
    нечитаемый authority -> None — fail-closed."""
    return urlsplit("//" + netloc).hostname


def origin_host_check(host: str | None, origin: str | None) -> str | None:
    """Проверка заголовков запроса. None — можно обрабатывать; строка —
    причина отказа 403 (проверяется ДО чтения тела).

    Host — только localhost/127.0.0.1/[::1]: отсекает DNS rebinding —
    запрос, пришедший через вредоносный домен, несёт Host attacker.com.
    Origin: отсутствие разрешено (curl и переход по ссылке его не шлют);
    непустой должен указывать на localhost — «простой» POST (text/plain)
    с внешнего сайта проходит без CORS-preflight и несёт Origin исходной
    страницы. Порт не проверяется: угроза — внешние сайты и rebinding,
    а не другие процессы того же локального пользователя."""
    if not host or _hostname(host) not in ALLOWED_HOSTS:
        return f"Host {host!r} не localhost — возможен DNS rebinding"
    if origin:
        parsed = urlsplit(origin)
        if parsed.scheme not in ("http", "https") \
                or parsed.hostname not in ALLOWED_HOSTS:
            return f"Origin {origin!r} не localhost"
    return None


def parse_chat(payload: dict) -> tuple[str, str] | str:
    """Нормализует payload чата: (session, message) или текст ошибки 400.
    str() и дефолт применяются здесь, в одном месте с валидацией: раньше
    validate_chat допускал запрос без session, а do_POST падал KeyError;
    bool-сессия («True») проходила регэксп и уходила в путь файла."""
    session = str(payload.get("session", "web"))
    message = str(payload.get("message", "")).strip()
    error = validate_chat({"session": session, "message": message})
    return error or (session, message)


def normalize_create_payload(payload) -> dict:
    """POST /api/sessions допускает любое тело: не-dict ([]/"x"/1) ведёт
    себя как пустое — id генерирует сервер. Без нормализации обработчик
    падал AttributeError на payload.get() и рвал соединение без ответа."""
    return payload if isinstance(payload, dict) else {}


def validate_chat(payload: dict) -> str | None:
    """Валидация запроса чата: id сессии попадает в путь файла сессии,
    поэтому кроме пустоты проверяем и форму. None — запрос корректен."""
    session = payload.get("session", "web")
    if not SESSION_RE.match(str(session)):
        return "некорректный id сессии"
    if not str(payload.get("message", "")).strip():
        return "пустое сообщение"
    return None


def parse_chat_request(payload) -> dict | str:
    """Расширенный /api/chat: {session, message, model?, regenerate?,
    stream?} -> нормализованный запрос или строка ошибки 400.
    message обязателен не всегда: при regenerate=True допускается пустой
    (генерация заново от последнего user)."""
    if not isinstance(payload, dict):
        return "тело должно быть JSON-объектом"
    session = str(payload.get("session", "web"))
    if not SESSION_RE.match(session):
        return "некорректный id сессии"
    message = str(payload.get("message", "")).strip()
    regenerate = payload.get("regenerate", False)
    stream = payload.get("stream", False)
    model = payload.get("model")
    if model is not None and (not isinstance(model, str) or not model.strip()):
        return "model должен быть непустой строкой или null"
    if not isinstance(regenerate, bool) or not isinstance(stream, bool):
        return "regenerate и stream должны быть bool"
    if not regenerate and not message:
        return "пустое сообщение"
    return {"session": session, "message": message,
            "model": model.strip() if isinstance(model, str) else None,
            "regenerate": regenerate, "stream": stream}


def validate_patch_meta(payload) -> dict | str:
    """PATCH /api/sessions/<id>: тело — любое подмножество полей meta
    -> словарь обновлений, либо строка ошибки 400. temperature 0..2,
    max_tokens 1..100000; bool не проходит как number/int (True — это
    int в Python: явная проверка раньше неявного приведения)."""
    if not isinstance(payload, dict):
        return "тело должно быть JSON-объектом"
    updates = {}
    for key, value in payload.items():
        if key not in ("title", "model", "pinned", "system",
                       "temperature", "max_tokens"):
            return f"неизвестное поле {key!r}"
        if key in ("title", "model", "system"):
            if value is not None and not isinstance(value, str):
                return f"{key} должен быть строкой или null"
        elif key == "pinned":
            if not isinstance(value, bool):
                return "pinned должен быть bool"
        elif key == "temperature":
            if value is not None and (
                    isinstance(value, bool)
                    or not isinstance(value, (int, float))
                    or not 0 <= value <= 2):
                return "temperature должен быть числом 0..2 или null"
        elif key == "max_tokens":
            if value is not None and (
                    isinstance(value, bool)
                    or not isinstance(value, int)
                    or not 1 <= value <= 100000):
                return "max_tokens должен быть целым 1..100000 или null"
        updates[key] = value
    return updates


def validate_fork(payload) -> int | str:
    """POST /api/sessions/<id>/fork: {"message_index": int} — индекс
    ВКЛЮЧИТЕЛЬНОГО последнего копируемого сообщения истории."""
    if not isinstance(payload, dict):
        return "тело должно быть JSON-объектом"
    idx = payload.get("message_index")
    if isinstance(idx, bool) or not isinstance(idx, int):
        return "message_index должен быть целым"
    return idx


def merge_models(local_ids, online_ids, active_model=None,
                 active_backend="local") -> list:
    """Список моделей обоих бэкендов с дедупом по id. Модель, доступная
    на обоих бэкендах, помечается бэкендом активной модели: её выбор в UI
    не должен переключать бэкенд (остальные дубли — локальный приоритет)."""
    sources: dict = {}
    for mid in local_ids:
        sources.setdefault(str(mid), set()).add("local")
    for mid in online_ids:
        sources.setdefault(str(mid), set()).add("online")
    out = []
    for mid in sorted(sources):
        marks = sources[mid]
        if len(marks) == 1:
            backend = next(iter(marks))
        elif mid == active_model:
            backend = active_backend
        else:
            backend = "local"
        out.append({"id": mid, "backend": backend})
    return out


# ---------- сессии: файлы, meta, список, fork ----------

def _read_history(session_id: str) -> list | None:
    """История сессии как на диске (без system). None — файла нет (404);
    битый JSON -> пустая история: список чатов не должен падать из-за
    одного повреждённого файла."""
    path = context._session_path(session_id)
    if not path.is_file():
        return None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        return data if isinstance(data, list) else []
    except (json.JSONDecodeError, OSError):
        return []


def first_user_text(history: list) -> str | None:
    """Текст первого user-сообщения — источник автозаголовка."""
    return next((m.get("content") for m in history
                 if m.get("role") == "user"
                 and isinstance(m.get("content"), str)), None)


def _as_float(value) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return 0.0


def session_summary(session_id: str) -> dict | None:
    """Элемент списка сессий. title из meta; если null — вычисляется из
    первого user-сообщения на лету (без сохранения)."""
    history = _read_history(session_id)
    if history is None:
        return None
    meta = agent.load_meta(session_id)
    title = meta.get("title")
    if title is None:
        title = agent.auto_title(first_user_text(history))
    return {"id": session_id, "title": title, "model": meta.get("model"),
            "pinned": bool(meta.get("pinned")),
            "messages": len(history),
            "updated_at": _as_float(meta.get("updated_at")),
            "created_at": _as_float(meta.get("created_at"))}


def snippet_for(messages: list, term: str, width: int = 120) -> str | None:
    """До ~width символов вокруг первого вхождения term в телах сообщений
    (case-insensitive). None — term в телах не встречается (совпадение
    только по title snippet-а не даёт)."""
    low = term.lower()
    for m in messages:
        content = m.get("content")
        if not isinstance(content, str):
            continue
        idx = content.lower().find(low)
        if idx < 0:
            continue
        start = max(0, idx - width // 3)
        frag = content[start:start + width].strip()
        if start > 0:
            frag = "…" + frag
        if start + width < len(content):
            frag += "…"
        return frag
    return None


def list_session_ids() -> list:
    """id сессий по файлам <id>.json; sidecar <id>.meta.json и tmp
    отсеиваются: meta без истории не создаёт пустой чат в списке."""
    directory = context.SESSIONS_DIR
    if not directory.is_dir():
        return []
    return sorted(p.name[:-len(".json")] for p in directory.glob("*.json")
                  if not p.name.endswith(".meta.json"))


def list_sessions(q: str = "") -> list:
    """Список чатов: pinned первыми, далее updated_at desc. q (len>=2,
    иначе игнорируется) фильтрует по title и содержимому сообщений,
    case-insensitive; отфильтрованным добавляется snippet."""
    items = [s for sid in list_session_ids() if (s := session_summary(sid))]
    term = (q or "").strip()
    if len(term) >= 2:
        low = term.lower()
        filtered = []
        for s in items:
            history = _read_history(s["id"]) or []
            snippet = snippet_for(history, term)
            title = s["title"] or ""
            if low in title.lower() or snippet is not None:
                s["snippet"] = snippet
                filtered.append(s)
        items = filtered
    items.sort(key=lambda s: (not s["pinned"], -_as_float(s["updated_at"])))
    return items


def session_detail(session_id: str) -> tuple[int, dict]:
    """GET /api/sessions/<id>: (код, payload)."""
    if not SESSION_RE.match(session_id):
        return 400, {"error": "некорректный id сессии"}
    history = _read_history(session_id)
    if history is None:
        return 404, {"error": "сессия не найдена"}
    meta = agent.load_meta(session_id)
    title = meta.get("title")
    if title is None:
        title = agent.auto_title(first_user_text(history))
    return 200, {"id": session_id, "title": title,
                 "model": meta.get("model"), "pinned": bool(meta.get("pinned")),
                 "system": meta.get("system"),
                 "temperature": meta.get("temperature"),
                 "max_tokens": meta.get("max_tokens"),
                 "created_at": _as_float(meta.get("created_at")),
                 "updated_at": _as_float(meta.get("updated_at")),
                 "messages": history}


def delete_session(session_id: str) -> tuple[int, dict]:
    """DELETE: удаляет <id>.json и <id>.meta.json (каждый — если есть).
    404, если не было ни одного файла."""
    if not SESSION_RE.match(session_id):
        return 400, {"error": "некорректный id сессии"}
    history = context._session_path(session_id)
    meta = agent.meta_path(session_id)
    existed = history.is_file() or meta.is_file()
    history.unlink(missing_ok=True)
    meta.unlink(missing_ok=True)
    if not existed:
        return 404, {"error": "сессия не найдена"}
    return 200, {"ok": True}


def create_session(session_id: str | None = None) -> str:
    """Новый чат: пустая история + meta (чтобы появился в списке).
    id опционален: иначе chat-<unix_ts>. Существующий id возвращается
    как есть (идемпотентно) — двойной клик «новый чат» не создаёт дубли."""
    if not session_id:
        session_id = f"chat-{int(time.time())}"
    if not SESSION_RE.match(session_id):
        raise ValueError("некорректный id сессии")
    if not context._session_path(session_id).is_file():
        agent.save_session(session_id, [])   # пустая история + meta с метками
    return session_id


def fork_session(session_id: str, message_index: int) -> str | None:
    """Fork: копия сообщений 0..message_index (включительно) в сессию
    <orig>-fork-<ts> с копией meta (created_at новый, pinned=False).
    None — исходной сессии нет; ValueError — индекс вне диапазона."""
    history = _read_history(session_id)
    if history is None:
        return None
    if isinstance(message_index, bool) or not isinstance(message_index, int) \
            or not 0 <= message_index < len(history):
        raise ValueError("message_index вне диапазона истории")
    # суффикс ограничен, чтобы новый id не вылез за 64 символа SESSION_RE
    base = session_id[:45]
    new_id = f"{base}-fork-{int(time.time())}"
    while context._session_path(new_id).is_file() \
            or agent.meta_path(new_id).is_file():
        new_id = f"{base}-fork-{int(time.time() * 1000)}"
    meta = agent.load_meta(session_id)
    meta.update(pinned=False, created_at=time.time(), updated_at=time.time())
    agent.save_meta(new_id, meta)            # до save_session: иначе автозаголовок
    agent.save_session(new_id, history[:message_index + 1])
    return new_id


# ---------- чат: подготовка, не-стрим, стрим ----------

class ChatError(Exception):
    """Ошибка подготовки чата (валидация, неизвестная модель) -> 400 JSON."""


def session_lock(session_id: str) -> threading.Lock:
    """Лок сессии, лениво под guard-ом: setdefault атомарен, дубликат
    не создаётся; разные чаты не блокируют друг друга."""
    with _locks_guard:
        return _session_locks.setdefault(session_id, threading.Lock())


def register_cancel(session_id: str) -> threading.Event:
    event = threading.Event()
    with _locks_guard:
        _cancel_events[session_id] = event
    return event


def pop_cancel(session_id: str, event: threading.Event) -> None:
    with _locks_guard:
        # удаляем только свой Event: между нашими поколениями мог быть другой
        if _cancel_events.get(session_id) is event:
            del _cancel_events[session_id]


def request_stop(session_id: str) -> bool:
    """POST /api/stop: ставит cancel-флаг активной генерации сессии.
    False — активной генерации нет."""
    with _locks_guard:
        event = _cancel_events.get(session_id)
    if event is None:
        return False
    event.set()
    return True


def _ensure_backend() -> None:
    """select_backend один раз на процесс — при первом обращении к API."""
    global backend_ready
    with backend_lock:
        if not backend_ready:
            try:
                agent.select_backend()
            except SystemExit as e:
                # в консоли SystemExit корректен, но в HTTP-обработчике он не
                # ловится except Exception — соединение обрывалось без 500-JSON
                raise RuntimeError(str(e) or "модель недоступна") from e
            backend_ready = True


def configure_web_mode() -> None:
    """Настройка agent под веб-режим — вызывается при старте сервера,
    а не на import: import webui ради validate_chat/origin_host_check
    не должен менять поведение консольного агента (находка ревью).
    write_file подтверждает запись через input(), но stdout webui
    перехвачен redirect_stdout: промпт уходит в буфер, POST /api/chat
    висит вечно, а ввод в консоли сервера «слепо» авторизует запись
    без показанного превью. В веб-режиме подтверждения нет — write_file
    сразу возвращает отказ."""
    agent.CONFIRM_WRITES = False


def _prepare_chat(session_id: str, message: str, model_id=None,
                  regenerate: bool = False):
    """Общая подготовка стрим/не-стрим чата (ВЫЗЫВАТЬ ПОД ЛОКОМ СЕССИИ):
    (messages, client, model_name, backend, gen_kwargs). Кастомная модель
    резолвится per-request и не мутирует глобальные active/client —
    консольный сценарий продолжает работать с дефолтом. Настройки из meta
    (system, temperature, max_tokens) применяются к запросу."""
    _ensure_backend()
    if model_id:
        try:
            client_, model_name, backend = agent.resolve_model(model_id)
        except LookupError as e:
            raise ChatError(str(e)) from e
    else:
        client_, model_name, backend = \
            agent.client, agent.active["model"], agent.active
    meta = agent.load_meta(session_id)
    system_prompt = meta.get("system") or agent.SYSTEM_PROMPT
    messages = agent.load_session(session_id, system_prompt)
    if regenerate:
        starts = [i for i, m in enumerate(messages)
                  if m.get("role") == "user"]
        if not starts:
            raise ChatError("в истории нет user-сообщения — регенерировать нечего")
        # вырезаем всё после последнего user (хвостовая пара user+ответ),
        # сам user остаётся — генерация идёт заново от него
        messages = messages[:starts[-1] + 1]
        if message:
            messages.append({"role": "user", "content": message})
    else:
        messages.append({"role": "user", "content": message})
    gen = {}
    if meta.get("temperature") is not None:
        gen["temperature"] = meta["temperature"]
    if meta.get("max_tokens") is not None:
        gen["max_tokens"] = meta["max_tokens"]
    return messages, client_, model_name, backend, gen


def _remember_model(session_id: str, model_name: str) -> None:
    """Явно выбранная модель запоминается в meta сессии: при следующем
    открытии чата UI подставит её в селектор."""
    meta = agent.load_meta(session_id)
    if meta.get("model") != model_name:
        meta["model"] = model_name
        agent.save_meta(session_id, meta)


def chat_once(session_id: str, message: str, model_id=None,
              regenerate: bool = False) -> dict:
    """Не-стрим чат: прежний контракт {answer, log} + новые ключи
    model/fallback_used/usage. log — все строки stdout (on_log=None ->
    print -> перехват), как и раньше."""
    with session_lock(session_id):
        messages, client_, model_name, backend, gen = _prepare_chat(
            session_id, message, model_id, regenerate)
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            result = agent.run_agent_stream(
                messages, on_delta=lambda text: None, session_id=session_id,
                client=client_, model_name=model_name, backend=backend,
                gen_kwargs=gen)
        if model_id:
            _remember_model(session_id, model_name)
    return {"answer": result["answer"],
            "log": buf.getvalue().splitlines(),
            "model": result["model"],
            "fallback_used": result["fallback_used"],
            "usage": result["usage"]}


def chat_stream_events(session_id: str, message: str, model_id=None,
                       regenerate: bool = False):
    """Генератор SSE-событий чата: yield (event, data) —
    log/delta/done/error. Генерация идёт в рабочем потоке: колбэки
    on_log/on_delta не могут yield-ить из генератора напрямую, события
    идут через очередь. Лок сессии держится всю генерацию (в рабочем
    потоке), cancel-Event регистрируется после захвата лока — registration
    не затирает флаг ещё идущей генерации той же сессии."""
    q: queue.Queue = queue.Queue()
    # один Event на поколение генерации, создан ДО worker-а: finally гасит
    # именно свой Event по локальной ссылке (ревью P1: «что зарегистрировано
    # сейчас» из реестра ловило чужое поколение или None — KeyError, зомби-
    # генерация и ложный стоп следующего сообщения той же сессии)
    cancel = threading.Event()

    def work():
        with session_lock(session_id):
            # регистрируем именно свой Event под локом: следующая генерация
            # той же сессии начнётся только после освобождения лока и не
            # будет затёрта; клиент, отключившийся до захвата лока, уже
            # поставил cancel — первый же check в run_agent_stream остановит
            # зомби-генерацию, копившую дельты в осиротевшую очередь
            with _locks_guard:
                _cancel_events[session_id] = cancel
            try:
                messages, client_, model_name, backend, gen = _prepare_chat(
                    session_id, message, model_id, regenerate)
                result = agent.run_agent_stream(
                    messages,
                    on_log=lambda line: q.put(("log", {"line": line})),
                    on_delta=lambda text: q.put(("delta", {"text": text})),
                    cancel_event=cancel, session_id=session_id,
                    client=client_, model_name=model_name, backend=backend,
                    gen_kwargs=gen)
                if model_id:
                    _remember_model(session_id, model_name)
                q.put(("done", {"answer": result["answer"],
                                "model": result["model"],
                                "fallback_used": result["fallback_used"],
                                "usage": result["usage"],
                                "stopped": result["stopped"],
                                "title": agent.load_meta(session_id)["title"]}))
            except ChatError as e:
                q.put(("error", {"error": str(e)}))
            except Exception as e:               # noqa: BLE001 — ошибка уходит клиенту кадром
                q.put(("error", {"error": str(e)}))
            finally:
                q.put(None)                      # sentinel конца потока
                # свой Event убираем и здесь: если генератор закрыли ДО
                # регистрации (клиент отключился в очереди на лок), его
                # finally уже отработал и реестр остался бы замусоренным;
                # pop_cancel по identity — чужое поколение не заденет
                pop_cancel(session_id, cancel)

    worker = threading.Thread(target=work, daemon=True)
    worker.start()
    try:
        while True:
            item = q.get()
            if item is None:
                return
            yield item
    finally:
        # потребитель ушёл (закрытие соединения/вкладки) — гасим генерацию.
        # set() на СВОЁМ Event: при нормальном завершении это безвредно
        # (его уже никто не читает), при раннем отключении — останавливает
        # worker, ждущий лок или идущий цикл дельт
        cancel.set()
        worker.join(timeout=5)
        pop_cancel(session_id, cancel)


def sse_frame(event: str, data) -> bytes:
    """Кадр SSE: event: имя\\ndata: json\\n\\n. Пустая строка — граница
    события для fetch-ридера на клиенте; write+flush после каждого кадра
    делаются в обработчике."""
    return (f"event: {event}\n"
            f"data: {json.dumps(data, ensure_ascii=False)}\n\n").encode("utf-8")


class Handler(BaseHTTPRequestHandler):
    def _send(self, code: int, body: bytes, ctype: str) -> None:
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _json(self, code: int, obj) -> None:
        self._send(code, json.dumps(obj, ensure_ascii=False).encode("utf-8"),
                   "application/json; charset=utf-8")

    def _deny_foreign(self) -> bool:
        """403 для запросов с чужим Host/Origin (до чтения тела)."""
        denied = origin_host_check(self.headers.get("Host"),
                                   self.headers.get("Origin"))
        if denied:
            self._json(403, {"error": denied})
            return True
        return False

    def _read_json(self):
        """Тело запроса -> dict; пустое тело -> {} (PATCH без полей легален).
        Битый JSON ловится в обработчике как 400, а не 500."""
        length = int(self.headers.get("Content-Length", 0))
        if length <= 0:
            return {}
        return json.loads(self.rfile.read(length).decode("utf-8"))

    def do_GET(self):
        if self._deny_foreign():
            return
        path, _, query = self.path.partition("?")
        if path == "/":
            self._send(200, HTML_FILE.read_bytes(), "text/html; charset=utf-8")
        elif path == "/api/info":
            try:
                _ensure_backend()
                info = {"model": agent.active["model"],
                        "backend": agent.active["base_url"],
                        "fallback_model": agent.FALLBACK["model"],
                        "local_available": False}
                try:
                    info["local_available"] = agent.local_available()
                except Exception:
                    pass
                self._json(200, info)
            except Exception as e:
                self._json(500, {"error": str(e)})
        elif path == "/api/models":
            try:
                _ensure_backend()
                with models_lock:
                    local_ids = agent.local_model_ids()
                    online_ids = agent.online_model_ids()
                active_backend = "local" if agent.active is agent.LOCAL \
                    else "online"
                self._json(200, {
                    "models": merge_models(local_ids, online_ids,
                                           agent.active["model"],
                                           active_backend),
                    "active": agent.active["model"]})
            except Exception as e:
                self._json(500, {"error": str(e)})
        elif path == "/api/sessions":
            q = (parse_qs(query).get("q") or [""])[0]
            self._json(200, {"sessions": list_sessions(q)})
        elif SESSION_PATH.match(path):
            code, body = session_detail(unquote(SESSION_PATH.match(path).group(1)))
            self._json(code, body)
        else:
            self._send(404, "не найдено", "text/plain; charset=utf-8")

    def do_POST(self):
        if self._deny_foreign():
            return          # 403 до разбора тела — тело не читается вовсе
        path = urlsplit(self.path).path
        try:
            payload = self._read_json()
        except (ValueError, UnicodeDecodeError) as e:
            self._json(400, {"error": f"невалидный JSON: {e}"})
            return
        if path == "/api/chat":
            self._handle_chat(payload)
        elif path == "/api/sessions":
            self._handle_create_session(payload)
        elif FORK_PATH.match(path):
            self._handle_fork(unquote(FORK_PATH.match(path).group(1)), payload)
        elif path == "/api/stop":
            self._handle_stop(payload)
        else:
            self._send(404, "не найдено", "text/plain; charset=utf-8")

    def do_PATCH(self):
        if self._deny_foreign():
            return
        match = SESSION_PATH.match(urlsplit(self.path).path)
        if not match:
            self._send(404, "не найдено", "text/plain; charset=utf-8")
            return
        session_id = unquote(match.group(1))
        try:
            payload = self._read_json()
        except (ValueError, UnicodeDecodeError) as e:
            self._json(400, {"error": f"невалидный JSON: {e}"})
            return
        updates = validate_patch_meta(payload)
        if isinstance(updates, str):
            self._json(400, {"error": updates})
            return
        if not SESSION_RE.match(session_id):
            self._json(400, {"error": "некорректный id сессии"})
            return
        if not context._session_path(session_id).is_file():
            self._json(404, {"error": "сессия не найдена"})
            return
        meta = agent.load_meta(session_id)
        meta.update(updates)
        # PATCH — правка настроек, а не диалог: updated_at не трогаем
        agent.save_meta(session_id, meta)
        self._json(200, {"ok": True, **meta})

    def do_DELETE(self):
        if self._deny_foreign():
            return
        match = SESSION_PATH.match(urlsplit(self.path).path)
        if not match:
            self._send(404, "не найдено", "text/plain; charset=utf-8")
            return
        code, body = delete_session(unquote(match.group(1)))
        self._json(code, body)

    def _handle_chat(self, payload) -> None:
        parsed = parse_chat_request(payload)
        if isinstance(parsed, str):
            self._json(400, {"error": parsed})
            return
        if parsed["stream"]:
            self._stream_chat(parsed)
            return
        try:
            self._json(200, chat_once(
                parsed["session"], parsed["message"], model_id=parsed["model"],
                regenerate=parsed["regenerate"]))
        except ChatError as e:
            self._json(400, {"error": str(e)})
        except Exception as e:
            self._json(500, {"error": str(e)})

    def _stream_chat(self, parsed) -> None:
        """SSE-ответ. protocol HTTP/1.0 без Content-Length: конец потока =
        закрытие соединения; flush после каждого кадра обязателен, иначе
        события копились бы в буферах и fetch-ридер на клиенте ждал бы."""
        try:
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream; charset=utf-8")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "close")
            self.end_headers()
            for event, data in chat_stream_events(
                    parsed["session"], parsed["message"],
                    model_id=parsed["model"],
                    regenerate=parsed["regenerate"]):
                self.wfile.write(sse_frame(event, data))
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass             # клиент закрыл вкладку — не ронять поток
        except Exception as e:
            # заголовки уже ушли: ошибка только кадром error (если можно)
            try:
                self.wfile.write(sse_frame("error", {"error": str(e)}))
                self.wfile.flush()
            except OSError:
                pass

    def _handle_create_session(self, payload) -> None:
        raw = normalize_create_payload(payload).get("id")
        if raw is not None and (not isinstance(raw, str)
                                or not SESSION_RE.match(raw)):
            self._json(400, {"error": "некорректный id сессии"})
            return
        try:
            session_id = create_session(raw)
        except ValueError as e:
            self._json(400, {"error": str(e)})
            return
        self._json(200, session_summary(session_id)
                   or {"id": session_id, "title": None})

    def _handle_fork(self, session_id, payload) -> None:
        if not SESSION_RE.match(session_id):
            self._json(400, {"error": "некорректный id сессии"})
            return
        index = validate_fork(payload)
        if isinstance(index, str):
            self._json(400, {"error": index})
            return
        try:
            new_id = fork_session(session_id, index)
        except ValueError as e:
            self._json(400, {"error": str(e)})
            return
        if new_id is None:
            self._json(404, {"error": "сессия не найдена"})
            return
        self._json(200, {"id": new_id})

    def _handle_stop(self, payload) -> None:
        session_id = payload.get("session") if isinstance(payload, dict) else None
        if not isinstance(session_id, str) or not SESSION_RE.match(session_id):
            self._json(400, {"error": "некорректный id сессии"})
            return
        self._json(200, {"ok": request_stop(session_id)})

    def log_message(self, *args):        # тихий сервер: чат и так логирует
        pass


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Веб-чат агента cleanbot-ops")
    parser.add_argument("--port", type=int, default=8765,
                        help="порт (по умолчанию 8765; 8080 занят Docker)")
    args = parser.parse_args()
    configure_web_mode()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"GUI агента: http://localhost:{args.port}  (Ctrl+C — выход)")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nостановлено")
