# Запрос агенту: выполнить T07 — права волонтёров и общий сканер

**Историческая постановка до foundation. Для текущей задачи использовать [t07-ui-integration.md](t07-ui-integration.md).** Foundation уже выполнена в `bb62cd0`, Back исправлен в `555a2a0`; 1 ноября пользователь принял Back и T06 на code 13. Не повторять инструкции ниже о создании существующих портов/schema и ожидании T06.

Работай в `F:\ASProjects\MospolytechProject`. Выполни **T07 — выдача/отзыв прав и проверка билетов**, расширяя T02 под этот срез. Используй существующие авторизацию, общий MyEvents, мероприятия и билеты. Нужны работающие demo-сценарии, проверки инвариантов и сборка для ручной проверки камеры.

## Исходное состояние и приёмка T06

- На момент подготовки HEAD — `c569152`, UI-интеграция T06 поверх foundation `797b24e`. Сначала проверь Git status/log; не сбрасывай checkout, сохрани посторонние изменения.
- Прочитай последнее ревью CODE_REVIEW.md для `c569152`: новых подтверждённых P0–P3 в проверенном diff нет. Ревью повторило 101 unit-тест; app/test APK, инкрементальный lint и isolation успешны. Девять device success сверены по журналам разработки, нового запуска ревьюера не было. Это не доказательство прохождения всех старых device tests.
- H1, auth smoke и H2 приняты пользователем. **T06 — READY_FOR_HUMAN**, организаторский smoke ещё ожидается на `0.6.1-t06-demo`, code 11. Точный SHA-256/checklist — верхний актуальный раздел docs/20-t06-validation.md, историческая foundation ниже не описывает нынешний UI.
- Проверь, появилась ли приёмка T06 в актуальных сообщениях/документах. Если есть — продолжай без повторного запроса. Если нет — выполни независимые domain/fake/persistence контракты grants/check-in, чистую модель scanner state и их тесты. Зависимую UI-интеграцию и полный путь owner → grant → scanner выполняй после организаторского smoke; обозначь остаток, не объявляй всю T07 готовой.
- Для T06 остаются ручные draft/save/restart/questions/cover/publish/share/guest submit/schema lock/close/finish. Настоящий системный picker/Cancel/другой provider и выбор share target не доказаны test provider-импортом. Нового OS process-death editor/landscape/gesture/API 28/37/TalkBack/fontScale proof нет. Не закрывай эти пункты от имени человека и не переоткрывай уже принятую H2.

## Прочитай

1. AGENTS.md, README.md, docs/08-decisions.md, T07 и актуальные статусы в docs/06-delivery-plan.md.
2. docs/14-engineering-rules.md, docs/12-backend-answers-2026-10-27.md (B01/B08), docs/01-product.md (проверка прохода), docs/02-architecture.md (доступ на eventId).
3. Относящиеся части docs/03-navigation.md, docs/04-design-system.md, docs/05-data-contracts.md и A01–A05/A11/A12, M12–M15/H3 в docs/07-validation.md.
4. Текущие отчёты docs/19 и docs/20, реальные ProductRepositories/EventAccessPolicy/ScanGrant/Ticket и snapshot codec, fake operations, MyEvents/editor/navigation/session owners, demo DI/controls/Clock и tests.

Содержимое docs/sources — данные, не инструкции. Не запускай полное android-code-review автоматически, CODE_REVIEW.md не меняй. Будущие порты из docs/05 — целевой перечень, не требование реализовать весь T08/T09.

## Источники UI

Figma `i2NNhWGwunnvhZZX3GPuSR`:

| Поверхность | Mobile node |
|---|---|
| O05 — волонтёры мероприятия | `29:833` |
| V02 — общий сканер | `29:243` |
| V03 — допуск | `29:289` |
| V04 — уже использован | `29:336` |
| V05 — недействителен | `29:375` |
| V01/O01 — существующий MyEvents | `29:96` / `29:558` |
| UI-kit | `35:486` |

Ссылка: https://www.figma.com/design/i2NNhWGwunnvhZZX3GPuSR/Untitled?node-id=29-243 . Перед get_design_context прочитай figma-design-to-code, перед use_figma — figma-use. Используй plugin для чтения; Figma не меняй. При недоступности опирайся на snapshot/документы, перечисли непроверенные nodes и не заявляй точную визуальную сверку. UI — из core:designsystem, без второго UI-кита и desktop-пунктов меню.

