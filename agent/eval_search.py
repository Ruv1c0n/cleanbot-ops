"""Сравнение keyword vs vector поиска (лаб. 4, шаг 5).

10 вопросов своими словами (не копируя фразы из документов); минимум 4 —
синонимы/перефраз. Для каждого вопроса указан файл, где лежит ответ.
hit_at_k — доля вопросов, для которых нужный файл есть в top-k.

Запуск: python eval_search.py
"""
import re

from context import search_docs as keyword_search_docs
from rag import vector_search, load_or_build_index

# (вопрос, файл с ответом, перефраз?) — формулировки не совпадают с текстом
# документов дословно; Questions 1-5 — перефраз/синонимы, 6-10 — точные
# термины, коды и имена
QUESTIONS = [
    ("каким словом помечают срочные задачи?", "faq.md", True),
    ("чем сухая уборка отличается от влажной и как это выбрать?", "schedules.md", True),
    ("машинка застряла и показывает сбой — как вернуть её в строй?", "troubleshooting.md", True),
    ("по какому адресу забрать историю уборок за неделю?", "protocol-rest.md", True),
    ("сколько клеток у поля и что будет, если задать клетку за краем?", "plan.md", True),
    ("на чём написан клиент и приложение — что за Ktor?", "features.md", False),
    ("какие коды ошибок приходят в телеметрии?", "protocol-telemetry.md", False),
    ("что значит ILLEGAL_STATE?", "commands.md", False),
    ("что случится с планами при рестарте симулятора?", "faq.md", True),
    ("где переименовать машину и поменять ей адрес сервера?", "settings.md", True),
]


def _files(result: str) -> list[str]:
    """Имена файлов из результата поиска, в порядке выдачи. Форматы:
    keyword — «1. name (score=…)», vector — «1. [file § title] cos=…»."""
    kw = re.findall(r"^\d+\. (\S+) \(", result, re.M)
    vec = re.findall(r"^\d+\. \[([^\s\]]+) §", result, re.M)
    return kw or vec


def hit_at_k(search, k: int) -> float:
    """Доля вопросов, у которых нужный файл есть в top-k результатах."""
    hits = 0
    for question, expected, _ in QUESTIONS:
        files = _files(search(question, limit=5))[:k]
        if expected in files:
            hits += 1
    return hits / len(QUESTIONS)


if __name__ == "__main__":
    load_or_build_index()                    # чтобы лог был чистым
    kw_hits, vec_hits = [], []
    for n, (question, expected, paraphrase) in enumerate(QUESTIONS, 1):
        kw_files = _files(keyword_search_docs(question, limit=3))
        vec_files = _files(vector_search(question, limit=3))
        kw_hits.append(expected in kw_files[:3])
        vec_hits.append(expected in vec_files[:3])
        tag = "перефраз" if paraphrase else "точный"
        print(f"| {n} | {question} | {expected} | {tag} "
              f"| {'+' if kw_hits[-1] else '-'} "
              f"| {'+' if vec_hits[-1] else '-'} |")
    print()
    print(f"hit@1: keyword={hit_at_k(keyword_search_docs, 1):.0%} "
          f"vector={hit_at_k(vector_search, 1):.0%}")
    print(f"hit@3: keyword={hit_at_k(keyword_search_docs, 3):.0%} "
          f"vector={hit_at_k(vector_search, 3):.0%}")
