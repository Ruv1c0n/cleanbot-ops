"""RAG-модуль лаб. 4: эмбеддинги и косинусная близость, chunking
документов, индекс на диске с пересчётом по хэшу и векторный поиск.

Шаги 1-3 методички написаны вручную. Эмбеддинги по умолчанию — локальная
Ollama (OpenAI-совместимый /v1/embeddings), при недоступности — OpenRouter
(ключ и адрес берутся из .env: EMBED_* переопределяют, иначе fallback из
FALLBACK_*). Индекс и запросы считаются одной и той же моделью: имя модели
входит в хэш индекса, смена модели вызывает пересчёт.
"""
import hashlib
import json
import os
import re
import time
from pathlib import Path

import numpy as np
from dotenv import load_dotenv
from openai import OpenAI

# _stem — общий lexical-скоринг с keyword-поиском лаб. 3 (context.py)
from context import _stem

load_dotenv()

WORKSPACE = (Path(__file__).parent / "workspace").resolve()
DOCS_DIR = WORKSPACE / "docs"
INDEX_DIR = Path(__file__).parent / "index"          # вне песочницы: агент
INDEX_FILE = INDEX_DIR / "docs_index.json"           # не затрёт через write_file

# эмбеддинги: по умолчанию локальная Ollama; EMBED_BASE_URL/EMBED_MODEL
# переключают на OpenRouter или другую модель
EMBED_BASE_URL = os.getenv("EMBED_BASE_URL", "http://localhost:11434/v1")
EMBED_API_KEY = os.getenv("EMBED_API_KEY", "ollama")
EMBED_MODEL = os.getenv("EMBED_MODEL", "bge-m3")

# порог «похожести»: у разных моделей разный масштаб косинуса
MIN_SCORE = float(os.getenv("MIN_SCORE", "0.45"))

# размер окна и перекрытие настраиваются окружением (эксперименты шага 6:
# CHUNK_SIZE=300/1500 python eval_search.py)
CHUNK_SIZE = int(os.getenv("CHUNK_SIZE", "800"))
CHUNK_OVERLAP = int(os.getenv("CHUNK_OVERLAP", "150"))


def _embed_client() -> OpenAI:
    return OpenAI(base_url=EMBED_BASE_URL, api_key=EMBED_API_KEY,
                  max_retries=0, timeout=120)


# ---------- шаг 1: эмбеддинг и косинусная близость ----------
def embed(texts: list[str]) -> list[list[float]]:
    """Векторы для списка текстов одним вызовом (пачка, не по одному)."""
    if not texts:
        return []
    resp = _embed_client().embeddings.create(model=EMBED_MODEL, input=texts)
    return [d.embedding for d in resp.data]


def cosine(a, b) -> float:
    a, b = np.asarray(a, dtype=float), np.asarray(b, dtype=float)
    denom = np.linalg.norm(a) * np.linalg.norm(b)
    if denom == 0:
        return 0.0
    return float(np.dot(a, b) / denom)


# ---------- шаг 2: chunking документов ----------
def chunk_markdown(path: Path, size: int = CHUNK_SIZE,
                   overlap: int = CHUNK_OVERLAP) -> list[dict]:
    """Фрагменты одного .md: разрез по заголовкам #/##/###, длинные
    разделы — окнами по size символов с перекрытием overlap. У каждого
    фрагмента метаданные file и title; разделы без текста пропускаются."""
    chunks = []
    # step = size - overlap: overlap >= size даёт step <= 0 — range() падает
    # или молча выбрасывает весь раздел, поэтому валидируем явно
    if not 0 < overlap < size:
        raise ValueError(f"нужен 0 < overlap < size, получено "
                         f"size={size}, overlap={overlap}")
    # заголовок может быть и в середине окна длинного раздела — режем только
    # по строкам, целиком начинающимся с #
    sections, title = [], ""
    for line in path.read_text(encoding="utf-8").splitlines():
        if re.match(r"^#{1,3}\s", line):
            title = line.lstrip("#").strip()
            sections.append([title, []])
        else:
            if not sections:                     # текст до первого заголовка
                sections.append([path.stem, []])
            sections[-1][1].append(line)
    for sec_title, lines in sections:
        text = "\n".join(lines).strip()
        if not text:                             # раздел без текста — мимо
            continue
        if len(text) <= size:
            chunks.append({"file": path.name, "title": sec_title, "text": text})
            continue
        step = size - overlap                    # шаг окна < размера окна
        for start in range(0, len(text), step):
            chunks.append({"file": path.name, "title": sec_title,
                           "text": text[start:start + size]})
            if start + size >= len(text):        # хвост уже покрыт окном
                break
    return chunks


