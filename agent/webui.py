"""Веб-чат GUI агента cleanbot-ops (вне ТЗ лабы, коммит по запросу).

stdlib-сервер: python webui.py [--port 8765] — браузер на http://localhost:8765.
Страница agent/webui.html, API: GET /api/info (модель), POST /api/chat
{session, message} -> {answer, log}. Логика агента переиспользуется как есть
(select_backend/load_session/run_agent), stdout агентного цикла перехватывается
и уходит в интерфейс как «лог шага» (шаги, вызовы тулов и их эхо).

Один глобальный лок на весь чат: agent.SESSION_ID — модульная глобаль,
превращать её в per-session состояние ради одного локального пользователя
не стоит. id сессии валидируется: он попадает в путь файла сессии.
"""
import argparse
import contextlib
import io
import json
import re
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

import agent

# write_file подтверждает запись через input(), но stdout webui перехвачен
# redirect_stdout: промпт уходит в буфер, POST /api/chat висит вечно, а ввод
# в консоли сервера «слепо» авторизует запись без показанного превью
# (находка ревью). В веб-режиме подтверждения нет — write_file сразу
# возвращает отказ; консольный агент работает как раньше
agent.CONFIRM_WRITES = False

HTML_FILE = Path(__file__).parent / "webui.html"
SESSION_RE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
chat_lock = threading.Lock()
backend_ready = False

# сервер слушает только 127.0.0.1 — запросы с этих хостов «свои».
# ::1 включён: на Windows localhost может резолвиться в IPv6
ALLOWED_HOSTS = {"localhost", "127.0.0.1", "[::1]"}


def _hostname(netloc: str) -> str:
    """Хост из authority без порта; IPv6 в скобках сохраняет их."""
    netloc = netloc.lower()
    if netloc.startswith("["):
        return netloc.partition("]")[0] + "]"
    return netloc.partition(":")[0]


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
                or _hostname(parsed.netloc) not in ALLOWED_HOSTS:
            return f"Origin {origin!r} не localhost"
    return None


def validate_chat(payload: dict) -> str | None:
    """Валидация запроса чата: id сессии попадает в путь файла сессии,
    поэтому кроме пустоты проверяем и форму. None — запрос корректен."""
    session = payload.get("session", "web")
    if not SESSION_RE.match(str(session)):
        return "некорректный id сессии"
    if not str(payload.get("message", "")).strip():
        return "пустое сообщение"
    return None


def _ensure_backend() -> None:
    """select_backend один раз на процесс — при первом обращении к API."""
    global backend_ready
    if not backend_ready:
        agent.select_backend()
        backend_ready = True


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

    def do_GET(self):
        if self._deny_foreign():
            return
        if self.path == "/":
            self._send(200, HTML_FILE.read_bytes(), "text/html; charset=utf-8")
        elif self.path == "/api/info":
            try:
                with chat_lock:
                    _ensure_backend()
                self._json(200, {"model": agent.active["model"],
                                 "backend": agent.active["base_url"]})
            except Exception as e:
                self._json(500, {"error": str(e)})
        else:
            self._send(404, "не найдено", "text/plain; charset=utf-8")

    def do_POST(self):
        if self._deny_foreign():
            return          # 403 до разбора тела — тело не читается вовсе
        if self.path != "/api/chat":
            self._send(404, "не найдено", "text/plain; charset=utf-8")
            return
        try:
            length = int(self.headers.get("Content-Length", 0))
            payload = json.loads(self.rfile.read(length).decode("utf-8"))
            error = validate_chat(payload)
            if error:
                self._json(400, {"error": error})
                return
            session = payload["session"]
            message = str(payload["message"]).strip()
            with chat_lock:
                _ensure_backend()
                agent.SESSION_ID = session
                messages = agent.load_session(session, agent.SYSTEM_PROMPT)
                messages.append({"role": "user", "content": message})
                # stdout агентного цикла (шаги, эхо тулов) -> лог в интерфейс
                buf = io.StringIO()
                with contextlib.redirect_stdout(buf):
                    answer = agent.run_agent(messages)
                agent.save_session(session, messages)
            self._json(200, {"answer": answer,
                             "log": buf.getvalue().splitlines()})
        except Exception as e:
            self._json(500, {"error": str(e)})

    def log_message(self, *args):        # тихий сервер: чат и так логирует
        pass


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Веб-чат агента cleanbot-ops")
    parser.add_argument("--port", type=int, default=8765,
                        help="порт (по умолчанию 8765; 8080 занят Docker)")
    args = parser.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"GUI агента: http://localhost:{args.port}  (Ctrl+C — выход)")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nостановлено")
