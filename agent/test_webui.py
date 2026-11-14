"""Смоук-тесты webui.py: модуль без сети, страница на месте, валидация чата."""
import json
from pathlib import Path

import pytest

import context
import webui


def test_html_page_exists():
    assert Path(webui.HTML_FILE).is_file()
    html = Path(webui.HTML_FILE).read_text(encoding="utf-8")
    assert "/api/chat" in html and "cleanbot" in html


def test_session_id_validated():
    # id сессии попадает в путь файла сессии: только [A-Za-z0-9_-], <=64
    assert webui.SESSION_RE.match("web")
    assert webui.SESSION_RE.match("lab4-scenario")
    assert not webui.SESSION_RE.match("../etc/passwd")
    assert not webui.SESSION_RE.match("a" * 65)


def test_validate_chat():
    assert webui.validate_chat({"session": "web", "message": "привет"}) is None
    # дефолтная сессия и непустое сообщение — ок
    assert webui.validate_chat({"message": "привет"}) is None
    assert webui.validate_chat({"session": "a/b", "message": "x"}) == \
        "некорректный id сессии"
    assert webui.validate_chat({"session": "web", "message": "   "}) == \
        "пустое сообщение"
    assert webui.validate_chat({"session": "web"}) == "пустое сообщение"


# ---------- Origin/Host: защита от DNS rebinding и «простых» POST ----------
# находка ревью lab4-check: сервер не проверял Host/Origin — любой открытый
# сайт мог слать POST /api/chat (text/plain проходит без preflight)

def test_origin_host_check():
    # обычные запросы GUI и curl без Origin проходят
    assert webui.origin_host_check("localhost:8765", None) is None
    assert webui.origin_host_check("127.0.0.1:8765", "http://localhost:8765") is None
    assert webui.origin_host_check("localhost", None) is None
    assert webui.origin_host_check("[::1]:8765", None) is None
    # DNS rebinding: чужой Host
    assert webui.origin_host_check("attacker.com", None) is not None
    assert webui.origin_host_check(None, None) is not None
    # «простой» POST с внешнего сайта: Origin не localhost
    assert webui.origin_host_check("localhost:8765", "http://evil.com") is not None
    assert webui.origin_host_check("localhost:8765", "https://evil.com:443/x") is not None
    assert webui.origin_host_check("localhost:8765", "null") is not None
    assert webui.origin_host_check("localhost:8765", "") is None  # пустой = отсутствие


def test_web_mode_configures_agent(monkeypatch):
    # находка ревью lab4-check: CONFIRM_WRITES=False был side-effect-ом
    # import webui — импорт ради хелперов менял поведение консольного
    # агента. Флаг включается только при старте сервера (configure_web_mode)
    monkeypatch.setattr(webui.agent, "CONFIRM_WRITES", True)
    webui.configure_web_mode()
    assert webui.agent.CONFIRM_WRITES is False
    assert webui.agent.CONFIRM_WRITES is not True


def test_ensure_backend_maps_systemexit(monkeypatch):
    # находка ревью lab4-check: select_backend при «нет модели и нет ключа»
    # бросает SystemExit — except Exception в HTTP-обработчике его не ловил,
    # соединение обрывалось без 500-JSON
    monkeypatch.setattr(webui, "backend_ready", False)

    def boom():
        raise SystemExit("Локальная модель недоступна, а FALLBACK_API_KEY не задан")

    monkeypatch.setattr(webui.agent, "select_backend", boom)
    with pytest.raises(RuntimeError, match="FALLBACK_API_KEY"):
        webui._ensure_backend()


def test_origin_host_rejects_userinfo():
    # находка ревью lab4-check: наивный разрез по первому ':' отдавал
    # 'localhost' для authority 'localhost:8765@evil.com' — userinfo
    # маскировал реальный хост; urlsplit парсит userinfo правильно
    assert webui.origin_host_check("localhost:8765@evil.com", None) is not None
    assert webui.origin_host_check(
        "localhost:8765", "http://localhost:8765@evil.com") is not None
    # валидные хосты по-прежнему проходят (IPv6 отдаётся urlsplit-ом без скобок)
    assert webui.origin_host_check("[::1]:8765", None) is None
    assert webui.origin_host_check(
        "localhost:8765", "http://user@localhost:8765") is None