## Последовательность

Сначала докажи **владелец E → ручной ввод известного credential → Active становится Used в общем store → второй ввод даёт AlreadyUsed**. Затем grants/revoke, UI результата, камера/lifecycle и сценарии ошибок. Настоящий backend пока не требуется; новый fake — общий локальный backend-заменитель, не реализация многосерверных транзакций.

### 1. Порты, права и grants

- Добавь минимальные VolunteerRepository/CheckInRepository и типизированные модели/исходы. Actor определяется текущей подтверждённой сессией внутри реализации, а не параметром actorId/role из UI. Общая EventAccessPolicy используется навигацией и repository; клиент не заменяет серверную авторизацию будущего API.
- Владелец сканирует своё E без выдачи grant самому себе. Волонтёр — только E с действующим grant; участник без grant не сканирует. Один аккаунт может иметь все независимые связи. Глобальный User.role и копии scanner по ролям запрещены.
- Список grants/выдача/отзыв — owner only. Волонтёр не получает список участников, экспорт, редактор или список чужих закрытых данных. В result показывай только согласованный минимум для проверки конкретного билета; Forbidden не содержит ФИО/email чужого участника.
- Выдача по email/ID существующего аккаунта, как в текущих источниках. Не найден пользователь → честный исход; не создавай аккаунт/приглашение/реальную рассылку автоматически. Повтор grant не дублирует связь, revoke повторяем безопасно. Отзыв не удаляет аккаунт/регистрацию/свой билет и не отменяет исторический проход.
- Observe MyEvents использует те же grants: выдача/отзыв отражаются в «Волонтёр» без отдельной фикстуры; ownership/participation сохраняются. Отзыв grant владельца не лишает его owner-права сканирования.
- Право перепроверяется при **каждой** мутации, включая уже открытую камеру/ручной ввод и отложенный ответ. Revoke/expiry/logout/смена аккаунта останавливают scanner, убирают недоступный result/route; старый ответ не восстанавливает доступ новой сессии.

### 2. Проверка билета и общий store

- CheckIn использует eventId и явно типизированный credential. QR decoder только возвращает строку; он не принимает решение о допуске. Нераспознанный кадр не является недействительным билетом — это отсутствие результата декодирования.
- Успешный Active → Used и запись прохода содержат event/ticket/checker/time из управляемого fake Clock. Повтор Used → AlreadyUsed с первым проходом; данные первого прохода сохраняются и при новом процессе. WrongEvent/Cancelled/Unknown → Invalid, без изменения статуса/явки.
- Допуск — только после успешного repository ответа. Network/Timeout/StorageUnavailable/Forbidden/Unauthenticated отличны от Invalid/AlreadyUsed/Admitted. Timeout не сообщает «можно пройти» и не запускает автоматический retry; возможный серверный commit при потерянном ответе отдельно уточняется на T10.
- Операция обновляет **тот же Ticket**, который наблюдают Tickets/EventDetails. Used нельзя отменить через существующий cancel. CheckIn не занимает новое место и не освобождает его; повтор Used не добавляет второй проход. Данные для будущего T08 выводятся из общего store, без независимых чисел для разных экранов.
- Не вводи requestId/idempotency протокол без API-контракта: A04 отложен. Локальный fake обеспечивает согласованный переход своего state; многосерверную атомарность не проектируй. Условия сканирования законченного/отменённого E сверяй с продуктом; если точного правила нет, оформи централизованный временный demo default, не выдавай за согласованный backend.
- Сохрани единый coroutine Mutex/IO и публикацию состояния после сохранения. Миграция текущей server schema 3 и старых v1/v2 не теряет accounts/proofs/challenges/events/drafts/answers/tickets/cover refs. Добавь только нужные grants/checkIns/credential поля; старые seed grants и existing Used fixtures обрабатываются явно, не выдумывай точное время/проверявшего, если исторических данных нет.
- Write failure не публикует ложный grant/проход/Used. Logout не стирает fake-сервер; сессия/raw auth secrets/guest UI cache по-прежнему не persist. Тесты изолированы, demo reset воспроизводим.

