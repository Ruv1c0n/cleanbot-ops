"""Смоук-тесты webui.py: модуль без сети, страница на месте, валидация чата."""
from pathlib import Path

import pytest

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