def test_parse_chat_normalizes_session():
    # находка ревью lab4-check: validate_chat допускал запрос без session,
    # а do_POST падал KeyError -> 500; нормализация теперь в одном месте
    assert webui.parse_chat({"message": "привет"}) == ("web", "привет")
    assert webui.parse_chat({"session": "lab4", "message": " х "}) == \
        ("lab4", "х")
    # нестроковая сессия нормализуется ДО валидации: в путь файла сессии
    # уходит та же строка, которую проверил регэксп ("True" валиден)
    assert webui.parse_chat({"session": True, "message": "х"}) == ("True", "х")
    assert webui.parse_chat({"message": "   "}) == "пустое сообщение"


# ---------- webui-pro: сессии, meta, SSE (всё network-free) ----------

def user_msg(text):
    return {"role": "user", "content": text}


def assistant_msg(text):
    return {"role": "assistant", "content": text}


@pytest.fixture
def sessions_ws(tmp_path, monkeypatch):
    monkeypatch.setattr(context, "SESSIONS_DIR", tmp_path)
    return tmp_path


def make_session(sid, history, meta=None):
    """Сессия с управляемой meta: файл истории пишется напрямую (save_session
    вычислял бы автозаголовок сам), meta — через save_meta с дефолтами."""
    path = context._session_path(sid)
    path.parent.mkdir(exist_ok=True)
    path.write_text(json.dumps(history, ensure_ascii=False), encoding="utf-8")
    if meta is not None:
        webui.agent.save_meta(sid, {**webui.agent.META_DEFAULTS, **meta})


# ---------- parse_chat_request ----------

def test_parse_chat_request_defaults_and_normalization():
    parsed = webui.parse_chat_request({"session": "web", "message": " привет "})
    assert parsed == {"session": "web", "message": "привет", "model": None,
                      "regenerate": False, "stream": False}
    parsed = webui.parse_chat_request(
        {"message": "х", "model": " m1 ", "regenerate": True, "stream": True})
    assert parsed["model"] == "m1" and parsed["regenerate"] is True
    assert parsed["stream"] is True


def test_parse_chat_request_errors():
    assert isinstance(webui.parse_chat_request([1, 2]), str)
    assert webui.parse_chat_request({"session": "a/b", "message": "х"}) == \
        "некорректный id сессии"
    assert webui.parse_chat_request({"message": "  "}) == "пустое сообщение"
    # message обязателен не всегда: regenerate с пустым — легален
    parsed = webui.parse_chat_request({"message": "", "regenerate": True})
    assert isinstance(parsed, dict) and parsed["regenerate"] is True
    # модель: пустая/нестроковая — ошибка
    assert isinstance(webui.parse_chat_request({"message": "х", "model": ""}), str)
    assert isinstance(webui.parse_chat_request({"message": "х", "model": 5}), str)
    # флаги должны быть bool
    assert isinstance(webui.parse_chat_request(
        {"message": "х", "stream": "да"}), str)
    assert isinstance(webui.parse_chat_request(
        {"message": "х", "regenerate": 1}), str)


# ---------- validate_patch_meta ----------

def test_validate_patch_meta_valid_subset():
    assert webui.validate_patch_meta({}) == {}
    assert webui.validate_patch_meta({"title": "Новое", "pinned": True}) == \
        {"title": "Новое", "pinned": True}
    assert webui.validate_patch_meta({"title": None, "system": None,
                                      "model": None}) == \
        {"title": None, "system": None, "model": None}
    assert webui.validate_patch_meta({"temperature": 1.5}) == \
        {"temperature": 1.5}
    assert webui.validate_patch_meta({"max_tokens": 100000}) == \
        {"max_tokens": 100000}


def test_validate_patch_meta_errors():
    assert isinstance(webui.validate_patch_meta("строка"), str)
    assert isinstance(webui.validate_patch_meta({"unknown": 1}), str)
    assert isinstance(webui.validate_patch_meta({"title": 5}), str)
    assert isinstance(webui.validate_patch_meta({"pinned": "yes"}), str)
    assert isinstance(webui.validate_patch_meta({"system": []}), str)
    # temperature: диапазон 0..2, bool не проходит как число
    for bad in (-0.1, 2.1, "х", True):
        assert isinstance(webui.validate_patch_meta({"temperature": bad}), str)
    # max_tokens: 1..100000, только int (не bool, не float)
    for bad in (0, 100001, 1.5, True):
        assert isinstance(webui.validate_patch_meta({"max_tokens": bad}), str)
    # null легален для числовых полей (сброс настройки)
    assert webui.validate_patch_meta({"temperature": None,
                                      "max_tokens": None}) == \
        {"temperature": None, "max_tokens": None}


# ---------- meta round-trip / история / 404 ----------

