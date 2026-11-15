# Бэклог ревью UX-фичи (онбординг/настройки/офлайн-стейты), 2026-11-15

Ревью 5 ревьюеров (correctness, product-UX, testing-strategy, performance-reliability, simplicity-scope) по диффу `feat/onboarding-settings` (e296db9, c04d7a4, 12c4a1b). P0/P1 закрыты в `12c4a1b`; ниже — согласованные P2/P3, осознанно отложенные.

## P2

1. **Вечный спиннер при REST-фейле при CONNECTED** (`RobotDetailViewModel.loadPlan`, `PlanEditorViewModel.load`): если WS CONNECTED, но `GET /plan` упал (500/таймаут), `plan/sketch` остаются null без флага — Map/Editor показывают бесконечный спиннер (офлайн-стейт гейтится на DISCONNECTED). Фикс: флаг `planLoadFailed`/`loadFailed` → `OfflineState(onRetry)`.
2. **Throttle/дебаунс `reconnect()`** (`RobotRepository.reconnect`, кнопка «Переподключиться»): каждое нажатие роняет живой WS-хендшейк; при нетерпеливых тапах соединение может не устанавливаться. Фикс: скип, если уже CONNECTING, или timestamp-гвард ~1–2с.
3. **Backoff реконнекта** (`RobotRepository.startTelemetryLoop`): фиксированный `delay(2000)` — попытка коннекта каждые ~2-5с весь сеанс при выключенном сервере (батарея/сеть). Фикс: экспоненциальный бэкофф с капом ~30с, сброс на успехе/ручном reconnect.
4. **«Настройки» из офлайн-стейтов Dashboard/Map/Editor** (`DashboardTab/MapTab/PlanEditorScreen` → `OfflineState`): кнопка есть только в парке; с робота/редактора до настроек добраться только назад+шестерёнка. Фикс: прокинуть `onOpenSettings` через `RobotDetailScreen`/табы.
5. **BackHandler онбординга** (`OnboardingScreen`): системный Back на страницах 2–3 закрывает приложение (start destination), флаг done не ставится. Фикс: `BackHandler` — на страницу назад.
6. **Deep-link уведомления поверх онбординга** (`CleanBotNavHost.LaunchedEffect(initialRobotId)`): тап по пушу на онбординге открывает робота поверх пейджера, минуя онбординг-контракт. Фикс: гейт по `onboardingDone` / отложить навигацию до `onDone`.
7. **IPv6 и регистр схемы в `normalizeHostPort`** (`SettingsViewModel`): `::1` парсится как хост `":"` порт 1; `WS://host` не стрипается (case-sensitive `removePrefix`) — сохраняется «успех», коннекта нет. Фикс: отвергать хосты с `:` без скобок; стрип схемы regex-ом `^(?i)(wss?|https?)://`.

## P3

8. **ConnectionDot без semantics** (`ui/components/ConnectionDot.kt`): цветовая точка 10dp без contentDescription — TalkBack молчит; CONNECTED/CONNECTING различаются только оттенком. Фикс: `semantics { contentDescription }`.
9. **Dots пейджера: контраст и semantics** (`OnboardingScreen.PagerDots`): неактивные точки alpha 0.35 (<3:1), нет «Страница X из 3». Фикс: alpha ~0.5+, contentDescription на ряд.
10. **Двойной тап «Далее»** (`OnboardingScreen.onNext`): гонка `currentPage` против анимации — недетерминированный прыжок. Фикс: `if (!pagerState.isScrollInProgress)` или `targetPage + 1`.
11. **ScheduleTab: противоречивый копирайт** — при офлайне показываются и баннер «Офлайн — расписание не загружено», и «Записей нет». Фикс: взаимоисключающие ветки. Плюс: автозагрузка `loadSchedule()` на CONNECTED (сейчас баннер исчезает, список не обновляется до пере-входа на таб).
12. **EngineeringTab офлайн молчит**: settings==null, поле пустое, Save задизейблен без объяснения. Фикс: офлайн-баннер/кликабельный хинт → настройки.
13. **Settings: задизейбленная кнопка без подсказки** («Сохранить и переподключиться» при неизменном адресе и CONNECTED). Фикс: supporting text «Адрес актуален — используйте «Переподключиться»».
14. **`retry()` в `RobotDetailViewModel` — 7 вызовов вместо 2**: грузит schedule/sessions/stats/logs/settings упреждающе. Фикс: только `refreshFleet()` + `loadPlan()`.
15. **Дубли офлайн-баннера** (`ScheduleTab.ScheduleOfflineBanner` vs `SessionsTab.OfflineBanner`): вынести `OfflineBanner(text)` в `ui/components`.
16. **Спекулятивные префиксы `wss://`/`https://`** в `normalizeHostPort` — клиент не умеет TLS; стрипать только `ws://`/`http://`.
17. **Спекулятивные параметры `OfflineState(title, hint)` + дублирующий hint в FleetListScreen** («Проверь» vs «Проверьте») — унифицировать копирайт, убрать переопределения.
18. **Тестовые дыры**: reconnect-tick без теста (нужен fake TelemetrySocket); автозагрузка-on-CONNECTED не покрыта (инжект `connectionState` в VM); guard `loadInFlight` (двойной `load()` → 1 fetch); границы порта 65535/65536, `ws://host` без порта, `::1`; save-ветка «не изменился при CONNECTED».

## Принято как есть (обоснование)

- Флаг онбординга без unit-теста — SharedPreferences-обвязка, без Robolectric недостижима (пет-проект).
- Лёгкое остаточное мигание статуса в настройках в фазах коннекта — уменьшено липким CONNECTING (12c4a1b), полная гистерезис-модель — по потребности.
