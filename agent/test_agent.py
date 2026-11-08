"""Юнит-тесты контекстной логики (issue #15). Сеть и Ollama не нужны:
тестируем context.py напрямую, summarize подменяем колбэком."""
import re
from pathlib import Path

import pytest

import context
from context import (SESSIONS_DIR, _session_path, ensure_context_budget,
                     estimate_tokens, safe_path, search_docs)


def user(text):
    return {"role": "user", "content": text}


def assistant(text, tool_calls=None):
    m = {"role": "assistant", "content": text}
    if tool_calls:
        m["tool_calls"] = tool_calls
    return m


def tool(call_id, text):
    return {"role": "tool", "tool_call_id": call_id, "content": text}


def big_tools(chars):
    """Фейковые tools с сериализацией примерно в chars символов (~chars/4 усл. т.)."""
    return [{"type": "function", "function": {
        "name": " filler_" + "x" * chars, "parameters": {}}}]


def no_orphan_tools(messages):
    """После обрезки нет role: tool без предшествующего tool_calls."""
    for i, m in enumerate(messages):
        if m.get("role") == "tool":
            prev = messages[i - 1]
            calls = prev.get("tool_calls") or ()
            assert any(c["id"] == m["tool_call_id"] for c in calls), \
                f"осиротевший tool на позиции {i}"


# ---------- ensure_context_budget: trim ----------

def test_no_trim_under_budget():
    msgs = [{"role": "system", "content": "sys"}] + \
           [user("привет"), assistant("ответ")]
    out = ensure_context_budget(list(msgs), budget=10_000)
    assert out == msgs


def test_trim_drops_whole_turns_and_keeps_system(capsys):
    msgs = [{"role": "system", "content": "SYSTEM"}]
    msgs += [user("ход-один " * 30),
             assistant(None, tool_calls=[{"id": "c1", "type": "function",
                                          "function": {"name": "search_docs",
                                                       "arguments": "{}"}}]),
             tool("c1", "результат инструмента " * 20),
             assistant("ответ первого хода " * 15),
             user("ход-два " * 30),
             assistant("ответ второго хода " * 15)]
    out = ensure_context_budget(msgs, budget=300)
    text = " ".join(str(m.get("content")) for m in out)
    assert out[0]["role"] == "system" and out[0]["content"] == "SYSTEM"
    assert "ход-один" not in text                 # первый ход удалён целиком
    assert "результат инструмента" not in text
    assert "ход-два" in text                      # последний ход выжил
    no_orphan_tools(out)
    assert "[TRIM: удалено 4 сообщений;" in capsys.readouterr().out


def test_trim_counts_tools_regression():
    """Регресс на баг из лабы: размер должен считаться вместе с tools.
    История ~300 усл. т., tools ~600, бюджет 900 -> обрезка обязана сработать
    (старый код без tools видел меньше бюджета и не резал)."""
    msgs = [{"role": "system", "content": "SYSTEM"}]
    msgs += [user("u" * 400),
             assistant(None, tool_calls=[{"id": "c1", "type": "function",
                                          "function": {"name": "read_file",
                                                       "arguments": "{}"}}]),
             tool("c1", "t" * 300),
             assistant("a" * 200),
             user("второй" * 40),
             assistant("ответ" * 40)]
    history = estimate_tokens(msgs)
    tools = big_tools(2400)
    assert history < 900                          # без tools старый код не резал бы
    assert 570 <= estimate_tokens(msgs, tools) - history <= 650  # tools ~600
    assert estimate_tokens(msgs, tools) > 900     # с tools бюджет превышен
    out = ensure_context_budget(msgs, budget=900, tools=tools)
    assert estimate_tokens(out, tools) <= 900     # обрезка сработала
    no_orphan_tools(out)
    assert out[0]["content"] == "SYSTEM"


def test_trim_single_turn_cannot_drop(capsys):
    msgs = [{"role": "system", "content": "SYSTEM"},
            user("u" * 4000), assistant("a" * 4000)]
    out = ensure_context_budget(list(msgs), budget=10)
    assert out == msgs                            # кроме последнего хода резать нечего
    assert "удалять нечего" in capsys.readouterr().out


# ---------- ensure_context_budget: summarize ----------

