# M03 — исправление двух P2 после ревью 3277e90

## STATUS / SCOPE

**FIX_REVIEW_PENDING, 16 ноября 2026.** База `3277e90` на `codex/t10-api-integration`; target — локальный коммит, содержащий этот отчёт и [манифест](t10-m03-p2-fixes-manifest.json). Исправлены две P2 из `CODE_REVIEW.md:1973` (CHANGES_REQUESTED), без расширения M03. Независимое повторное ревью ещё не выполнено. M02 fix-review остаётся открытым; M04–M09 TODO, ручная приёмка всего T10 после M09.

Пользователь прямо попросил не собирать новые APK. Assemble/package APK/device/staging в этом проходе не запускались, версии/зависимости/Gradle-конфигурация не менялись. Существующие code23 относятся к `3277e90` и **не содержат исправлений**. Субагентов, push и внешних записей не было.

## REQUIREMENTS → IMPLEMENTATION → EVIDENCE

| Находка | Итоговое поведение | Регрессия |
|---|---|---|
| Pending registration повторно валидируется по новой анкете и не доходит до HTTP | EventViewModel валидирует только новую логическую команду. Явный повтор pending использует исходные body/operationId, пропуская validation обновлённых questions/policy. Adapter/сервер сохраняют actor/expiry/cooldown/authorization guards | Четыре теста M03RegistrationStateTest: guest/auth × добавленный обязательный/удалённый отвеченный вопрос. Первая попытка моделирует принятый сервером запрос с потерянным ответом; повтор возвращает его результат. Проверяются равенство команд/key/answers, запрет редактирования и очистка pending после успеха |
| Own-карточка вне scope добавляется в server items, оставаясь после Back | TicketsUiState хранит server list в data и отдельный selectedCard. Found/refresh карточки меняет только карточку с её event/now. List Ready сохраняется без подмешивания карточки, при любом порядке ответов; Back очищает карточку | M03TicketsStateTest: PAST-карточка при UPCOMING scope, card→list и list→card, исходные items/scope/counters до/после Back. Проверяется, что сама карточка доступна |
| Связанный порядок восстановления selection | Own observer устанавливается до list observer, в том числе на Main.immediate. Мгновенный list не сбрасывает сохранённый ticketSelection вне scope | UnconfinedTestDispatcher + немедленный пустой list + поздняя own-карточка |

## ARCHITECTURE / CLEANUP

Контракты repository/domain, server DTO, DI и persisted schema не менялись. EventViewModel хранит pending body только в памяти; saved metadata по-прежнему boolean, неизвестная операция после process death не отправляется вслепую. Refresh анкеты не переписывает pending body. Поля новой формы остаются под обычной validation.

TicketsViewModel владеет независимыми list/card projections. До own ответа выбранный билет может отображаться из списка. Selected-card refresh проверяет owner и текущий selected ID до публикации результата; общий handler обновляет status/now/QR или очищает private projection. Forbidden/NotFound удаляют недоступную карточку и прежний недоступный list item; counters не пересчитываются клиентом. Authentication очищает private state. Transient error сохраняет данные; Back/смена сегмента очищают selectedCard; stop отменяет jobs. Обновлён TicketsScreen/fullscreen, чтобы карточка не зависела от присутствия в list items.

Текущие правила отражены в [M03 architecture](../architecture/t10-registration-tickets.md), [owners](../architecture/state-ownership.md), [operations](../architecture/operations.md), [invariants](../architecture/invariants.md), [navigation](../03-navigation.md) и ADR-T10-05. Продуктовых решений/новых абстракций не добавлено.

## CHECKS

JBR 21: `C:\Program Files\Android\Android Studio1\jbr`. Все команды из корня проекта, Gradle wrapper, `--offline --console=plain --max-workers=2`, init `.gradle/m01-verified-google.init.gradle`. Новые JVM-прогоны принудительно выполнялись с `--rerun` у test task; результаты не выданы из up-to-date outputs.

