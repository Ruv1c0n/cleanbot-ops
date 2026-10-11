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
    "Чтобы написать файл, используй write_file."
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


def write_file(path: str, content: str) -> str:
    full = safe_path(path)
    answer = input(
        f"\n[?] Записать файл {path} ({len(content)} символов)? Разрешить? [y/N] ")
    if answer.strip().lower() != "y":
        return "Пользователь отказал в записи файла."
    full.parent.mkdir(parents=True, exist_ok=True)
    full.write_text(content, encoding="utf-8")
    return f"Файл {path} записан ({len(content)} символов)."


TASKS_FILE = "tasks.json"          # лежит в workspace/
PRIORITIES = ("low", "mid", "high")
STATUSES = ("open", "done", "all")


def _load_tasks() -> list:
    path = safe_path(TASKS_FILE)
    if not path.exists():
        return []
    return json.loads(path.read_text(encoding="utf-8"))


def _save_tasks(tasks: list) -> None:
    safe_path(TASKS_FILE).write_text(
        json.dumps(tasks, ensure_ascii=False, indent=2), encoding="utf-8"
    )


def add_task(title: str, priority: str, robot_id: str = "") -> str:
    if not title.strip():
        raise ValueError("title не может быть пустым")
    if priority not in PRIORITIES:
        raise ValueError(
            f"priority должен быть одним из: {', '.join(PRIORITIES)}")
    tasks = _load_tasks()
    task = {
        "id": max((t["id"] for t in tasks), default=0) + 1,
        "title": title.strip(),
        "priority": priority,
        "robot_id": robot_id.strip(),
        "status": "open",
    }
    tasks.append(task)
    _save_tasks(tasks)
    return f"Задача #{task['id']} добавлена: {task['title']} (приоритет {priority})"


def list_tasks(status: str = "open", priority: str = "") -> str:
    if status not in STATUSES:
        raise ValueError(f"status должен быть одним из: {', '.join(STATUSES)}")
    if priority and priority not in PRIORITIES:
        raise ValueError(
            f"priority должен быть одним из: {', '.join(PRIORITIES)}")
    tasks = [
        t for t in _load_tasks()
        if (status == "all" or t["status"] == status)
        and (not priority or t["priority"] == priority)
    ]
    if not tasks:
        return "Задач не найдено."
    return "\n".join(
        f"#{t['id']} [{t['priority']}] {t['title']}"
        + (f" (робот {t['robot_id']})" if t["robot_id"] else "")
        for t in tasks
    )


FUNCS = {"list_files": list_files,
         "read_file": read_file,
         "write_file": write_file,
         "add_task": add_task,
         "list_tasks": list_tasks, }

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
    {"type": "function", "function": {
        "name": "write_file",
        "description": "Создать или перезаписать файл в проекте. Вызывай, когда пользователь просит что-то сохранить. Перед записью пользователь подтверждает действие.",
        "parameters": {"type": "object",
                       "properties": {
                           "path": {"type": "string", "description": "Путь к файлу, например 'TODO.md'"},
                           "content": {"type": "string", "description": "Полное содержимое файла"}},
                       "required": ["path", "content"]}}},
    {
        "type": "function",
        "function": {
            "name": "add_task",
            "description": "Добавить задачу уборки в список задач проекта. Вызывай, когда пользователь просит завести, запланировать или добавить задачу. Одна задача на один вызов.",
            "parameters": {
                "type": "object",
                "properties": {
                    "title": {"type": "string", "description": "Краткое название задачи, например 'Помыть зону A'"},
                    "priority": {"type": "string", "enum": ["low", "mid", "high"],
                                 "description": "Приоритет: high для срочных задач, low для несрочных, mid для задач без указания приоритета"},
                    "robot_id": {"type": "string", "description": "Необязательно: идентификатор робота, например 'R1'"},
                },
                "required": ["title", "priority"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "list_tasks",
            "description": "Показать задачи из списка. Вызывай, когда пользователь просит показать, найти или проверить задачи. Срочные задачи это priority=high.",
            "parameters": {
                "type": "object",
                "properties": {
                    "status": {"type": "string", "enum": ["open", "done", "all"],
                               "description": "Какие задачи показать, по умолчанию open"},
                    "priority": {"type": "string", "enum": ["low", "mid", "high"],
                                 "description": "Необязательно: показать только задачи этого приоритета"},
                },
                "required": [],
            },
        },
    },
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
