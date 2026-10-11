# Протокол обмена simulator ↔ app

База: `http://<host>:8080` (REST), `ws://<host>:8080` (WebSocket).

## WebSocket: телеметрия

`GET /telemetry`

Раз в ~1 секунду сервер присылает JSON-**массив** телеметрии по всем роботам сразу
(расширение относительно README: батч вместо одного объекта — меньше сообщений,
проще собрать список на клиенте одним `decode`):

```json
[
  {
    "robotId": "cleanbot-01",
    "timestamp": "2026-09-05T14:32:11Z",
    "state": "CLEANING",
    "battery": 63,
    "progress": 0.42,
    "position": { "x": 12, "y": 4 },
    "error": null
  },
  { "robotId": "cleanbot-02", "...": "..." }
]
```

`state` ∈ `IDLE | CLEANING | PAUSED | RETURNING | CHARGING | ERROR`
`error` ∈ `null | STUCK | BRUSH_JAMMED | TANK_FULL | SENSOR_FAULT | LOW_BATTERY`

## REST

| Метод | Путь | Описание |
|---|---|---|
| GET | `/robots` | список роботов (id, name, state, battery, error) |
| GET | `/robots/{id}` | сводка по одному роботу |
| POST | `/robots/{id}/commands` | команда роботу |
| GET | `/robots/{id}/schedules` | текущее расписание |
| PUT | `/robots/{id}/schedules` | заменить расписание целиком |
| GET | `/robots/{id}/sessions?from&to` | история сессий (ISO-8601, оба параметра опциональны) |
| GET | `/robots/{id}/logs` | журнал событий, новые сверху |
| GET | `/robots/{id}/settings` | настройки робота |
| PUT | `/robots/{id}/settings` | обновить настройки |

### Команды

`POST /robots/{id}/commands`
```json
{ "command": "PAUSE", "requestId": "b1c2d3e4" }
```
`command` ∈ `START | PAUSE | RESUME | ABORT | RETURN_HOME | RESET`

> `RESET` — дополнение к README: сбрасывает робота из `ERROR` в `IDLE`.
> Переход `ERROR --reset--> IDLE` уже был в таблице FSM в README, но не был
> явно перечислен как REST-команда — без него инженерное меню не может
> восстановить робота после аварии.

Ответ:
```json
{ "accepted": true, "requestId": "b1c2d3e4" }
```
или
```json
{ "accepted": false, "requestId": "b1c2d3e4", "reason": "ILLEGAL_STATE" }
```
`reason` ∈ `ILLEGAL_STATE | UNKNOWN_COMMAND`

### Конечный автомат (таблица переходов из README, без изменений)

| Состояние | Событие | Следующее состояние | Источник события |
|---|---|---|---|
| `IDLE` | `start` | `CLEANING` | REST-команда |
| `IDLE` | `return_home` | `RETURNING` | REST-команда |
| `CLEANING` | `pause` | `PAUSED` | REST-команда |
| `PAUSED` | `resume` | `CLEANING` | REST-команда |
| `CLEANING`/`PAUSED` | `abort`/`return_home` | `RETURNING` | REST-команда |
| `RETURNING` | `docked` | `CHARGING` | внутреннее (позиция == станция) |
| `CHARGING` | `battery_full` | `IDLE` | внутреннее (заряд == 100%) |
| любое кроме `ERROR` | `fault` | `ERROR` | внутреннее (случайная авария) |
| `ERROR` | `reset` | `IDLE` | REST-команда |

Недопустимая в текущем состоянии команда отклоняется с `reason: "ILLEGAL_STATE"`.

## Симуляция (что происходит "под капотом")

- Тик каждого робота — раз в секунду.
- В `CLEANING`: заряд -1%/тик, прогресс +2%/тик, случайное блуждание по сетке 24×12, авария завершает сессию как aborted=true.
- При заряде ≤15% — принудительный `RETURNING` (сессия помечается `aborted=true`).
- При прогрессе 100% — `RETURNING` (сессия `aborted=false`).
- Шанс аварии ~1%/тик в `CLEANING` → случайный код ошибки (кроме `LOW_BATTERY`).
- В `RETURNING`: пошаговое движение к точке стыковки `(0,0)`.
- В `CHARGING`: заряд +3%/тик до 100%.
- При старте сервера для каждого робота генерируется ~40 сессий за последний месяц
  (для экрана статистики) — при перезапуске история сбрасывается и генерируется заново.
