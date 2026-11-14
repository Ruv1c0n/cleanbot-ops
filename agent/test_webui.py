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
