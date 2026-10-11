import os
from dotenv import load_dotenv
from openai import OpenAI

load_dotenv()
client = OpenAI(base_url=os.getenv("BASE_URL"), api_key=os.getenv("API_KEY"))
MODEL = os.getenv("MODEL")

SYSTEM_PROMPT = "Ты полезный ассистент. Отвечай по-русски, кратко."

# вся «память» чата: список сообщений, первым идёт системный промпт
messages = [{"role": "system", "content": SYSTEM_PROMPT}]

while True:
    user_input = input("\n> ").strip()
    if user_input in ("exit", "quit", "выход"):
        break
    if not user_input:
        continue

    messages.append({"role": "user", "content": user_input})

    response = client.chat.completions.create(model=MODEL, messages=messages)
    choice = response.choices[0]
    answer = choice.message.content

    # без этой строки модель не помнит свои ответы
    messages.append({"role": "assistant", "content": answer})

    print(answer)
    print(
        f"[finish={choice.finish_reason} in={response.usage.prompt_tokens} out={response.usage.completion_tokens}]")