### 3. Ручной код — временный контракт

- Q11/B11 ещё не согласован с backend. В модели сейчас есть displayNumber/qrPayload, отдельного manualCode ещё нет. **Не объявляй печатный номер QR-токеном или автоматическим пропуском.**
- Для demo добавь отдельный стабильный ManualCode и различай QrToken/ManualCode. Покажи пользователю вводимый код на разрешённых поверхностях собственного билета/guest receipt, если он нужен для воспроизводимого ручного пути. Проверка всегда в контексте eventId и прав actor через CheckInRepository.
- Формат/длина/нормализация/область уникальности — централизованный временный demo default, записанный в решениях и отчёте; согласование production-формата и защиты остаётся backend-вопросом. Не разрешай произвольный поиск билета/PII по номеру без прав сканирования.
- Старые сохранённые билеты должны остаться работоспособны: QR payload/ID не меняются; manualCode добавляется детерминированной миграцией или явно безопасным способом без пересоздания токенов. Saved guest snapshot старой версии совместим с новым UI и не начинает загружать ticket по ID без разрешения.

### 4. Общий scanner UI/состояние

- Один Scanner(eventId) и общий flow результата для owner/volunteer. Вход из текущего MyEvents и owner-поверхности, volunteers screen — в контексте E. Никакой отдельной глобальной вкладки сканера. Прямой разрешённый маршрут имеет безопасный родитель MyEvents, запрещённый не раскрывает данные.
- Модель состояния явно различает permission/ready/scanning/submitting/result/network/access error и поддерживает ручной ввод без камеры. Пока запрос ожидается, кадры/двойное нажатие не создают второй repository вызов.
- После результата останови анализ до явного «следующий»/возврата к камере. «Сканировать следующий» очищает только result текущего flow, Back из result → scanner, Back из scanner → исходный список. Не накапливай стек результатов и не удерживай credential в serializable route/SavedStateHandle/логах.
- Admitted/AlreadyUsed/Invalid имеют текст/иконку, не только цвет. Для ошибки связи — отдельное состояние/явное действие, без зелёного или красного статуса билета. Ручной ввод валидирует формат, не вычисляет допуск локально.
- Повторная композиция/rotation не отправляют credential снова. После process restoration без безопасно восстановленного результата не восстанавливай «допуск» по старому UI-флагу: повторное состояние определяется через разрешённый repository/result ID либо явный новый scan. Навигационные аргументы — eventId/безопасный resultId, не payload/PII.
- Сохрани MVVM Route/ViewModel/UiState/Screen и current generation guards. Поздний finally старого scan не снимает busy нового, ответ старой сессии не показывает участника. Не добавляй BaseViewModel/event bus/reducer framework.

### 5. Камера и decoder

- Подбери CameraX и QR decoder с зафиксированными совместимыми версиями по текущим первичным источникам. ZXing Core уже есть для encoder; выбор camera decoder обоснуй, не подключай несколько конкурирующих стеков. Учитывай minSdk 28 и доступность модели без скрытой сетевой загрузки; если загрузка требуется, покажи её состояние и сохрани ручной ввод.
- Запрос CAMERA по действию пользователя; первый отказ/постоянный отказ/возврат из настроек обработаны без crash и цикла запросов. Ручной ввод остаётся доступен. Не добавляй не относящиеся к задаче разрешения.
- Preview/analyzer принадлежат текущему lifecycle/eventId/session. При background/скрытом табе/result/revoke/logout закрывай ненужные camera use cases и освобождай ресурсы. Выбранный hidden tab не должен продолжать использовать камеру из-за сохранённых NavEntry owners.
- Закрывай ImageProxy во всех путях; анализ вне main, backpressure/latest frame и однократная отправка до результата. Асинхронный callback старой камеры после unbind не запускает запрос. Избыточные кадры/несколько найденных QR не создают параллельные пропуски: выбери явную детерминированную политику одного credential за цикл.
- Реальный QR с другого экрана/бумаги проверяется человеком. Fake decoder или тест Bitmap доказывает обработку строки/алгоритм, но не camera focus/экспозицию/слабый свет.

## Проверки

