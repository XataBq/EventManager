# T09a — адресное обновление жестом

**READY_FOR_HUMAN, 7 ноября 2026.** Реализовано поверх `c75ce56` / code 19. Предыдущая ручная приёмка [6 ноября](validation/2026-11-06-manual-acceptance.md) сохраняется. Новый UX ещё не принят. Backend/Q10/API/schema не входят в задачу.

## Карта до реализации

Сверено с исходниками до изменений; общий store и observer-публикации сохраняются.

| Поверхность | Entry / VM | Ресурс | Read-команда | Indicator после изменения |
|---|---|---|---|---|
| Текущие/история билетов и выбранная карточка | Root Tickets / TicketsViewModel | Текущий private scope, свой selectedTicketId | TicketRepository.refreshMyTickets | TicketsUiState.refreshing |
| Организую/Волонтёр/Черновики | Root Events / MyEventsViewModel | Текущий private scope, сохранённый сегмент | EventRepository.refreshMyEvents, одна общая коллекция | MyEventsUiState.refreshing |
| Публичная страница E | EventKey(flowId, eventId) / EventViewModel | eventId этой entry | EventRepository.refreshPublicEvent(eventId) | EventUiState.refreshing |
| Dashboard E | DashboardKey / DashboardViewModel | eventId + query + revision | AttendanceRepository.refreshDashboard + attendees | DashboardUiState.refreshing |
| Волонтёры E | VolunteersKey / VolunteersViewModel | eventId этой entry | Новый минимальный VolunteerRepository.refreshVolunteers(eventId) | VolunteersUiState.refreshing |
| Профиль | Root Profile / ProfileViewModel | Текущий аккаунт/private scope | Новый минимальный ProfileRepository.refreshProfile | ProfileUiState.refreshing |

В исходном коде обычные refresh actions найдены в Tickets/MyEvents/Event/Dashboard; у Profile и Volunteers retry перезапускал весь owner. Tickets/MyEvents/Event использовали STARTED без проверки видимой entry. AuthColumn уже имеет verticalScroll, Dashboard — LazyColumn: дополнительная вертикальная прокрутка вокруг них не нужна.

