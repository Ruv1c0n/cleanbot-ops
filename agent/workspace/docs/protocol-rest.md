# REST API симулятора

База: `http://<host>:8080`.

| Метод | Путь | Описание |
|---|---|---|
| GET | `/robots` | список роботов (id, name, state, battery, error) |
| GET | `/robots/{id}` | сводка по одному роботу |
| POST | `/robots/{id}/commands` | команда роботу |
| GET | `/robots/{id}/schedules` | текущее расписание |
| PUT | `/robots/{id}/schedules` | заменить расписание целиком |
| GET | `/robots/{id}/sessions?from&to` | история сессий (ISO-8601) |
| GET | `/robots/{id}/logs` | журнал событий, новые сверху |
| GET/PUT | `/robots/{id}/settings` | настройки робота |

## Команды

`POST /robots/{id}/commands` с телом `{"command": "PAUSE", "requestId": "..."}`.
Команды: `START | PAUSE | RESUME | ABORT | RETURN_HOME | RESET`.
`RESET` выводит робота из состояния `ERROR` обратно в `IDLE`.

Ответ: `{"accepted": true, ...}` либо `{"accepted": false, "reason": "ILLEGAL_STATE"}`
(команда недопустима в текущем состоянии) или `"UNKNOWN_COMMAND"`.
