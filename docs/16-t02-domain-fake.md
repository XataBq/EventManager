# T02.1 — первая предметная итерация domain/fake

Дата: 27 октября 2026. Основание перехода: пользователь попросил прочитать CODE_REVIEW.md и при соответствии плану перейти к T02.

## Проверка основания

CODE_REVIEW.md относится к текущему базовому коммиту `be20799`, сообщает об отсутствии подтверждённых P0–P3 в T01 и допускает независимое продолжение T02/T03. Прочитаны отчёт и исходные контракты/DI; полное повторное ревью не выполнялось. Сам отчёт не изменён и остаётся существующим неотслеживаемым файлом пользователя.

T01 остаётся READY_FOR_HUMAN: его ручная приёмка не отмечена пройденной. Работа с domain/fake не меняет этот статус.

## Реализовано

- Чистые Kotlin-модели Event/Account/ScanGrant, регистрация без обязательного userId, ticket/snapshot, три типа вопросов и типизированные ответы. Domain не зависит от Android/DI/transport.
- EventAccessPolicy: права на конкретное E; владение, grant и билет независимы. Владелец может сканировать; волонтёр не получает управление/экспорт/участников. Отзыв grant не убирает свой билет.
- Availability: безлимит отдельно от Limited; 0 мест закрывает регистрацию. StartsAt закрывает новые заявки, endsAt/manual Finished завершают событие. Отдельные исходы Closed/Full/Started/Finished.
- RegistrationValidator: consent, имя на любой письменности и без требования двух слов, optional phone, базовая проверка email, текст/checkbox/single choice, required, тип ответа и ID вариантов. Phone policy Required/Optional/Hidden меняется централизованно без переписывания старых регистраций.
- EventRepository, RegistrationRepository.submitGuest и TicketRepository.observeMyTickets. Полный перечень будущих команд из 05 намеренно не создавался заранее.
- FakeEventPassEnvironment: один in-memory store для всех трёх портов, фиксированный инъектируемый Clock, seed `t02-v1`, контролируемая ошибка следующего submit и Reset. Это локальный demo backend, а не настоящий серверный движок.
- Guest submit возвращает GuestTicketSnapshot и добавляет запись demo outbox, но не создаёт User. Повторная заявка возвращает ExistingRegistration без повторного QR/ID; существующий account — RequiresLogin, без хранения анкеты и автоотправки после входа.
- Demo-сценарий отдельного аккаунта на тот же email: после подтверждения и явного входа доступны ранее выданные билеты; другая личность их не получает. ScenarioController управляет тестовой сессией/аккаунтами, а не реализует AuthRepository, пароль или подтверждение письмом.
- Demo Hilt-модуль предоставляет порты из одного singleton environment. Зависимость fake по-прежнему только demoImplementation.

## Где искать

| Часть | Файлы |
|---|---|
| Модели и access/availability | `core/domain/src/main/kotlin/ru/eventpass/core/domain/event/EventModels.kt` |
| Заявки, вопросы, исходы и билеты | `core/domain/src/main/kotlin/ru/eventpass/core/domain/registration/RegistrationModels.kt` |
| Предметная валидация | `core/domain/src/main/kotlin/ru/eventpass/core/domain/registration/RegistrationValidator.kt` |
| Текущие порты | `core/domain/src/main/kotlin/ru/eventpass/core/domain/ProductRepositories.kt` |
| Общий store, репозитории, debug controls | `data/fake/src/main/kotlin/ru/eventpass/data/fake/product/FakeEventPassEnvironment.kt` |
| S01 и открытое событие | `data/fake/src/main/kotlin/ru/eventpass/data/fake/product/FakeFixtures.kt` |
| DI | `app/src/demo/kotlin/ru/eventpass/app/di/DemoModule.kt` |

## Проверки

Использован установленный JBR 21.0.10 из Android Studio1. Wrapper/версии библиотек не менялись, загрузка зависимостей не требовалась.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :core:domain:test :data:fake:test :app:assembleDemoDebug :app:lintDemoDebug :app:testDemoDebugUnitTest :core:platform:testDebugUnitTest :app:verifyDemoIsolation --offline --console=plain --max-workers=2
```

Итог: BUILD SUCCESSFUL. По XML-отчётам 24 unit-теста, 0 failures/errors/skipped: 6 domain, 11 новых product fake, 1 прежний demo fake, 5 app, 1 platform. Новые проверки охватывают связи на разных E, revoke без потери билета, отсутствие автоаккаунта, наблюдаемое уменьшение мест, привязку по email, manual session после confirm, смену личности/logout, повтор заявки без повторного QR, consent/stale form, закрытие/начало/лимит, управляемый Timeout, смену policy и копирование ответов, Reset.

Assemble/Hilt compilation и demo isolation прошли. Lint: 0 ошибок, 6 предупреждений о доступных новых версиях (как в T01). Device-тесты и ручные H-gates в этой задаче не запускались; тесты сервера/конкурентного распределения мест не добавлялись.

## Реальные границы текущей итерации

- T02 целиком **IN_PROGRESS**, T02.1 — завершённый слой данных с unit-проверками. Persistence fake backend, отмена регистраций, AuthRepository и остальные порты добавляются в следующих итерациях по фичам; не отмечать их готовыми.
- Store в памяти, состояние при новом процессе создаётся из seed. Файл snapshot/постоянная база не добавлялись. Это ограничение перед будущей ручной приёмкой связных сценариев.
- UI остаётся техническим T01. Порты зарегистрированы в demo DI, но продуктовых экранов нет. APK после сборки содержит новую data-реализацию, однако не показывает её через обычный пользовательский интерфейс.
- Android saved state и реальное изображение QR ещё не реализованы: snapshot содержит строковый demo payload; его кодирование, показ и владелец flow — T05. Предсказуемые demo-токены нельзя переносить в production.
- Clock по умолчанию фиксирован на `2026-10-27T09:00:00Z`. Для изменения времени инъектировать другой Clock и вызвать refreshPublicEvent; фонового таймера нет. Submit всегда проверяет Clock заново, даже если открытая страница устарела.
- Exact email match после trim — правило fake; серверный алгоритм сопоставления не выдумывается, почта не нормализуется по Gmail-подобным правилам. Синтаксическая проверка email базовая. Телефон принимает формат +7 или национальный 8 с десятью следующими цифрами; это не проверка принадлежности номера абоненту/оператору.
- Мутации ScenarioController — инструменты тестов/debug, не проверенные owner API. Редактирование анкеты после первой регистрации, отмена и check-in проверяются при реализации соответствующих команд, а не объявляются реализованными наличием моделей.
- Нет сети, реальных писем/PDF, DTO, серверных retry/idempotency, новых разрешений или изменения UI-kit. Изменения T02.1 подготовлены к отдельному коммиту по запросу пользователя; существующий CODE_REVIEW.md и посторонние изменения в него не включаются.

Следующий полезный этап — T03 (компоненты/галерея), затем T04/T05 с текущими портами; продолжать T02 данными, нужными очередной фиче. Перед H2 добавить необходимое сохранение данных fake-сервера, сохранив запрет постоянного клиентского guest QR-cache.