def test_summarize_strategy_structure():
    dropped_witness = []

    def fake_summarize(dropped):
        dropped_witness.extend(dropped)
        return "ПАМЯТЬ ДИАЛОГА"

    msgs = [{"role": "system", "content": "SYSTEM"},
            user("первый" * 40), assistant("ответ1" * 40),
            user("второй" * 40), assistant("ответ2" * 40)]
    out = ensure_context_budget(list(msgs), budget=250, strategy="summarize",
                                summarize_fn=fake_summarize)
    assert [m["role"] for m in out] == ["system", "user", "user", "assistant"]
    assert out[0]["content"] == "SYSTEM"
    assert out[1] == {"role": "user", "content": "ПАМЯТЬ ДИАЛОГА"}
    assert out[2:] == msgs[3:]                    # остаток — второй ход
    assert dropped_witness == msgs[1:3]           # сжат первый ход


def test_summarize_without_callback_raises():
    msgs = [{"role": "system", "content": "SYSTEM"},
            user("первый" * 40), assistant("ответ1" * 40),
            user("второй" * 40), assistant("ответ2" * 40)]
    with pytest.raises(ValueError):
        ensure_context_budget(list(msgs), budget=250, strategy="summarize")


# ---------- estimate_tokens ----------

def test_estimate_tokens_counts_tools():
    msgs = [user("в" * 400), assistant("о" * 400)]
    base = estimate_tokens(msgs)
    assert estimate_tokens(msgs, big_tools(2400)) > base + 500


# ---------- _session_path ----------

def test_session_path_traversal_stays_in_sessions():
    p = _session_path("../../.env")
    assert p.parent == SESSIONS_DIR
    assert p.is_relative_to(SESSIONS_DIR)
    assert p.suffix == ".json"


# ---------- search_docs ----------

@pytest.fixture
def docs_ws(tmp_path, monkeypatch):
    docs = tmp_path / "docs"
    docs.mkdir()
    (docs / "a.md").write_text(
        "Первый абзац: уборка, уборка и снова уборка.\n\n"
        "Второй абзац про робота.\n",
        encoding="utf-8")
    (docs / "b.md").write_text("Про уборка в другом файле.\n\n"
                               "Что-то совсем другое.\n", encoding="utf-8")
    monkeypatch.setattr(context, "WORKSPACE", tmp_path)
    return tmp_path


def test_search_docs_ranks_by_score(docs_ws):
    out = search_docs("уборка")
    assert out.startswith("1. a.md (score=3): Первый абзац")
    assert out.index("Первый абзац") < out.index("в другом файле")


def test_search_docs_limit_clamped(tmp_path, monkeypatch):
    docs = tmp_path / "docs"
    docs.mkdir()
    (docs / "a.md").write_text(
        "\n\n".join(f"Запись {i}: уборка." for i in range(7)), encoding="utf-8")
    monkeypatch.setattr(context, "WORKSPACE", tmp_path)
    assert len(search_docs("уборка", limit=99).split("\n\n")) == 5
    assert len(search_docs("уборка", limit=0).split("\n\n")) == 1


def test_search_docs_empty_query_raises(docs_ws):
    with pytest.raises(ValueError):
        search_docs("   ")
    with pytest.raises(ValueError):
        search_docs("аб вг")            # нет слов длиннее 2 символов


def test_search_docs_no_results_plain_string(docs_ws):
    out = search_docs("несуществующееслово")
    assert isinstance(out, str)
    assert "ничего не найдено" in out


# ---------- safe_path ----------

def test_safe_path_escape_raises(tmp_path, monkeypatch):
    monkeypatch.setattr(context, "WORKSPACE", tmp_path)
    with pytest.raises(ValueError):
        safe_path("../outside.txt")
    with pytest.raises(ValueError):
        safe_path("../../.env")
    with pytest.raises(ValueError):
        safe_path("..")


def test_safe_path_inside_ok(tmp_path, monkeypatch):
    monkeypatch.setattr(context, "WORKSPACE", tmp_path)
    assert safe_path("docs/x.md") == (tmp_path / "docs" / "x.md").resolve()
    assert safe_path("sub/../file.txt").name == "file.txt"


# ---------- регрессия: fallback не должен быть мёртвым (issue #16) ----------
def test_fallback_regression_import_openai():
    """Баг: chat() ловила openai.APIConnectionError, но модуль openai в
    agent.py не импортировался — NameError внутри except убивал обработчик,
    и переключение на онлайн-модель не срабатывало никогда. Фикс — явный
    import openai. Проверяем исходник, а не импортируем agent.py: тот при
    импорте стучится к Ollama, а в тестах сети нет."""
    src = Path(__file__).with_name("agent.py").read_text(encoding="utf-8")
    assert re.search(r"^import openai$", src, re.M), \
        "agent.py должен импортировать openai — иначе except openai.* мёртв"
    assert "openai.APIConnectionError" in src, \
        "fallback-обработчик APIConnectionError должен остаться на месте"
