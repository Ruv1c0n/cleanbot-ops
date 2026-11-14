"""Прогон сценария лаб. 3/4 без интерактива: фиксированный вопрос ->
агентный цикл (run_agent) с сохранением сессии. Используется для логов
LAB4.md: сценарий «найди в документации ... и вызови доменный инструмент».

Запуск: python run_scenario.py "<вопрос>" [session_id]
"""
import sys

import agent

QUESTION = sys.argv[1]
SESSION_ID = sys.argv[2] if len(sys.argv) > 2 else "lab4-scenario"

agent.SESSION_ID = SESSION_ID
agent.select_backend()
messages = agent.load_session(SESSION_ID, agent.SYSTEM_PROMPT)
messages.append({"role": "user", "content": QUESTION})
print("🤖", agent.run_agent(messages))
agent.save_session(SESSION_ID, messages)
