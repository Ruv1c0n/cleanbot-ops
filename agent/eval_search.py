"""Сравнение keyword vs vector поиска (лаб. 4, шаг 5).

10 вопросов своими словами (не копируя фразы из документов); минимум 4 —
синонимы/перефраз. Для каждого вопроса указан файл, где лежит ответ.
hit@k — доля вопросов, для которых нужный файл есть в top-k. Каждый
поиск вызывается ровно один раз на вопрос (limit=5), hit@1/hit@3 и
таблица top-3 считаются с одного прохода — раньше каждый поиск гонялся
дважды (основной цикл + re-run в hit_at_k).

Запуск: python eval_search.py
"""
import re

from context import search_docs as keyword_search_docs
from rag import vector_search, hybrid_search, load_or_build_index

# (вопрос, файл с ответом, перефраз?) — формулировки не совпадают с текстом
# документов дословно; 1-5 и 9-10 — перефраз/синонимы, 6-8 — точные
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

SEARCHES = ("keyword", "vector", "hybrid")


def _files(result: str) -> list[str]:
    """Имена файлов из результата поиска, в порядке выдачи. Форматы:
    keyword — «1. name (score=…)», vector/hybrid — «1. [file § …] …»."""
    kw = re.findall(r"^\d+\. (\S+) \(", result, re.M)
    vec = re.findall(r"^\d+\. \[([^\s\]]+) §", result, re.M)
    files = kw or vec
    # без assert смена формата выдачи превращалась бы в тихий hit@k = 0%
    assert files, f"не распарсился вывод поиска: {result[:120]!r}"
    return files


if __name__ == "__main__":
    load_or_build_index()                    # чтобы лог был чистым
    ranked_per_question = []
    for n, (question, expected, paraphrase) in enumerate(QUESTIONS, 1):
        ranked = {
            "keyword": _files(keyword_search_docs(question, limit=5)),
            "vector": _files(vector_search(question, limit=5)),
            "hybrid": _files(hybrid_search(question, limit=5)),
        }
        ranked_per_question.append((expected, ranked))
        tag = "перефраз" if paraphrase else "точный"
        marks = " ".join(
            ("+" if expected in ranked[name][:3] else "-") for name in SEARCHES)
        print(f"| {n} | {question} | {expected} | {tag} | {marks} |")
    print()
    for k in (1, 3):
        parts = [
            f"{name}={sum(expected in ranked[name][:k]
                          for expected, ranked in ranked_per_question)
                     / len(ranked_per_question):.0%}"
            for name in SEARCHES
        ]
        print(f"hit@{k}: " + " ".join(parts))
