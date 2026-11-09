"""Чистая контекстная логика агента (без сети): оценка размера контекста,
бюджет/усечение истории, пути сессий и песочницы, поиск по документам.

Вынесено из agent.py (issue #15), чтобы тестировать без импорта agent.py —
тот при импорте стучится к Ollama. Поведение функций не изменено.
"""
import json
import re
from pathlib import Path

WORKSPACE = (Path(__file__).parent / "workspace").resolve()   # песочница
SESSIONS_DIR = Path(__file__).parent / "sessions"
STRATEGY = "trim"           # trim | summarize
CONTEXT_BUDGET = 6000       # бюджет контекста в «условных токенах»


# ---------- шаг 1: сессия на диске ----------
def _session_path(session_id: str) -> Path:
    # защищаемся от путей вроде ../../.env в имени сессии
    safe = re.sub(r"[^\w.-]", "_", session_id)
    return SESSIONS_DIR / f"{safe}.json"


# ---------- шаг 2: оценка размера контекста ----------
def estimate_tokens(messages: list, tools=None) -> int:
    """Грубая оценка токенов: сумма len(text) // 4 по messages + tools."""
    size = len(json.dumps(messages, ensure_ascii=False))
    if tools:
        size += len(json.dumps(tools, ensure_ascii=False))
    return size // 4


# ---------- шаги 3-4: бюджет контекста ----------
def _turn_starts(messages: list) -> list:
    """Индексы, с которых начинаются ходы пользователя (system — messages[0])."""
    return [i for i, m in enumerate(messages) if m["role"] == "user"]


def ensure_context_budget(messages: list, budget: int = CONTEXT_BUDGET,
                          strategy: str = STRATEGY, tools=None,
                          summarize_fn=None) -> list:
    """Держит историю в пределах бюджета. Размер считается вместе с
    сериализованными tools — они тоже уходят в каждый запрос. Ходы удаляются
    только целиком (user вместе с ответом ассистента и tool-сообщениями),
    поэтому «осиротевших» role: tool без предшествующего tool_calls не
    остаётся. system сохраняется всегда.

    summarize_fn — колбэк сжатия (в agent.py это summarize, зовущий модель);
    он передаётся отдельно, чтобы тестировать стратегию summarize без сети."""
    est = estimate_tokens(messages, tools)
    if est <= budget:
        return messages
    dropped = []
    while estimate_tokens(messages, tools) > budget and len(_turn_starts(messages)) > 1:
        starts = _turn_starts(messages)
        cut = starts[1]                     # граница первого хода
        dropped += messages[starts[0]:cut]
        messages = messages[:starts[0]] + messages[cut:]
    if not dropped:
        print(f"[TRIM: превышение бюджета ({est} > {budget}), "
              f"но кроме последнего хода удалять нечего]")
        return messages
    if strategy == "summarize":
        if summarize_fn is None:
            raise ValueError(
                "strategy='summarize' требует summarize_fn (колбэк сжатия)")
        # Прежние «память диалога» (system-сообщения сразу после основного
        # system) вливаем в новое сжатие: пересжимаясь поодиночке при каждом
        # превышении, факты в них деградировали бы, а без вливания они
        # накапливались бы — по одному сообщению на каждое превышение.
        head, tail = messages[:1], messages[1:]
        old_memory = []
        while tail and tail[0]["role"] == "system":
            old_memory.append(tail.pop(0))
        summary = summarize_fn(old_memory + dropped)
        folded = f" (+{len(old_memory)} старой памяти)" if old_memory else ""
        print(f"[SUMMARY: {len(dropped)} сообщений сжаты в память диалога"
              f"{folded}; {est} -> {estimate_tokens(head + tail, tools)} усл. токенов]")
        # Роль system (сразу после основного system): некоторые OpenAI-совместимые
        # серверы плохо переносят два подряд идущих user-сообщения.
        return head + [{"role": "system", "content": summary}] + tail
    print(f"[TRIM: удалено {len(dropped)} сообщений; "
          f"{est} -> {estimate_tokens(messages, tools)} усл. токенов]")
    return messages


# ---------- инструменты ----------
def safe_path(rel: str) -> Path:
    # разворачивает '..' в настоящий путь
    p = (WORKSPACE / rel).resolve()
    if not p.is_relative_to(WORKSPACE):      # выход за песочницу запрещён
        raise ValueError("путь вне песочницы")
    return p


# ---------- шаг 5: поиск по документам проекта ----------
def search_docs(query: str, limit: int = 3) -> str:
    """Keyword-поиск по workspace/docs/. Вне песочницы не выходит: путь
    жёстко задан, пользовательский ввод влияет только на текст запроса."""
    if not str(query).strip():
        raise ValueError("query не может быть пустым")
    limit = max(1, min(int(limit), 5))
    # регистронезависимо, значимые слова >= 3 символов
    words = [w for w in re.findall(r"\w+", str(query).lower()) if len(w) > 2]
    if not words:
        raise ValueError("в query нет значимых слов")
    scored = []
    for path in sorted((WORKSPACE / "docs").glob("*.md")):
        paragraphs = [p.strip() for p in
                      path.read_text(encoding="utf-8").split("\n\n") if p.strip()]
        for para in paragraphs:
            low = para.lower()
            score = sum(low.count(w) for w in words)
            if score:
                scored.append((score, path.name, para))
    if not scored:
        return f"По запросу '{query}' ничего не найдено."
    scored.sort(key=lambda x: -x[0])
    return "\n\n".join(
        f"{n}. {name} (score={score}): {para[:400]}"
        for n, (score, name, para) in enumerate(scored[:limit], 1)
    )