def _all_chunks(size: int = CHUNK_SIZE, overlap: int = CHUNK_OVERLAP) -> list[dict]:
    chunks = []
    for path in sorted(DOCS_DIR.glob("*.md")):
        chunks += chunk_markdown(path, size, overlap)
    return chunks


# ---------- шаг 3: индекс на диске с пересчётом по хэшу ----------
def _index_hash(size: int, overlap: int) -> str:
    """Хэш имени модели, size/overlap и содержимого всех .md в docs/."""
    h = hashlib.sha256()
    h.update(EMBED_MODEL.encode())
    h.update(f"{size}/{overlap}".encode())
    for path in sorted(DOCS_DIR.glob("*.md")):
        h.update(path.name.encode())
        h.update(path.read_bytes())
    return h.hexdigest()


def load_or_build_index(size: int = CHUNK_SIZE,
                        overlap: int = CHUNK_OVERLAP) -> dict:
    """Загружает индекс с диска, если хэш совпал; иначе пересчитывает
    и сохраняет. Лог: INDEX: cache hit / INDEX: rebuilt, N chunks и время."""
    expected = _index_hash(size, overlap)
    if INDEX_FILE.is_file():
        try:
            data = json.loads(INDEX_FILE.read_text(encoding="utf-8"))
            if data.get("hash") == expected:
                print(f"INDEX: cache hit ({len(data['chunks'])} chunks, "
                      f"model={data['model']})")
                return data
        except (json.JSONDecodeError, OSError, KeyError):
            print("INDEX: файл индекса повреждён, пересчитываю")
    started = time.time()
    chunks = _all_chunks(size, overlap)
    if chunks:
        vectors = embed([c["text"] for c in chunks])   # пачкой
        for c, v in zip(chunks, vectors):
            c["vector"] = v
    data = {"hash": expected, "model": EMBED_MODEL,
            "size": size, "overlap": overlap, "chunks": chunks}
    INDEX_DIR.mkdir(exist_ok=True)
    INDEX_FILE.write_text(json.dumps(data, ensure_ascii=False),
                          encoding="utf-8")
    print(f"INDEX: rebuilt, {len(chunks)} chunks "
          f"(model={EMBED_MODEL}) за {time.time() - started:.1f}s")
    return data


# ---------- шаг 4: векторный search_docs ----------
def vector_search(query: str, limit: int = 3) -> str:
    """Поиск по смыслу: эмбеддинг запроса -> косинус со всеми фрагментами
    -> top-k. Схема для модели та же, что у keyword-поиска: query, limit 1-5."""
    if not str(query).strip():
        raise ValueError("query не может быть пустым")
    limit = max(1, min(int(limit), 5))
    index = load_or_build_index()
    if not index["chunks"]:
        return "ОШИБКА: docs/ пуста — нечего искать."
    qvec = embed([str(query)])[0]
    scored = [(cosine(qvec, c["vector"]), c) for c in index["chunks"]]
    scored.sort(key=lambda x: -x[0])
    if scored[0][0] < MIN_SCORE:
        return (f"ОШИБКА: нет похожих фрагментов (лучший косинус "
                f"{scored[0][0]:.2f} ниже порога {MIN_SCORE:.2f}).")
    return "\n\n".join(
        f"{n}. [{c['file']} § {c['title']}] cos={score:.2f}: {c['text'][:400]}"
        for n, (score, c) in enumerate(scored[:limit], 1)
    )