def test_session_detail_404_and_400(sessions_ws):
    assert webui.session_detail("no-such")[0] == 404
    assert webui.session_detail("../etc/passwd")[0] == 400
    make_session("d1", [user_msg("первое сообщение диалога")],
                 meta={"system": "СИС", "temperature": 0.5, "max_tokens": 100,
                       "model": "m1", "pinned": True, "created_at": 555.0,
                       "updated_at": 777.0})
    code, body = webui.session_detail("d1")
    assert code == 200
    assert body["system"] == "СИС" and body["temperature"] == 0.5
    assert body["max_tokens"] == 100 and body["model"] == "m1"
    assert body["pinned"] is True
    assert body["messages"] == [user_msg("первое сообщение диалога")]
    assert body["title"] == "первое сообщение диалога"
    assert body["created_at"] == 555.0 and body["updated_at"] == 777.0


def test_delete_session_removes_both_files(sessions_ws):
    make_session("del1", [user_msg("х")], meta={"title": "Т"})
    assert context._session_path("del1").is_file()
    assert webui.agent.meta_path("del1").is_file()
    code, body = webui.delete_session("del1")
    assert code == 200 and body == {"ok": True}
    assert not context._session_path("del1").exists()
    assert not webui.agent.meta_path("del1").exists()
    # повторное удаление — 404: не было ни одного файла
    assert webui.delete_session("del1")[0] == 404
    # только история без meta — удаляется тоже
    make_session("del2", [])
    assert webui.delete_session("del2")[0] == 200
    assert webui.delete_session("a/b")[0] == 400


def test_create_session_idempotent(sessions_ws):
    sid = webui.create_session()
    assert webui.SESSION_RE.match(sid) and sid.startswith("chat-")
    # пустая история + meta, чтобы чат появился в списке
    assert context._session_path(sid).is_file()
    assert webui.agent.meta_path(sid).is_file()
    assert webui._read_history(sid) == []
    # идемпотентность: тот же id возвращается как есть
    assert webui.create_session(sid) == sid
    with pytest.raises(ValueError):
        webui.create_session("../evil")


# ---------- fork ----------

def test_fork_session_copies_messages_and_meta(sessions_ws):
    history = [user_msg("один"), assistant_msg("два"), user_msg("три")]
    make_session("orig", history,
                 meta={"title": "Исходник", "pinned": True,
                       "created_at": 123.0, "updated_at": 456.0,
                       "temperature": 0.7})
    new_id = webui.fork_session("orig", 1)
    assert new_id.startswith("orig-fork-")
    assert webui.SESSION_RE.match(new_id)
    assert webui._read_history(new_id) == history[:2]
    meta = webui.agent.load_meta(new_id)
    assert meta["pinned"] is False
    assert meta["title"] == "Исходник"           # meta скопирована
    assert meta["temperature"] == 0.7
    assert meta["created_at"] != 123.0 and meta["created_at"] > 0


def test_fork_session_boundaries(sessions_ws):
    history = [user_msg("один"), assistant_msg("два"), user_msg("три")]
    make_session("orig", history, meta={"title": "Т"})
    # индекс включительный: 2 — вся история, 0 — одно сообщение
    assert webui._read_history(webui.fork_session("orig", 2)) == history
    assert webui._read_history(webui.fork_session("orig", 0)) == history[:1]
    # вне диапазона — ошибка
    with pytest.raises(ValueError):
        webui.fork_session("orig", 3)
    with pytest.raises(ValueError):
        webui.fork_session("orig", -1)
    # пустая история: любой индекс вне диапазона
    make_session("empty", [])
    with pytest.raises(ValueError):
        webui.fork_session("empty", 0)
    # несуществующая сессия
    assert webui.fork_session("нет-такой", 0) is None


# ---------- список сессий: сортировка, q-фильтр, snippet ----------

def test_list_sessions_sorts_pinned_then_updated(sessions_ws):
    make_session("a", [user_msg("Первая сессия привет")],
                 meta={"updated_at": 100.0})
    make_session("b", [user_msg("Вторая"), assistant_msg("х")],
                 meta={"updated_at": 300.0, "pinned": True, "title": "Важное"})
    make_session("c", [], meta={"updated_at": 200.0, "title": None})
    items = webui.list_sessions()
    assert [i["id"] for i in items] == ["b", "c", "a"]
    assert items[0]["title"] == "Важное"
    assert items[0]["messages"] == 2 and items[0]["pinned"] is True
    # title null вычислен из первого user-сообщения, без сохранения в meta
    assert items[2]["title"] == "Первая сессия привет"
    assert webui.agent.load_meta("a")["title"] is None
    # без meta вообще — дефолты, чат не пропадает из списка
    context._session_path("legacy").write_text(
        json.dumps([user_msg("старая")], ensure_ascii=False), encoding="utf-8")
    legacy = next(i for i in webui.list_sessions() if i["id"] == "legacy")
    assert legacy["updated_at"] == 0.0 and legacy["messages"] == 1


