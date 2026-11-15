"""Провайдеры моделей по API-ключу (webui): хранение и валидация.

Пользователь добавляет собственные OpenAI-совместимые бэкенды (base_url +
api_key) через веб-чат; их модели появляются в селекторе моделей. Файл
agent/providers.json лежит рядом с sessions/ и в git не попадает (ключи!).
Запись атомарная (tmp + os.replace) — читатель никогда не видит
полузаписанный файл; битый файл -> []: список чатов не должен ломаться
из-за одного повреждённого файла конфигурации, как и битая сессия.

api_key никогда не возвращается клиенту целиком: наружу идёт только
key_mask («***» + последние 4 символа). redact() маскирует известные
ключи в текстах ошибок — openai может вложить ключ в сообщение
исключения, и он не должен доехать до клиента или лога.
"""
import json
import os
import threading
from pathlib import Path
from urllib.parse import urlsplit

PROVIDERS_FILE = Path(__file__).parent / "providers.json"

# CRUD-цикл (проверка дубликата + запись) атомарен под этим локом: два
# параллельных POST с одним name не должны оба пройти проверку
LOCK = threading.Lock()

NAME_MAX = 64  # имя уезжает в URL маршрута DELETE — без «/» и разумной длины


def _base_url_valid(base_url: str) -> bool:
    """base_url — http/https с непустым хостом."""
    parts = urlsplit(base_url)
    return parts.scheme in ("http", "https") and bool(parts.hostname)


def validate_provider(payload) -> dict | str:
    """Тело POST /api/providers -> нормализованная запись или текст 400.
    Неизвестные поля отклоняются: опечатка (apu_key) не должна молча
    теряться, а клиент думал, что провайдер сохранён."""
    if not isinstance(payload, dict):
        return "тело должно быть JSON-объектом"
    for key in payload:
        if key not in ("name", "base_url", "api_key", "enabled"):
            return f"неизвестное поле {key!r}"
    name = payload.get("name")
    if not isinstance(name, str) or not name.strip():
        return "name должен быть непустой строкой"
    name = name.strip()
    if len(name) > NAME_MAX or "/" in name:
        return f"name должен быть строкой до {NAME_MAX} символов без «/»"
    base_url = payload.get("base_url")
    if not isinstance(base_url, str) \
            or not _base_url_valid(base_url.strip()):
        return "base_url должен быть http/https URL с хостом"
    api_key = payload.get("api_key")
    if not isinstance(api_key, str) or not api_key.strip():
        return "api_key должен быть непустой строкой"
    enabled = payload.get("enabled", True)
    if not isinstance(enabled, bool):
        return "enabled должен быть bool"
    return {"name": name, "base_url": base_url.strip(),
            "api_key": api_key.strip(), "enabled": enabled}


def _normalize_entry(entry) -> dict | None:
    """Одна запись файла -> валидный dict. None — запись битая, пропускаем:
    один кривой объект не должен прятать остальные провайдеры."""
    if not isinstance(entry, dict):
        return None
    # validate_provider возвращает текст ошибки строкой, а не бросает —
    # битая запись отфильтровывается (None)
    result = validate_provider({
        "name": entry.get("name"), "base_url": entry.get("base_url"),
        "api_key": entry.get("api_key"),
        "enabled": entry.get("enabled", True)})
    return result if isinstance(result, dict) else None


def load_providers(path: Path | None = None) -> list:
    """Список провайдеров с диска. Отсутствие файла, битый JSON или битая
    запись -> валидные записи / [] — ошибки чтения не роняют обработчики."""
    path = path or PROVIDERS_FILE
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError, ValueError):
        return []
    if not isinstance(data, list):
        return []
    return [e for e in (_normalize_entry(x) for x in data) if e is not None]


def save_providers(items: list, path: Path | None = None) -> None:
    """Атомарная запись списка (tmp + os.replace, как save_meta в agent)."""
    path = path or PROVIDERS_FILE
    path.parent.mkdir(exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(json.dumps(items, ensure_ascii=False, indent=2),
                   encoding="utf-8")
    os.replace(tmp, path)


def enabled_backends(path: Path | None = None) -> list:
    """Конфиги включённых провайдеров для resolve_model: dict с base_url и
    api_key — make_client/_backend_model_ids принимают их как есть."""
    return [p for p in load_providers(path) if p["enabled"]]


def mask_key(api_key: str) -> str:
    """Маска ключа для UI: видны только последние 4 символа."""
    return "***" + api_key[-4:] if api_key else "нет ключа"


def public_view(entry: dict) -> dict:
    """Ответ сервера клиенту: ключ заменён маской, сам api_key не отдаётся."""
    return {"name": entry["name"], "base_url": entry["base_url"],
            "enabled": entry["enabled"],
            "key_mask": mask_key(entry["api_key"])}


def known_secrets(path: Path | None = None) -> set:
    """Все известные ключи: провайдеры + .env (LOCAL/FALLBACK). Для redact."""
    secrets = set()
    for p in load_providers(path):
        if p["api_key"]:
            secrets.add(p["api_key"])
    for var in ("API_KEY", "FALLBACK_API_KEY"):
        value = os.getenv(var)
        if value:
            secrets.add(value)
    return secrets


def redact(text, path: Path | None = None) -> str:
    """Маскирует известные ключи в тексте (ошибки исключений, логи):
    ключ длиной < 4 не маскируем осмысленно, но и не пробрасываем —
    заменяем целиком, как и остальные."""
    out = str(text)
    for secret in known_secrets(path):
        if secret:
            out = out.replace(secret, "***")
    return out
