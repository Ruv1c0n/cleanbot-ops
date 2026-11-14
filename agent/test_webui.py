"""Смоук-тесты webui.py: модуль без сети, страница на месте, валидация чата."""
from pathlib import Path

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


def test_webui_disables_interactive_writes():
    # webui на import выключает input()-подтверждение write_file:
    # иначе POST /api/chat зависает навсегда
    assert webui.agent.CONFIRM_WRITES is False