def test_list_sessions_q_filter_and_snippet(sessions_ws):
    body = "В середине сообщения спрятано слово ЯНТАРЬ и продолжение текста"
    make_session("q1", [user_msg(body)], meta={"title": "Заметка"})
    make_session("q2", [user_msg("совсем другое")],
                 meta={"title": "ЯНТАРЬ в названии"})
    make_session("q3", [user_msg("никаких совпадений")])
    items = webui.list_sessions(q="янтарь")      # case-insensitive
    assert {i["id"] for i in items} == {"q1", "q2"}
    hit = next(i for i in items if i["id"] == "q1")
    assert hit["snippet"] and "ЯНТАРЬ" in hit["snippet"]
    # совпадение только в title: snippet остаётся null
    title_hit = next(i for i in items if i["id"] == "q2")
    assert title_hit["snippet"] is None
    # q короче 2 — игнорируется: весь список, без snippet
    all_items = webui.list_sessions(q="я")
    assert {i["id"] for i in all_items} == {"q1", "q2", "q3"}
    assert "snippet" not in all_items[0]
    assert "snippet" not in webui.list_sessions()[0]


def test_snippet_for_window_and_absence():
    long_body = "а" * 150 + " ЖЕТОН " + "б" * 200
    msgs = [user_msg("без совпадений"), user_msg(long_body)]
    frag = webui.snippet_for(msgs, "жетон")
    assert frag is not None and "ЖЕТОН" in frag
    assert frag.startswith("…") and frag.endswith("…")
    assert len(frag) <= 124                      # ~120 + эллипсисы
    assert webui.snippet_for(msgs, "нет-такого-слова") is None
    assert webui.snippet_for([], "что-угодно") is None


# ---------- merge_models ----------

def test_merge_models_dedup_prefers_active_backend():
    models = webui.merge_models(["m1", "shared"], ["shared", "m2"],
                                active_model="shared", active_backend="local")
    assert {m["id"]: m["backend"] for m in models} == \
        {"m1": "local", "m2": "online", "shared": "local"}
    dup = webui.merge_models(["shared"], ["shared"],
                             active_model="shared", active_backend="online")
    assert dup == [{"id": "shared", "backend": "online"}]
    assert webui.merge_models([], []) == []


# ---------- stop-реестр ----------

def test_request_stop_registry():
    assert webui.request_stop("нет-генерации") is False
    event = webui.register_cancel("busy")
    assert webui.request_stop("busy") is True
    assert event.is_set()                        # агент увидит флаг между шагами
    webui.pop_cancel("busy", event)
    assert webui.request_stop("busy") is False


# ---------- SSE ----------

def test_sse_frame_format():
    frame = webui.sse_frame("delta", {"text": "х"})
    assert frame == 'event: delta\ndata: {"text": "х"}\n\n'.encode("utf-8")
    # кадр распадается на event/data/пустую строку — то, что парсит fetch-ридер
    lines = frame.decode("utf-8").split("\n")
    assert lines[0] == "event: delta"
    assert json.loads(lines[1].removeprefix("data: ")) == {"text": "х"}
    assert lines[2] == ""                        # пустая строка = конец события


def test_chat_stream_events_direct(sessions_ws, monkeypatch):
    # генератор вызывается напрямую, без сокета: агент подменён
    monkeypatch.setattr(webui, "_prepare_chat",
                        lambda *a, **k: ([], None, None, "fake", {}))

    def fake_run(messages, **kwargs):
        kwargs["on_log"]("[шаг 0]")
        kwargs["on_delta"]("При")
        kwargs["on_delta"]("вет")
        return {"answer": "Привет", "model": "fake", "fallback_used": False,
                "usage": {"prompt_tokens": 1, "completion_tokens": 2},
                "stopped": False}

    monkeypatch.setattr(webui.agent, "run_agent_stream", fake_run)
    events = list(webui.chat_stream_events("sse", "привет"))
    assert [name for name, _ in events] == ["log", "delta", "delta", "done"]
    assert events[1][1] == {"text": "При"}
    done = events[-1][1]
    assert done["answer"] == "Привет" and done["model"] == "fake"
    assert done["stopped"] is False
    assert done["title"] is None                 # meta ещё пуста (sessions_ws)