1. **RED на production-коде 3277e90:** `:app:testDemoDebugUnitTest --tests '*M03RegistrationStateTest' --tests '*M03TicketsStateTest' --rerun` — exit **1**, 12 tests / **7 failures**, 0 errors/skipped. Четыре retry-регрессии получили только один submit вместо двух; два порядка list/card изменили server items; immediate list сбросил selection. Лог `.gradle/m03-p2-fix-evidence/red.log`, XML `red-results`. Предшествующая попытка компиляции выявила отсутствующий метод в новом тестовом стабе; стаб исправлен до RED, production тогда не менялся (`test-compile-attempt.log`).
2. **GREEN после исправления:** та же команда — exit **0**, **12 passed**, 0 failures/errors/skipped. `green-targeted.log`, `green-targeted-results`.
3. **Полный app JVM + lint/isolation:** `:app:testDemoDebugUnitTest --rerun :app:testRemoteDebugUnitTest --rerun :app:lintDemoDebug :app:lintRemoteDebug :app:verifyDemoIsolation` — exit **0**, 1m16s, 203 tasks (32 executed). **Demo 90 passed, remote 25 passed**, без failures/errors/skipped. Выполнена компиляция изменённых app Kotlin/Compose/Hilt обоих вариантов. Lint: demo **0 errors / 21 warnings**, remote **0 / 44**; существующие предупреждения не скрывались. Fake отсутствует в main/remote runtime. Лог `verification.log`, отдельные XML `joint-results/demo` и `joint-results/remote`, `lint-demo.txt`/`lint-remote.txt`.
4. **Финальный адресный прогон:** `:app:testDemoDebugUnitTest --tests '*M03RegistrationStateTest' --tests '*M03TicketsStateTest' --rerun :data:remote:testDebugUnitTest --tests '*RemoteTicketsTest' --rerun :data:fake:test --tests '*M03GuestRegistrationTest' --rerun` — exit **0**, 8s. **12 app + 9 remote + 1 fake passed**, без пропусков/ошибок. В тест refresh карточки добавлены проверки нового USED/status/now, закрытия QR и неизменности списка. Старые transport/replay/cooldown/actor tests также прошли. Лог `final-targeted.log`, XML `final-results`, сводка `summary.json`.

Уникальных JVM cases в совокупности — **125**: 90 demo + 25 remote app + 9 remote data + 1 fake; повторные 12 app не прибавляются. Сохранённые 190/3 и staging из реализации `3277e90` — прежние результаты, в эти числа не входят. До фильтрованных прогонов прежние XML скопированы отдельно в `before-results`/`before-port-results`.

Структурная проверка `python scripts/verify-architecture-docs.py` — exit **0**: 18 текущих страниц, 361 local links, 9 Mermaid blocks, 18 mobile IDs, S01–S14/test references. `git diff --check` — exit **0**. Лог `.gradle/m03-p2-fix-evidence/documentation-checks.log`; rendering Mermaid и семантика Kotlin этим скриптом не проверяются.

## PRESERVATION

Изначально dirty только пользовательский `CODE_REVIEW.md` и untracked `gradle/gradle-daemon-jvm.properties`. Оба сверены побайтно с baseline в `.gradle/m03-p2-fix-evidence/baseline`, не включаются в коммит:

- CODE_REVIEW.md SHA-256 `d6cc4dd2d40413aa5b093d4a80534cbdf852493315fa1c676f74a988b538e171`.
- gradle-daemon-jvm.properties SHA-256 `231649d2f8e8dc24310f18c921e3ae33fd2715b7f48a7ddcaf27a85003e0729a`.

Evidence локальные ignored, не новый артефакт APK. Прежний отчёт реализации/его манифест сохранены как история.

## NOT_RUN / RISKS / HANDOFF

- Новые APK **не собирались по указанию пользователя**; packaging/install/device/UI/SAF/rotation/OS process kill не проверялись заново. Имеющиеся APK не подходят для ручной проверки этих исправлений.
- Staging, реальная почта/guest setup и реальный потерянный HTTP-ответ заново не проверялись; retry-сочетания доказаны JVM-состоянием, transport — существующими MockWebServer tests. Server retention/окончательная авторизация не менялись.
- Полные domain/fake/platform/data remote suites заново не запускались; выполнены весь app и адресные M03 data tests. Предупреждения lint 21/44 остаются.
- Повторное независимое ревью M03 обязательно; авторская проверка не даёт TECH_ACCEPTED. M02 fix-review и человеческие H-пункты не закрыты. M04 пока не запускать.

Следующая задача — [промпт повторного ревью M03](../prompts/t10-m03-fix-review.md) на зафиксированном commit/manifest, меняя только CODE_REVIEW.md. Ручные сценарии в конце T10: повтор неопределённой guest/auth заявки после изменения анкеты возвращает исходный результат; открытие PAST own ticket из UPCOMING не меняет список после Back; восстановленная карточка не исчезает после list response. Для человека позже потребуется новая сборка с исправлениями, отдельным разрешённым шагом.
