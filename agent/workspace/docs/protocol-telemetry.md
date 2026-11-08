# Телеметрия (WebSocket)

`GET ws://<host>:8080/telemetry` — раз в ~1 секунду сервер присылает JSON-массив
телеметрии по всем роботам сразу (батч).

Пример элемента массива:

```json
{
  "robotId": "cleanbot-01",
  "timestamp": "2026-09-05T14:32:11Z",
  "state": "CLEANING",
  "battery": 63,
  "progress": 0.42,
  "position": { "x": 12, "y": 4 },
  "error": null
}
```

Состояния (`state`): `IDLE | CLEANING | PAUSED | RETURNING | CHARGING | ERROR`.
Коды ошибок (`error`): `STUCK`, `BRUSH_JAMMED`, `TANK_FULL`, `SENSOR_FAULT`, `LOW_BATTERY`.

Симуляция: в `CLEANING` заряд -1%/тик, прогресс +2%/тик; при заряде <=15% —
принудительный `RETURNING`; при прогрессе 100% — `RETURNING`; в `CHARGING`
заряд +3%/тик до 100%.
