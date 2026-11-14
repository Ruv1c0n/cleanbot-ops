"""Юнит-тесты rag.py (лаб. 4). Сеть не нужна: embed подменяется
детерминированными векторами, индекс кладётся в tmp_path — по тому же
принципу monkeypatch-на-модуль, что и тесты context.py в test_agent.py."""
import json

import pytest

import agent  # безопасен без сети: сеть только в select_backend/main
import rag
from rag import chunk_markdown, cosine, hybrid_search, load_or_build_index, \
    vector_search


# ---------- chunk_markdown: окна, перекрытие, метаданные ----------

def _doc(tmp_path, name, text):
    p = tmp_path / name
    p.write_text(text, encoding="utf-8")
    return p


def test_windows_overlap_and_cover_tail(tmp_path):
    # раздел длиннее size: соседние окна перекрываются, хвост доходит до конца
    text = "# Заголовок\n" + "".join(f"строка {i:03d}.\n" for i in range(60))
    chunks = chunk_markdown(_doc(tmp_path, "long.md", text), size=100, overlap=20)
    assert len(chunks) > 1
    assert all(len(c["text"]) <= 100 for c in chunks)
    assert all(c["title"] == "Заголовок" and c["file"] == "long.md"
               for c in chunks)
    for a, b in zip(chunks, chunks[1:]):        # перекрытие = overlap символов
        assert a["text"][-20:] == b["text"][:20]
    assert chunks[-1]["text"].endswith("строка 059.")   # хвост не потерян


def test_header_only_sections_skipped(tmp_path):
    text = "# Пустая\n\n## Тоже пустая\n\n## С текстом\nчто-то есть\n"
    chunks = chunk_markdown(_doc(tmp_path, "sk.md", text), size=800, overlap=150)
    assert len(chunks) == 1
    assert chunks[0]["title"] == "С текстом"


def test_text_before_first_header_gets_stem_title(tmp_path):
    chunks = chunk_markdown(_doc(tmp_path, "intro.md", "без заголовка\nтекст\n"),
                            size=800, overlap=150)
    assert chunks[0]["title"] == "intro"        # имя файла без .md


def test_invalid_size_overlap_rejected(tmp_path):
    p = _doc(tmp_path, "x.md", "# A\nтекст")
    # step = size - overlap: 0 делит на ноль range(), отрицательный молча
    # роняет весь раздел — валидируем явно (находка ревью)
    with pytest.raises(ValueError):
        chunk_markdown(p, size=100, overlap=100)
    with pytest.raises(ValueError):
        chunk_markdown(p, size=100, overlap=150)


# ---------- cosine: шаг 1, включая нулевой вектор ----------

def test_cosine():
    assert cosine([1, 0], [1, 0]) == pytest.approx(1.0)
    assert cosine([1, 0], [0, 1]) == pytest.approx(0.0)
    assert cosine([0, 0], [1, 2]) == 0.0        # гвард знаменателя, не NaN


# ---------- индекс: хэш, cache hit, инвалидация ----------

@pytest.fixture
def rag_env(tmp_path, monkeypatch):
    """Изолированный индекс: docs в tmp, embed — детерминированный."""
    docs = tmp_path / "docs"
    docs.mkdir()
    (docs / "a.md").write_text("# Раздел\nтекст документа\n", encoding="utf-8")
    monkeypatch.setattr(rag, "DOCS_DIR", docs)
    monkeypatch.setattr(rag, "INDEX_DIR", tmp_path / "index")
    monkeypatch.setattr(rag, "INDEX_FILE", tmp_path / "index" / "docs_index.json")
    monkeypatch.setattr(rag, "EMBED_MODEL", "fake-model")
    monkeypatch.setattr(rag, "embed",
                        lambda texts: [[float(len(t)) % 7] * 4 for t in texts])
    return tmp_path


