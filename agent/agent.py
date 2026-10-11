import json
import os
from pathlib import Path
from dotenv import load_dotenv
from openai import OpenAI

load_dotenv()


LOCAL = dict(base_url=os.getenv("BASE_URL"), api_key=os.getenv("API_KEY"),
             model=os.getenv("MODEL"))
FALLBACK = dict(base_url=os.getenv("FALLBACK_BASE_URL", "https://openrouter.ai/api/v1"),
                api_key=os.getenv("FALLBACK_API_KEY"),
                model=os.getenv("FALLBACK_MODEL", "openrouter/free"))


def make_client(cfg):
    return OpenAI(base_url=cfg["base_url"], api_key=cfg["api_key"],
                  max_retries=0, timeout=120)


def local_available() -> bool:
    """Локальный сервер отвечает и нужная модель скачана."""
    try:
        ids = [m.id for m in make_client(LOCAL).with_options(
            timeout=3).models.list().data]
        return LOCAL["model"] in ids
    except Exception:
        return False


active = LOCAL if local_available() else FALLBACK
if active is FALLBACK and not FALLBACK["api_key"]:
    raise SystemExit(
        "Локальная модель недоступна, а FALLBACK_API_KEY не задан")
client = make_client(active)
print(f"[модель: {active['model']} @ {active['base_url']}]")


def chat(**kwargs):
    """Запрос к модели. Если локальный сервер упал посреди работы, переключаемся на онлайн."""
    global active, client
    try:
        return client.chat.completions.create(model=active["model"], **kwargs)
    except openai.APIConnectionError:
        if active is FALLBACK or not FALLBACK["api_key"]:
            raise
        print("[локальная модель недоступна, переключаюсь на онлайн-модель]")
        active, client = FALLBACK, make_client(FALLBACK)
        return client.chat.completions.create(model=active["model"], **kwargs)


WORKSPACE = (Path(__file__).parent / "workspace").resolve()   # песочница
# лимит шагов цикла
MAX_STEPS = 8
SYSTEM_PROMPT = (
    "Ты ассистент по проекту. Файлы проекта лежат в песочнице. "
    "Чтобы узнать содержимое, используй инструменты list_files и read_file. "
    "Отвечай по-русски, кратко."
)


# ---------- инструменты ----------
def safe_path(rel: str) -> Path:
    # разворачивает '..' в настоящий путь
    p = (WORKSPACE / rel).resolve()
    if not p.is_relative_to(WORKSPACE):      # выход за песочницу запрещён
        raise ValueError("путь вне песочницы")
    return p


def list_files(path: str = ".") -> str:
    p = safe_path(path)
    if not p.is_dir():
        raise ValueError(f"{path} не является папкой")
    items = sorted(x.name + ("/" if x.is_dir() else "") for x in p.iterdir())
    return "\n".join(items) or "(пусто)"


def read_file(path: str) -> str:
    p = safe_path(path)
    if not p.is_file():
        raise FileNotFoundError(f"файл {path} не найден")
    # обрезаем, чтобы не раздувать контекст
    return p.read_text(encoding="utf-8")[:8000]


FUNCS = {"list_files": list_files, "read_file": read_file}

TOOLS = [
    {"type": "function", "function": {
        "name": "list_files",
        "description": "Показать файлы и папки в директории песочницы. Вызывай, чтобы узнать, какие файлы есть.",
        "parameters": {"type": "object",
                       "properties": {"path": {"type": "string", "description": "Папка, по умолчанию '.'"}},
                       "required": []}}},
    {"type": "function", "function": {
        "name": "read_file",
        "description": "Прочитать текстовый файл из песочницы по относительному пути.",
        "parameters": {"type": "object",
                       "properties": {"path": {"type": "string", "description": "Например README.md"}},
                       "required": ["path"]}}},
]


def execute_tool(name: str, raw_args: str) -> str:
    """Любая ошибка возвращается строкой, а не исключением."""
    try:
        if name not in FUNCS:
            return f"ОШИБКА: неизвестный инструмент {name}"
        # модель присылает аргументы JSON-строкой
        args = json.loads(raw_args or "{}")
        return str(FUNCS[name](**args))
    except Exception as e:
        return f"ОШИБКА: {e}"


# ---------- агентный цикл ----------
def run_agent(messages: list) -> str:
    for step in range(MAX_STEPS):
        response = chat(messages=messages, tools=TOOLS)
        choice = response.choices[0]
        msg = choice.message
        print(f"[шаг {step}: model={response.model} in={response.usage.prompt_tokens} "
              f"out={response.usage.completion_tokens} finish={choice.finish_reason}]")

        # ответ ассистента сохраняем всегда
        messages.append(msg.model_dump(exclude_none=True))

        if not msg.tool_calls:                               # инструментов нет: это итоговый ответ
            return msg.content

        for call in msg.tool_calls:
            result = execute_tool(call.function.name, call.function.arguments)
            print(
                f"🔧 {call.function.name}({call.function.arguments}) -> {result[:200]}")
            messages.append(
                {"role": "tool", "tool_call_id": call.id, "content": result})

    return "Достигнут лимит шагов, задача не завершена."


if __name__ == "__main__":
    messages = [{"role": "system", "content": SYSTEM_PROMPT}]
    while True:
        user_input = input("\n> ").strip()
        if user_input in ("exit", "quit", "выход"):
            break
        if not user_input:
            continue
        messages.append({"role": "user", "content": user_input})
        print("🤖", run_agent(messages))