Общий визуальный компонент использует pinned Material3 1.4.0 `Modifier.pullToRefresh` / Indicator, тот же nested-scroll API, что [официальный PullToRefreshBox](https://developer.android.com/develop/ui/compose/components/pull-to-refresh). Modifier выбран для явного enabled (скрытая entry/overlay), без смены BOM 2026.09.00/стека. Gesture state только remember, не SavedStateHandle. Сигнатуры сверены с установленным material3-android-1.4.0-sources.jar, официальная статья получена через Android CLI docs search/fetch; реальная компиляция подтверждает совместимость этого набора. Latest-version lookup/обновление стека не выполнялись.

## Результат и APK

[APK **0.9.2-t09a-demo, code 20**](../artifacts/apk/eventpass-0.9.2-t09a-demo-code20.apk), **44871450 байт**, SHA-256 `a5a89fabffd084955d12b8658ae755ef30d4e3c3b3aa19efe47ebf7485f30d02`.

ApplicationId `ru.eventpass.android.demo`, minSdk 28 / target 37, debug APK v2 signature, aapt/output metadata проверены. JBR 21 / wrapper Gradle 9.5 / AGP 9.3.3 / Kotlin 2.4.21 прежние, build offline. Общий schema 5/store/credentials/старые APK и sample documents не менялись. Seed `t02-v1`, аккаунты `alex@example.com` и `host@example.com`, пароль **DemoPass123!**. Event Clock фиксирован 27 октября; pull не обязан изменять timestamp и не использует Clock для порядка ответов.

Все четыре обычные продуктовые кнопки refresh удалены, включая tag `dashboard-refresh`; тест больше не нажимает скрытую замену. Общий компонент подключён к шести Screen (Tickets list/card — один владелец). У Profile/Volunteers появились безопасные read-порты, не перезапуск start/reset. Read errors оставляют разрешённые данные, профиль сохраняет dirty fields и показывает конфликт при смене baseline, Event при новой formVersion явно очищает старые answers/consent со StaleForm. Retry чтения/команды, more/next, resend и export остаются адресными. Debug «Обновить письма» относится только к техническому src/demo outbox и не является продуктовой refresh-поверхностью.

Точные правила/владельцы/guard — [текущая архитектура T09a](architecture/t09a-refresh.md). DocumentsViewModel/DocumentActions и storage codec не изменялись; интеграция проверяет сохранность прежнего SAF-контракта.

## Технические проверки

- **187 JVM tests, 0 failures/errors/skipped:** domain 20, fake 86, app 73, platform 8. Новые 6 RefreshOwnersTest, 2 RefreshReadContractsTest и 1 DashboardViewModelTest добавлены к прежним 178. Проверены конкретные initiating ports/E1/E2, hidden owner, duplicate suppression, old finally/rebind/new actor, dirty selection/segment/form/profile, StaleForm и observer priority. Все прежние Dashboard follow-up/cancellation, auth/migration/PII/документные регрессии выполнены.
- `assembleDemoDebug`, `assembleDemoDebugAndroidTest`, Hilt/DI, `lintDemoDebug`, `verifyDemoIsolation` успешны. Финальный XML lint: **0 issues**, offline/incremental; это не заявление о новых версиях библиотек. APK/aapt/signature/output metadata согласованы.
- **API 33 / Pixel 7 AVD emulator-5584, three-button, fontScale 1.0: OK (26 tests), 173.114s.** PullRefreshComponentTest 2 + RefreshRoutesTest 6 + DashboardIntegrationTest 3 + ProductBackNavigationTest 4 + SafRecreationTest 2 + ProfileFilesIntegrationTest 3 + RegistrationTicketsTest 5 + WindowInsetsRegressionTest 1.
- **Системная жестовая навигация: OK (9 повторов), 47.579s** — те же новые 8 tests + WindowInsetsRegressionTest. Реальный режим подтверждён insets logcat: portrait bottom 63px, landscape bottom 63px без боковой three-button области; прежний прогон имел portrait bottom 126px и боковую панель 126px. Сразу после переключения settings ещё возвращал 0, поэтому одно это чтение не считается proof режима. После прогона тестовый AVD возвращён в исходный three-button. 26 уникальных device-тестов, 9 повторов; не 35 разных сценариев.
- Новые device-тесты выполняют **настоящий swipeDown**: длинный список после scroll to top, middle-of-list без refresh, short/empty/error; Tickets/card vs MyEvents/empty draft segment; две Event entries; MyEvents → Dashboard E → Volunteers/Back; tab switch во время suspended read; dirty Profile/input; fullscreen/confirmation/guest receipt/Scanner. Spy счётчики считают вызовы read-порта, а не распространение общего store; проверены callback/resource и refreshing/завершение/semantic action, не только анимация. Route-стенд намеренно оставляет hidden parents composed, отдельно от настоящих NavDisplay/Back тестов.
- Старые продуктовые сценарии повторены в настоящем AppHost: cancellation/dialog retry, Dashboard observer freshness/query/recreation/Back/Scanner/Volunteers, guest QR flow/auth overlay, selected ticket/system Back и pending SAF. SafRecreationTest открывает настоящий DocumentsUI, пересоздаёт Activity/поворачивает при открытом picker и доставляет controlled URI через настоящий ActivityResultRegistry/provider. Это не проверка всех native folder/Save UI providers. PDF/XLSX output и QR проверены прежним ProfileFilesIntegrationTest.
- Insets regression проверяет безопасные границы профиля/content и реальный системный tap в portrait + обеих landscape ориентациях. Новые swipe-сценарии в основном portrait; наличие insets regression не означает полный визуальный прогон всех refresh-поверхностей во всех ориентациях.
- Архитектурная структурная проверка и `git diff --check` проходят; точный итоговый счётчик сохранён в `.gradle/t09a-evidence/architecture.txt`. Mermaid rendering не выполнялся, новых Mermaid блоков нет.

Основная команда:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :core:domain:test :data:fake:test :app:testDemoDebugUnitTest :core:platform:testDebugUnitTest :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest :app:lintDemoDebug :app:verifyDemoIsolation -I .gradle/t06-evidence/repeat-tests.init.gradle --offline --console=plain --max-workers=2
# BUILD SUCCESSFUL in 1m 8s, 187 tasks: 29 executed / 158 up-to-date.
```

Логи/точные XML/metadata/baseline/index variants — `.gradle/t09a-evidence/` (игнорируются Git). `final-build.log`, `unit-results/`, `unit-summary.json`, `regressions-device.log`, `apk.json`, `signature.txt`, `badging.txt`, `lint.xml`. Предварительные подтверждения первого среза: 10 Dashboard JVM / 2 component device; полный новый набор после уточнения selector — OK 8, 36.482s. Эти повторы не прибавляются к числу уникальных тестов.

Дополнительные логи — `gestures-device.log`, `insets-logcat.txt`. Screenshot `dashboard-api33.png` извлечён из актуального DashboardIntegrationTest и визуально осмотрен: три таба, прежние insets, header/totals/query/export без обычной refresh button. Он показывает один portrait frame, без активного indicator; это не человеческая визуальная приёмка T09a.

Промежуточные неуспехи сохранены: первый compile test harness использовал CustomActions как обычный SemanticsAction и не компилировался; исправлен вызов test semantics под pinned API, production wrapper собирался. Первый Route-прогон завершился 8 tests / 1 failure: текст «Волонтёры» совпал с пятью nodes retained-parent стенда до целевого scroll; selector ограничен текущей Volunteers поверхностью. Повтор 8/8 и финальные 26/26 прошли. Это ошибки harness, не объявленный RED продукта. Стандартный sandbox shell имел setup refresh error, поэтому команды выполнялись через разрешённый native PowerShell; глобальные SDK/JDK/настройки пользователя не менялись.

Git: исходный большой пакет документации/конфигурации сохранён. 43 не относящихся к задаче исходных файла проверены побайтно; для шести смешанных tracked страниц оставшийся пользовательский delta совпадает с исходным. README/06/08 адресно обновлены по T09a/status/build/ADR; в index переданы отдельные варианты относительно HEAD, без присвоения пользовательской актуализации/history/API/приёмки. Пользовательские untracked architecture maps обновлены в рабочем дереве, их предсуществующая база остаётся untracked; коммит содержит новую каноническую страницу T09a и собственные tracked уточнения. AGENTS/CODE_REVIEW/задание/свидетельство 6 ноября и архивные APK 17/18/19 не изменены. Push не выполняется.

## Адресная ручная приёмка T09a

**Ожидается на code 20; агентом не отмечена.** Прежние принятые H1–H4/demo не переоткрываются. Установить APK поверх предыдущей schema 5 сборки. Для чистого воспроизведения seeds при необходимости использовать явный reset только собственных demo-данных через инструменты.

1. **Tickets:** current/history → карточка → потянуть сверху. Сегмент/выбранный билет/родитель сохраняются, status/QR отражают актуальные данные. Потянуть в середине длинного контента: сначала scroll к началу, refresh начинается только сверху после порога. Повторные pulls не ставят очередь. Проверить короткий/пустой список.
2. **MyEvents:** под Alex/Host проверить Организую/Волонтёр/Черновики, включая пустые. Pull сохраняет сегмент; Tickets не выбирается и не перезагружается вместо списка. Открыть E → pull → системный Back возвращает прежний родитель; повторить с другим E.
3. **Dashboard/Volunteers:** Host → Командный хакатон → Dashboard, ввести query, pull, затем Volunteers. Ввести email/ID, вернуться вверх и pull: input остаётся, grant/revoke не выполняются. Back возвращает Dashboard того же E/query; из Scanner pull не повторяет check-in и не обновляет родителя.
4. **Формы/ошибки:** в Event заполнить часть анкеты и pull при той же схеме — значения остаются; в Profile изменить name/phone/org/preference и pull — правки остаются, сохранённые значения не подменяют их. Если baseline изменился отдельно, сообщение конфликта видно до явного save. Для read Network через demo tools «Ошибка чтения» → Back lifecycle покажет ошибку; pull/«Повторить» восстанавливают чтение без очистки доступных данных. Mutation error/retry остаётся в своей форме/диалоге.
5. **Окна:** открыть fullscreen QR/confirmation и потянуть внутри окна: нижний экран не обновляется. Guest receipt не меняется и не отправляет повторную заявку/письмо. Открыть SAF PDF/XLSX, повернуть и завершить/отменить выбор: прежний pending не теряется; обычный выход/смена ресурса очищает старую операцию.
6. **Layout/IME/система:** indicator сверху по центру в доступной области, без перекрытия системных/верхних кнопок. Portrait/оба landscape, three-button/gestures, IME открыта/закрыта, увеличенный шрифт; обычный scroll/горизонтальные движения/выделение текста не дают ложный refresh. Быстрое локальное чтение может закончиться за один кадр: искусственного delay нет, spinner не должен зависать.
7. **Доступность:** включить настоящий TalkBack, найти custom action «Обновить данные» на нужном контейнере, выполнить его без drag; слышно состояние обновления, обычная кнопка «Обновить» не возвращается. Проверить явный error retry. Semantics-тест не заменяет этот пункт.

Остаточные ограничения: физическое устройство, реальный TalkBack, полная матрица fontScale/IME/providers/всех orientation для нового UX не пройдены человеком. Нет нового OS process-kill proof T09a: refresh flags/gesture distance не сериализуются, восстановление бизнес-команды не обещается. Camera quality/production backend/удаление аккаунта/Q10/T10 не входят; файлы и письма прежние demo defaults. После человеческого подтверждения этого checklist T09a можно перевести в DONE; Q10/T10 ведутся отдельно.