def test_index_rebuilt_then_cache_hit(rag_env, capsys):
    first = load_or_build_index(size=800, overlap=150)
    assert "INDEX: rebuilt" in capsys.readouterr().out
    second = load_or_build_index(size=800, overlap=150)
    assert "INDEX: cache hit" in capsys.readouterr().out
    assert second["chunks"] == first["chunks"]          # без вызовов модели


def test_index_rebuilds_on_model_change(rag_env, capsys):
    load_or_build_index(size=800, overlap=150)
    capsys.readouterr()
    rag.EMBED_MODEL = "other-model"                     # имя модели в хэше
    load_or_build_index(size=800, overlap=150)
    assert "INDEX: rebuilt" in capsys.readouterr().out


def test_index_rebuilds_on_corrupt_file(rag_env, capsys):
    rag.INDEX_DIR.mkdir()
    rag.INDEX_FILE.write_text("не json", encoding="utf-8")
    load_or_build_index(size=800, overlap=150)
    assert "INDEX: rebuilt" in capsys.readouterr().out


# ---------- vector_search / hybrid_search: клампинг и пути ошибок ----------

def _fake_index(n=6):
    """Индекс из n чанков: чанк i — вектор по оси i; запрос ищем по оси 0."""
    chunks = [{"file": f"f{i}.md", "title": f"раздел {i}", "text": f"текст {i}",
               "vector": [1.0 if j == i else 0.0 for j in range(n)]}
              for i in range(n)]
    return {"hash": "x", "model": "fake", "size": 800, "overlap": 150,
            "chunks": chunks}


@pytest.fixture
def vec_env(monkeypatch):
    monkeypatch.setattr(rag, "load_or_build_index", _fake_index)
    monkeypatch.setattr(rag, "embed", lambda texts: [[1.0] + [0.0] * 5])
    monkeypatch.setattr(rag, "MIN_SCORE", 0.5)


def test_vector_limit_clamped_like_keyword(vec_env):
    assert len(vector_search("запрос", limit=0).split("\n\n")) == 1
    assert len(vector_search("запрос", limit=99).split("\n\n")) == 5


def test_vector_error_paths(vec_env, monkeypatch):
    with pytest.raises(ValueError):
        vector_search("   ")
    monkeypatch.setattr(rag, "MIN_SCORE", 1.5)          # порог выше cos=1.0
    assert vector_search("запрос").startswith("ОШИБКА: нет похожих")


def test_vector_empty_index(monkeypatch):
    monkeypatch.setattr(rag, "load_or_build_index",
                        lambda: {"hash": "x", "chunks": []})
    monkeypatch.setattr(rag, "embed", lambda texts: [[1.0]])
    assert "docs/ пуста" in vector_search("запрос")


def test_hybrid_error_needs_both_searches_to_fail(monkeypatch):
    # keyword нули + вектор ниже порога -> ОШИБКА; достаточно точного слова —
    # hybrid отвечает, даже если косинус низкий
    chunks = [{"file": "f.md", "title": "t", "text": "расписание уборки",
               "vector": [1.0, 0.0]}]
    monkeypatch.setattr(rag, "load_or_build_index",
                        lambda: {"hash": "x", "chunks": chunks})
    monkeypatch.setattr(rag, "embed", lambda texts: [[0.1, 1.0]])  # cos ~0.1
    monkeypatch.setattr(rag, "MIN_SCORE", 0.5)
    assert hybrid_search("рецепт борща").startswith("ОШИБКА")   # kw нули
    assert "расписание" in hybrid_search("расписание")          # kw > 0


# ---------- стык тулов и реестра ----------

def test_tools_and_funcs_in_sync():
    # каждое имя тулов из TOOLS обязано иметь реализацию в FUNCS, иначе
    # модель получит «неизвестный инструмент» посреди сессии. Обратное
    # направление не строго: в FUNCS живут легаси-алиасы (search_docs
    # для старых сессий лаб. 3), которые модели не показываются
    tool_names = {t["function"]["name"] for t in agent.TOOLS}
    assert tool_names <= set(agent.FUNCS)
