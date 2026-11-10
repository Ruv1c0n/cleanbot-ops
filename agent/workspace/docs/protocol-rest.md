# REST API симулятора

База: `http://<host>:8080`. Телеметрия идёт по WebSocket (`GET /telemetry`, батч по всем роботам раз в секунду) — см. `protocol-telemetry.md`.

| Метод | Путь | Описание |
|---|---|---|
| GET | `/robots` | список роботов (id, name, state, battery, error) |
| GET | `/robots/{id}` | сводка по одному роботу |
| POST | `/robots/{id}/commands` | команда роботу |
| GET | `/robots/{id}/schedules` | текущее расписание |
| PUT | `/robots/{id}/schedules` | заменить расписание целиком |
| GET | `/robots/{id}/plan` | текущий план уборки |
| PUT | `/robots/{id}/plan` | заменить план целиком (ошибка валидации → 400 с текстом) |
| GET | `/robots/{id}/sessions?from&to` | история сессий (ISO-8601, оба параметра опциональны) |
| GET | `/robots/{id}/logs` | журнал событий, новые сверху |
| GET | `/robots/{id}/settings` | настройки робота |
| PUT | `/robots/{id}/settings` | обновить настройки (robotId в теле ≠ id в пути → 400) |

> Фильтр `from`/`to` у сессий — по времени **старта** (`startedAt`), границы включительные,
> оба параметра опциональны. При невалидной дате сервер вернёт 500, при неизвестном роботе — 404.

## Команды

`POST /robots/{id}/commands` с телом `{"command": "PAUSE", "requestId": "..."}`.
Команды: `START | PAUSE | RESUME | ABORT | RETURN_HOME | RESET`.
`RESET` выводит робота из состояния `ERROR` обратно в `IDLE`.

Ответ: `{"accepted": true, ...}` либо `{"accepted": false, "reason": "ILLEGAL_STATE"}`
(команда недопустима в текущем состоянии) или `"UNKNOWN_COMMAND"`.

## План уборки

`GET /robots/{id}/plan` возвращает `CleaningPlan`; `PUT /robots/{id}/plan` заменяет план целиком.

```json
{
  "gridW": 20,
  "gridH": 14,
  "zones": [
    { "id": 1, "name": "Гостиная", "cells": [{ "x": 1, "y": 1 }, { "x": 2, "y": 1 }] },
    { "id": 2, "name": "Спальня", "cells": [{ "x": 9, "y": 4 }] },
    { "id": 3, "name": "Кухня", "cells": [{ "x": 2, "y": 9 }] }
  ]
}
```

- Сетка **фиксированная 20×14**; координаты телеметрии `position {x, y}` — целые, маппятся 1:1 на клетку карты.
- Клетки 0-based: `0 <= x < gridW`, `0 <= y < gridH`.
- Валидация на PUT; ошибки → **400** с plain-text причиной, неизвестный робот → **404**:
  - `gridW`/`gridH` должны быть ровно `20`/`14`;
  - клетки в границах сетки;
  - клетки без дублей (в том числе между зонами);
  - зона ≥ 1 клетки.
- Успешный PUT → 200, запись в журнал робота; план применяется при **следующем START**, текущая уборка не прерывается.
- Дефолтный план у каждого робота — 3 зоны, генерируется при старте сервера.
- В симуляции робот идёт «змейкой» по клеткам зон (зона за зоной), `progress` = доля пройденных клеток маршрута. START с пустым планом отклоняется с `reason: "План не задан"` — «блуждания без плана» нет.

## Расписание

`GET /robots/{id}/schedules` возвращает список записей; `PUT /robots/{id}/schedules`
заменяет расписание целиком. Запись: `{"id": "...", "robotId": "cleanbot-01", "dayOfWeek": 1, "time": "06:00", "mode": "dry_sweep"}`.

- Валидация на PUT; ошибки → **400** с plain-text причиной, неизвестный робот → **404**:
  - `dayOfWeek` ∈ `1..7` (ISO-8601, понедельник = 1);
  - `time` — строка `"HH:mm"` (часы `00–23`, минуты `00–59`);
  - `mode` ∈ `dry_sweep | wet_mopping`;
  - `robotId` каждой записи совпадает с `id` в пути.
- Расписания реально запускают уборку (см. FAQ), поэтому все поля проверяются.

## Конечный автомат

`IDLE --start--> CLEANING`, `CLEANING --pause--> PAUSED`, `PAUSED --resume--> CLEANING`,
`CLEANING/PAUSED --abort|return_home--> RETURNING`, `RETURNING --docked--> CHARGING`,
`CHARGING --battery_full--> IDLE`, любое кроме `ERROR` --fault--> `ERROR`, `ERROR --reset--> IDLE`.

Недопустимая в текущем состоянии команда отклоняется с `reason: "ILLEGAL_STATE"`.