# ---------- бонус: гибридный поиск (reciprocal rank fusion) ----------
def _keyword_chunk_scores(query: str, chunks: list[dict]) -> list[float]:
    """Тот же lexical-скоринг, что у keyword_search_docs из context.py
    (точные слова + словоформы через _stem), но по фрагментам индекса —
    чтобы ранги keyword и vector были сопоставимы (одна гранулярность)."""
    words = [w for w in re.findall(r"\w+", str(query).lower()) if len(w) > 2]
    scores = []
    for c in chunks:
        low = c["text"].lower() + " " + c["title"].lower()
        score = 0.0
        for w in words:
            score += len(re.findall(r"\b" + re.escape(w) + r"\b", low))
            stem = _stem(w)
            if stem:
                score += 0.4 * len(
                    re.findall(r"\b" + re.escape(stem) + r"\w{0,2}\b", low))
        scores.append(score)
    return scores


RRF_K = 60              # константа RRF: гасит вклад верхушки рейтинга
FUSE_DEPTH = 10         # глубина списков, которые сливаем


def hybrid_search(query: str, limit: int = 3) -> str:
    """Гибрид: ранги keyword-скоринга и векторного поиска объединяются
    reciprocal rank fusion — rrf(d) = Σ 1/(RRF_K + rank_i(d)). Ошибка
    возвращается, только если ОБА поиска не нашли ничего (keyword дал
    нули, вектор — ниже порога): точный термин ловит keyword даже при
    низком косинусе, перефраз — вектор."""
    if not str(query).strip():
        raise ValueError("query не может быть пустым")
    limit = max(1, min(int(limit), 5))
    index = load_or_build_index()
    if not index["chunks"]:
        return "ОШИБКА: docs/ пуста — нечего искать."
    kw_scores = _keyword_chunk_scores(query, index["chunks"])
    kw_rank = sorted(range(len(kw_scores)),
                     key=lambda i: -kw_scores[i])[:FUSE_DEPTH]
    qvec = embed([str(query)])[0]
    vec_scores = [cosine(qvec, c["vector"]) for c in index["chunks"]]
    vec_rank = sorted(range(len(vec_scores)),
                      key=lambda i: -vec_scores[i])[:FUSE_DEPTH]
    if kw_scores[kw_rank[0]] == 0 and vec_scores[vec_rank[0]] < MIN_SCORE:
        return ("ОШИБКА: нет похожих фрагментов (keyword — нули, лучший "
                f"косинус {vec_scores[vec_rank[0]]:.2f} ниже порога "
                f"{MIN_SCORE:.2f}).")
    rrf = {}
    for rank, i in enumerate(kw_rank, 1):        # ранг с 1: важнее верхушки
        rrf[i] = rrf.get(i, 0.0) + 1.0 / (RRF_K + rank)
    for rank, i in enumerate(vec_rank, 1):
        rrf[i] = rrf.get(i, 0.0) + 1.0 / (RRF_K + rank)
    ordered = sorted(rrf, key=lambda i: -rrf[i])[:limit]
    return "\n\n".join(
        f"{n}. [{index['chunks'][i]['file']} § {index['chunks'][i]['title']}] "
        f"rrf={rrf[i]:.4f}: {index['chunks'][i]['text'][:400]}"
        for n, i in enumerate(ordered, 1)
    )


# ---------- проверка шага 1: 6 пар фраз ----------
if __name__ == "__main__":
    phrases = ["удалить аккаунт", "стереть профиль",          # 1-2: синонимы
               "передать команду роботу", "отправить команду роботу",
               "сбросить ошибку", "сбросить настройку",        # 3-4: слово общее
               "план уборки", "план тренировок",
               "расписание уборки", "погода в Москве",         # 5-6: не связаны
               "история сессий", "размер сетки плана"]
    vecs = embed(phrases)
    print(f"длина вектора ({EMBED_MODEL}): {len(vecs[0])}")
    for i in range(0, len(phrases), 2):
        cos = cosine(vecs[i], vecs[i + 1])
        print(f"{cos:.3f}  «{phrases[i]}» / «{phrases[i + 1]}»")