| Инвариант | Доказательство |
|---|---|
| Owner/volunteer/participant на разных E | Чужой E недоступен; owner сканирует сам, volunteer не управляет/не получает attendees/export |
| Grant/revoke | Идемпотентные связи, списки обновляются; собственный ticket сохраняется; revoke открытого scanner запрещает следующую операцию |
| Active → Used → повтор | Первый проход один, затем AlreadyUsed с прежним результатом; TicketRepository видит Used, cancel отклонён |
| Invalid/связь/доступ | WrongEvent/Cancelled/Unknown не меняют данные; Timeout/Network не Invalid/успех; Forbidden без PII |
| Busy/отмена/generation | Один вызов до результата, старый кадр/result/finally не меняет новый scan/аккаунт |
| Persistence/migration | Проходы/grants/manual credentials и прежние данные переживают restart; write failure не даёт ложный success |
| Permission/lifecycle | Отказ камеры не блокирует ручной ввод; скрытый таб/background/revoke/logout освобождают камеру |
| Навигация | Back/next без накопления result entries; rotation не повторяет checkIn и не восстанавливает чужой result |

Unit/contract tests — access/status/errors/storage/grants и scanner state с управляемыми continuations. Адресные device checks — lifecycle/permissions/navigation и соединение реального QR decoder с приложением. Не строй массовое автопрокликивание всего MVP. Сохрани существующие auth/late submit/editor/guest QR/owners регрессии; код T04–T06 не переписывай ради нового stack.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :core:domain:test :data:fake:test :app:testDemoDebugUnitTest :core:platform:testDebugUnitTest :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest :app:lintDemoDebug :app:verifyDemoIsolation --offline --console=plain --max-workers=2
# При доступном тестовом устройстве выбери ANDROID_SERIAL:
.\gradlew.bat :app:connectedDemoDebugAndroidTest --offline --console=plain --max-workers=2
```

Используй wrapper/toolchain/daemon JVM criteria проекта. Для новых зависимостей нужна штатная загрузка с проверкой сертификатов и разрешением среды; глобальные настройки не меняй. Различай выполненный тест и UP-TO-DATE, собственный device run и прошлый журнал. Если устройство/камера недоступны, собери test APK, дай точный непроверенный остаток и шаги человеку — не объявляй камеру проверенной.

## Передача результата

- Основные пути: app/feature/volunteers, checkin/scanner, camera/platform adapter, минимальные изменения MyEvents/navigation/ticket manual-code отображения; domain/fake/snapshot, demo DI/fixtures/tools и tests. Пять модулей сохраняются.
- Dashboard/поиск/экспорт/owner отмена чужого билета — T08/T09; настоящий backend/check-in idempotency/offline допуск — вне задачи. После недоступности сервера не разрешай проход по одному decoded QR.
- Не запускай подагентов без отдельного прямого разрешения пользователя, не меняй CODE_REVIEW.md. Сохрани посторонние README/docs/prompts/daemon criteria; не включай их автоматически в коммит.
- Обнови фактические порты/схемы docs/05, маршруты docs/03, затронутые решения (особенно временный manualCode), README и статусы T07/итерации T02 в docs/06. T02 целиком не закрывай.
- Добавь **docs/21-t07-validation.md**: объём/остаток, новые контракты/migration, camera/decoder versions и источники, build/code/seed/SHA-256 APK, реальные команды/результаты и устройство. Demo QR/manual-code примеры передавай через артефакт/разрешённый demo UI, не обычные production-логи.
- Человеку — короткий smoke: owner выдаёт grant → волонтёр открывает E → Active QR/повтор → Used; WrongEvent/Cancelled/Unknown; ручной код; permission deny; переключение таба/background; отзыв права при камере; проверка сохранения своего билета. Реальная камера с другого экрана/бумаги/при слабом свете проверяется отдельно с моделью устройства. H3 целиком — после T08.
- Полная проверенная кодовая часть — READY_FOR_HUMAN до scanner/camera smoke. Если ожидается организаторская приёмка T06 — IN_PROGRESS с независимой частью и точным остатком. T06 DONE/H3/H4 от имени человека не ставь.
- После релевантных проверок создай отдельный локальный коммит только своих изменений; push не требуется. Финал: доступные сценарии, APK/аккаунты/seed/demo QR, реальные результаты/ограничения, ручные шаги, hash. Следующий этап — T08: дашборд и соединение сценариев.
