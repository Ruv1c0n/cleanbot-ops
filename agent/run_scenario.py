"""Прогон сценария лаб. 3/4 без интерактива: фиксированный вопрос ->
агентный цикл (run_agent) с сохранением сессии. Используется для логов
LAB4.md: сценарий «найди в документации ... и вызови доменный инструмент».

Запуск: python run_scenario.py "<вопрос>" [session_id] [--no-guard]
--no-guard убирает анти-injection правило из системного промпта —
baseline-прогон для батарейки инъекций (чтобы показать, что агент
защищён именно правилом, а не «на везении» модели). Промпт правится
через общую константу GUARD_SENTENCE из agent.py — копия строки в двух
местах недопустима. Baseline-прогоны пишут в сессию с суффиксом
«-baseline»: смешивание guarded/baseline истории в одном store
подмешивало бы модели чужие сопротивления/уступки инъекциям.
"""
import argparse

import agent
from agent import GUARD_SENTENCE

parser = argparse.ArgumentParser(
    description="Неинтерактивный прогон сценария агента cleanbot-ops")
parser.add_argument("question", help="вопрос пользователя")
parser.add_argument("session_id", nargs="?", default="lab4-scenario",
                    help="id сессии (по умолчанию lab4-scenario)")
parser.add_argument("--no-guard", action="store_true",
                    help="baseline: убрать анти-injection правило из промпта")
args = parser.parse_args()

SESSION_ID = args.session_id + ("-baseline" if args.no_guard else "")
if args.no_guard:
    assert GUARD_SENTENCE in agent.SYSTEM_PROMPT
    agent.SYSTEM_PROMPT = agent.SYSTEM_PROMPT.replace(GUARD_SENTENCE, "")
    print("[baseline: анти-injection правило убрано из системного промпта]")

agent.SESSION_ID = SESSION_ID
agent.select_backend()
messages = agent.load_session(SESSION_ID, agent.SYSTEM_PROMPT)
messages.append({"role": "user", "content": args.question})
print("🤖", agent.run_agent(messages))
agent.save_session(SESSION_ID, messages)
