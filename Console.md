# Работа с cleanbot-ops из консоли (пока нет GUI)

Инструкция для Windows 11 и PowerShell. Корень репозитория: `D:\source\uni\clean-bot-ops\cleanbot-ops`.

## 1. Что нужно запустить

Для работы агента нужны три процесса, каждый в своём окне PowerShell:

| # | Процесс | Зачем |
|---|---|---|
| 1 | **Ollama** | локальная модель (`qwen3:4b-instruct`), «мозг» агента |
| 2 | **Симулятор** | цифровой двойник роботов, REST на `localhost:8080` |
| 3 | **Агент** | принимает запрос на русском и вызывает инструменты симулятора |

Порядок: Ollama → симулятор → агент.

## 2. Один раз

1. JDK 17: `winget install EclipseAdoptium.Temurin.17.JDK`
2. Постоянный `JAVA_HOME` (подставьте своё имя папки), затем откройте **новое** окно PowerShell:
   ```powershell
   [Environment]::SetEnvironmentVariable("JAVA_HOME", "C:\Program Files\Eclipse Adoptium\jdk-17.0.x-hotspot", "User")
   ```
3. Модель: `ollama pull qwen3:4b-instruct`
4. Файл `agent\.env`:
   ```
   OLLAMA_URL=http://localhost:11434
   MODEL=qwen3:4b-instruct
   SIMULATOR_URL=http://localhost:8080
   ```
5. В репозитории должны быть `gradlew.bat` и папка `gradle\`.

## 3. Запуск

**Окно 1. Ollama** (если не запущена автоматически из трея):
```powershell
ollama serve
```

**Окно 2. Симулятор** (окно оставить открытым, процент и `EXECUTING` у Gradle нормальны, это работающий сервер):
```powershell
cd D:\source\uni\clean-bot-ops\cleanbot-ops
.\gradlew.bat :simulator:run
```
Готов, когда в логе появилась строка `Responding at http://127.0.0.1:8080`.

**Окно 3. Агент** (UTF-8 нужен для русского текста, настройки выполнять в каждом новом окне):
```powershell
cd D:\source\uni\clean-bot-ops\cleanbot-ops
chcp 65001
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding = [System.Text.Encoding]::UTF8
.\gradlew.bat :agent:run --console=plain -q
```
На приглашении `>` введите запрос и нажмите Enter. **Один запуск обрабатывает один запрос**, для следующего запустите агента заново.

Вывод агента:
- `🔧 [N] имя_инструмента {аргументы} -> ответ` — вызов инструмента и ответ симулятора
- `🤖 ...` — итоговый ответ модели
- `tokens: in=… out=…` — расход токенов за запрос

## 4. Примеры запросов

| Запрос | Что произойдёт |
|---|---|
| `Что с парком?` | `list_robots`, краткая сводка |
| `Запусти уборку у cleanbot-01` | `list_robots`, затем `send_command START` |
| `Поставь на паузу всех, кто сейчас убирает` | выбор роботов в `CLEANING`, `PAUSE` для каждого |
| `Продолжи уборку у cleanbot-02` | если робот не на паузе, симулятор ответит `ILLEGAL_STATE` |
| `Отправь cleanbot-01 домой` | `send_command RETURN_HOME` |

## 5. Какие команды допустимы

Симулятор проверяет команды по конечному автомату (недопустимая команда возвращает `ILLEGAL_STATE`):

| Команда | Допустима из состояния |
|---|---|
| `START` | `IDLE` |
| `PAUSE` | `CLEANING` |
| `RESUME` | `PAUSED` |
| `ABORT`, `RETURN_HOME` | `CLEANING`, `PAUSED` |

После `ABORT` / `RETURN_HOME` робот едет на станцию (`RETURNING`), затем заряжается (`CHARGING`) и возвращается в `IDLE`.

## 6. Управление симулятором напрямую (без агента)

Состояние всех роботов:
```powershell
curl.exe http://localhost:8080/robots
```

Отправить команду роботу:
```powershell
Invoke-RestMethod -Uri http://localhost:8080/robots/cleanbot-01/commands -Method Post `
  -ContentType 'application/json' `
  -Body '{"command":"START","requestId":"manual-1"}'
```
Ответ: `accepted: True` или `accepted: False` с `reason`. Остальные маршруты (расписания, сессии, журнал) описаны в `docs/protocol.md`.

Проверка Ollama:
```powershell
curl.exe http://localhost:11434/api/tags
ollama list
```

## 7. Остановка и сброс

- Остановить симулятор или Ollama: `Ctrl+C` в их окне.
- **Не запускайте `.\gradlew.bat --stop` при работающем симуляторе**: он остановит и сервер.
- Симулятор хранит данные в памяти: перезапуск возвращает роботов в `IDLE` с зарядом 100% и пересоздаёт историю.

## 8. Если что-то не работает

| Симптом | Причина и решение |
|---|---|
| `Connection refused` на 8080 | Не запущен симулятор (окно 2) |
| `Connection refused` на 11434 | Не запущена Ollama: `ollama serve` |
| `This build uses a Java 8 JVM` | В окне не задан JDK 17: проверьте `java -version`, задайте `JAVA_HOME` и откройте новое окно |
| `ReadAfterEOFException` | В `agent\build.gradle.kts` нет `standardInput = System.in` в задаче `run` |
| Вместо кириллицы мусор, модель «не понимает вопрос» | Не выполнены `chcp 65001` и две строки `[Console]`, нужен и `-Dfile.encoding=UTF-8` в `jvmArgs` |
| `model not found` | Не выполнен `ollama pull`, либо `MODEL` в `.env` не совпадает с `ollama list` |
| Не находит `.env` | Запускайте из корня репозитория, файл должен лежать в `agent\` (или задайте `workingDir = projectDir` в `agent\build.gradle.kts`) |
| `.\gradlew.bat` не распознан | Вы не в корне репозитория |
| Модель выдумала причину отказа | Ориентируйтесь на строки `🔧`: там настоящий ответ симулятора |

## 9. Перед коммитом

```powershell
git status
```
- `agent\.env` в коммит попадать не должен (он в `.gitignore`).
- Не коммитьте скачанные архивы (`gradle.zip`).
- Закоммитьте `gradlew`, `gradlew.bat`, `gradle\`, `agent\`, `LAB2.md`, `CONSOLE.md`.