def test_chat_stream_events_error_frame(sessions_ws, monkeypatch):
    def boom(*a, **k):
        raise webui.ChatError("неизвестная модель 'x'; доступные: y")

    monkeypatch.setattr(webui, "_prepare_chat", boom)
    events = list(webui.chat_stream_events("s", "привет"))
    assert len(events) == 1 and events[0][0] == "error"
    assert "неизвестная модель" in events[0][1]["error"]


def test_chat_stream_events_regenerate_trims_history(sessions_ws, monkeypatch):
    # regenerate: всё после последнего user вырезано, генерация от него;
    # _ensure_backend глушится — тест network-free
    make_session("reg", [user_msg("вопрос"), assistant_msg("ответ"),
                         assistant_msg("хвост")])
    monkeypatch.setattr(webui, "_ensure_backend", lambda: None)
    # real _prepare_chat читает глобальный active: подменяем (backend не нужен)
    monkeypatch.setattr(webui.agent, "active",
                        {"model": "fake", "base_url": "x"})
    monkeypatch.setattr(webui.agent, "client", object())
    captured = {}

    def fake_run(messages, **kwargs):
        captured["messages"] = messages
        return {"answer": "заново", "model": "m", "fallback_used": False,
                "usage": {"prompt_tokens": None, "completion_tokens": None},
                "stopped": False}

    monkeypatch.setattr(webui.agent, "run_agent_stream", fake_run)
    events = list(webui.chat_stream_events("reg", "", regenerate=True))
    assert [name for name, _ in events] == ["done"]
    # system сохранён, хвостовая пара/assistant-хвосты вырезаны
    assert captured["messages"][0]["role"] == "system"
    assert captured["messages"][1] == user_msg("вопрос")
    assert len(captured["messages"]) == 2
    assert events[0][1]["answer"] == "заново"


# ---------- регрессии ревью webui-pro ----------

def test_normalize_create_payload_non_dict():
    # ревью P1: POST /api/sessions с телом []/"x"/1 падал AttributeError
    # в обработчике без isinstance-проверки
    assert webui.normalize_create_payload([]) == {}
    assert webui.normalize_create_payload("x") == {}
    assert webui.normalize_create_payload(None) == {}
    assert webui.normalize_create_payload({"id": "a"}) == {"id": "a"}


def test_chat_stream_events_disconnect_stops_generation(sessions_ws, monkeypatch):
    # ревью P1: клиент отключился (генератор закрыт) посреди генерации.
    # Раньше finally брал «что зарегистрировано сейчас» из реестра — при
    # рассинхроне поколений ловил чужой Event или None -> KeyError в
    # pop_cancel, а генерация продолжалась без остановки («зомби»).
    # Теперь Event один на поколение, создан ДО worker-а: finally гасит
    # свой по локальной ссылке, worker видит cancel в своём цикле.
    import threading

    monkeypatch.setattr(webui, "_ensure_backend", lambda: None)
    monkeypatch.setattr(webui.agent, "active",
                        {"model": "fake", "base_url": "x"})
    monkeypatch.setattr(webui.agent, "client", object())
    seen = {}
    closed = threading.Event()

    def fake_run(messages, **kwargs):
        cancel = kwargs["cancel_event"]
        kwargs["on_log"]("[шаг 0]")          # первое событие уходит клиенту
        cancel.wait(timeout=10)              # имитация цикла дельт до отключения
        seen["cancel_observed"] = cancel.is_set()
        return {"answer": "", "model": "m", "fallback_used": False,
                "usage": {"prompt_tokens": None, "completion_tokens": None},
                "stopped": True}

    monkeypatch.setattr(webui.agent, "run_agent_stream", fake_run)

    gen = webui.chat_stream_events("dc", "привет")

    def driver():
        it = iter(gen)
        first = next(it)                     # log-событие: worker уже в fake_run
        assert first[0] == "log"
        gen.close()                          # «клиент отключился» — тем же потоком
        closed.set()

    t = threading.Thread(target=driver, daemon=True)
    t.start()
    assert closed.wait(timeout=10)
    assert seen["cancel_observed"] is True   # флаг дошёл до идущей генерации
    # реестр чист: finally снял СВОЙ Event, worker-side pop подстраховался
    with webui._locks_guard:
        assert "dc" not in webui._cancel_events
