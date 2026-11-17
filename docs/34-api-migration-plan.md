# T10: сравнение, миграция правил и интеграция API

**17 ноября — M07 IMPLEMENTED/COMPILE_VERIFIED; поведение NOT_VERIFIED.** Remote dashboard/участники/owner cancel подключены к общим UI/ports. [Архитектура M07](architecture/t10-dashboard-attendees.md), [compile и ограничения](validation/t10-m07-implementation.md). Одна remote Kotlin/Java/DI компиляция прошла; suites/lint/demo/staging/APK/device/review не запускались. M05/M06 поведенчески не проверены; старые review/H-пункты не закрыты. M08–M09 TODO, ручная приёмка после M09.

Основание: Android `0493dbd2e328796cd963689cc25cf3d4d2bd40e1` плюс существующие незакоммиченные документы; backend `def65653b9e1f22dce8bc2d6f266b23ace6cd1d5`, OpenAPI/staging 1.1.1. Согласованные правила — [08](08-decisions.md), задание backend — [32](32-backend-final-requirements.md), проверка сервера — [33](33-backend-update-2026-11-13.md). Старое предложение 44 URL и B01–B08 не отменяют более поздних решений пользователя.

## 1. Режим выполнения

- Это план адаптации работающего demo, не переписывание приложения с нуля. Kotlin/Compose/Hilt/Navigation 3, minSdk 28 и MVVM сохраняются.
- Один последовательный этап меняет цельный набор объектов и их потребителей: domain → fake → remote → VM/UI → тесты → документация. Не оставлять мёртвые старые порты или неработающий demo до последнего этапа.
- После каждого этапа — автоматические проверки, обновление контекста и отдельное ревью через [android-code-review](../.agents/skills/android-code-review/SKILL.md). Ревью читает исходники, дописывает CODE_REVIEW.md, не исправляет код. Исправления делает реализационная задача, затем повторное ревью diff исправлений.
- **Ручная проверка только в конце всего цикла.** Между этапами пользователь не нужен для прокликивания. Существующие непройденные пункты нового T09a включаются в итоговый checklist; прежние приёмки не переименовываются в приёмку remote.
- Переход: проверки зелёные, нет открытых P0–P2, P3 явно разобраны; блокирующий риск или непроверенный обязательный инвариант нельзя спрятать за «находок нет». Если исправление требует нового продуктового решения, остановить зависимую работу, продолжить независимую. Не запускать субагентов: с 15 ноября работа и ревью выполняются только отдельными последовательными задачами, без делегирования.
- Межэтапный статус: IMPLEMENTED → REVIEW_PENDING → TECH_ACCEPTED. Последний не равен HUMAN_ACCEPTED. Весь T10 DONE только после итоговой ручной приёмки.

## 2. Что уже есть и что меняется

Сравнение основано на прочитанных Kotlin-моделях/портах и вызывающих местах, не только на старых отчётах. Основные точки: [ProductRepositories](../core/domain/src/main/kotlin/ru/eventpass/core/domain/ProductRepositories.kt), [AuthRepository](../core/domain/src/main/kotlin/ru/eventpass/core/domain/auth/AuthRepository.kt), [EventModels](../core/domain/src/main/kotlin/ru/eventpass/core/domain/event/EventModels.kt), [EventEditing](../core/domain/src/main/kotlin/ru/eventpass/core/domain/event/EventEditing.kt), [RegistrationModels](../core/domain/src/main/kotlin/ru/eventpass/core/domain/registration/RegistrationModels.kt), [CheckInModels](../core/domain/src/main/kotlin/ru/eventpass/core/domain/checkin/CheckInModels.kt), [AttendanceModels](../core/domain/src/main/kotlin/ru/eventpass/core/domain/attendance/AttendanceModels.kt), [DocumentModels](../core/domain/src/main/kotlin/ru/eventpass/core/domain/documents/DocumentModels.kt).

| Область | Реализовано сейчас | Принятое поведение и изменение |
|---|---|---|
| Модули/DI | Пять модулей, только demoDebug, DemoModule; verifyDemoIsolation запрещает другие варианты | Добавить remoteDebug с отдельным applicationId, URL/DI/storage; позднее release. Переписать isolation-проверку на разрешённую матрицу и отсутствие fake/tools/outbox в remote APK |
| Signup/вход | Signup → EmailChallenge, signIn отклоняет EmailUnverified | Signup и login → Session, verify не условие входа. Обновить AuthRepository, AuthViewModels/Screens, SessionCoordinator и guards; emailVerified остаётся информацией профиля |
| Сессия | Transient Session с обязательным expiresAt; private scope зависит от session.id и verified | Bearer на 30 дней без refresh/expiresAt в ответе. Токен хранится защищённо, session.id — локальный несекретный ID; серверная проверка /me и 401, generation защищает поздние результаты |
| Reset/verify | challenge/token, код → resetGrant → новый пароль; demo link parser | Setup/reset/verify через серверные ссылки 24 часа. Удалить код/grant-экраны. Пока App Links отложены: действие выполняется web-страницей, приложение перечитывает /me при возвращении; после setup/reset — ручной login |
| Гость | Аккаунт не создаётся; issued snapshot в текущем flow | Сервер создаёт passwordless Account, но это не Session. Показать QR/результат отправки на почту; capability отдельно от bearer. Сохранить flow lifetime без постоянного guest cache |
| Существующий email | RequiresLogin, нет сохранения анкеты перед login | EMAIL_TAKEN → предложить login, сохранить только безопасный return target. Не добавлять web-поведение автоповтора анкеты после входа: B04 этим решением не изменён |
| Права/роли | EventAccessPolicy и private restore требуют verified; независимые grant/ticket/owner уже есть | Убрать verified как auth gate во всех потребителях, сохранить независимые права на eventId. Backend окончательно разрешает каждую команду; 403 отличается от 401 |
| Публичное событие | Навигация и observePublicEvent используют EventId | Развести EventId и EventSlug/PublicEventReference. Public GET по slug, editor/scanner/registration по id. Не подставлять один вместо другого; return destination не содержит секретов |
| Lifecycle/места | PUBLISHED без LIVE, registrationOpen/closeRegistration, запрет после startsAt, null capacity=Unlimited | DRAFT/PUBLISHED/LIVE/FINISHED, effectiveEndsAt и finishedAt, регистрация во время live. Удалить closeRegistration UI/порт. У published capacity>0, null только у draft; сервер распределяет места |
| Редактор | Draft уже неполный, но локальные revision/formVersion, вопросы фиксируются; даты started заблокированы | Nullable draft из API, PATCH непереданных полей не сбрасывает их, вопросы редактируются после submit. Удалить искусственные questionsLocked/expectedFormVersion и ограничения, которых нет у сервера |
| Анкеты | Text/Checkbox/SingleChoice и один вариант уже достаточен | Поддержать text/textarea/email/tel/number/date/choice/select/multiple/checkbox, hints/help/placeholder, options минимум 2 для опубликованного E; boolean/string/string[] без потери типа и истории |
| Программа/обложка | EventSection без id; asset локального AndroidEventCoverStore | Стабильные section id и порядок; upload → серверный assetId/url/размеры. Не выдавать локальный asset за удалённый; draft-image требует авторизации |
| Контакты | AuthenticatedRegistrationCommand содержит name/phone | Сервер у вошедшего берёт имя/phone из профиля: не обещать сохранение отдельных введённых контактов заявкой. Форма показывает профиль/переход к его редактированию. Guest phone optional в UI, mapper передаёт пустую строку. Имя любой письменности, локальная проверка RU телефона |
| Билеты/история | Own tickets завязаны на verified/email, QR только current Active; snapshot содержит вычисленный ManualCode | Владение по серверной userId, server upcoming/past и Active/Used/Cancelled. Принять разрешённые сервером PDF/QR статусы, не фильтровать только Active из старого demo. Manual EVT-xxxxxx из code, qrToken не изменять; accessToken не равен qrToken |
| Scanner | 12-символьный ManualCode от hash QR; Admitted требует полный CheckInRecord | EVT-код и opaque QR; replayed=true — восстановленный результат, не новый допуск. API не всегда даёт ticketId/checkerId/scanId: переделать outcome под фактические поля, не выдумывать ID или первый проход |
| Dashboard | Локальный revision-bound offset; Attendee требует registeredAt/checkerId/registrationId | page/pageSize + revision, STALE_PAGE очищает старую пагинацию и перечитывает stats/page. Missing поля сделать optional/убрать из UI-проекции; cancel передаёт ticketId. Не использовать dashboard revision для PATCH event |
| Профиль/удаление | ProfileFields не даёт менять email; удаления нет | PATCH /me включая email/notificationsEnabled; после смены email обновить Account/verify состояние, не потерять историю билетов. DELETE /me с каскадом без пароля после понятного подтверждения последствий; очистка состояния/хранилищ |
| Документы/письма | Локальная генерация PDF/XLSX, DocumentSnapshot из domain rows, resendTicket/outbox | В remote скачать готовые PDF/XLSX и записать через SAF без локальной пересборки. Удалить продуктовый ticket resend из общих UI/портов; auth resend остаётся. Fake-генераторы только demo |
| Offline/ошибки | Общий fake snapshot schema 5, Network/Timeout в демо | Изолированный Room draft snapshot+pending edits; server response подтверждает сохранение. Transport→типизированные ошибки, 429/Retry-After, unknown protocol error, никакого разбора русского message |

## 3. Архитектурные решения для реализации

Решения приняты **для этого плана**, код ещё им не соответствует. При реализации в каждом этапе заменить соответствующий раздел фактической документации, не вставлять новую несовместимую спецификацию поверх старой. Основания и последствия решений сохранять по ID ниже.

### ADR-T10-01: границы модулей и MVVM

Сохраняем Route → VM → repository → immutable UiState. Domain без Android/HTTP/DTO/Room/Hilt. Нет BaseViewModel, общего event bus, универсального BaseRepository и use case на каждый getter.

`data:remote` — Android library с HTTP, DTO, mapping, реализациями repository и scoped read-coordination; app/remote DI выбирает bindings. `data:local` вводится только с первым рабочим Room draft в M05; он зависит от domain и не знает HTTP. Remote может использовать local через конкретную границу draft storage; local не вызывает remote. Keystore/file/SAF — platform; app связывает реализации. Не создавать отдельные core:network/core:database/feature-модули без доказанной потребности. app/main не импортирует fake/remote concrete classes.

Выбор транспорта: OkHttp + уже используемый kotlinx.serialization, без второго JSON-стека и генерации всего приложения из OpenAPI. Версии OkHttp/MockWebServer и Room/KSP проверить по первичным release notes и реальной сборке на M01/M05, затем закрепить в version catalog. Текущий toolchain без необходимости не обновлять; совместимость новых библиотек этим планом не доказана. HTTP API минимально типизировать по мере рабочих срезов, не создавать все пустые репозитории заранее.

### ADR-T10-02: authority, identity и неизвестные поля

Backend владеет аккаунтом, правами, местами, фактами регистрации/scan/status. UI checks — подсказки, не авторизация. Identity: userId/eventId/ticketId/slug/publicId/grant различаются. Нет глобальной роли. Profile и Session используют одну согласованную Account-проекцию; запросы захватывают accountId+sessionGeneration+resourceId.

Нельзя эмулировать отсутствующий expiresAt/formVersion/eventRevision/checkerId через now(), hash, 1 или synthetic IDs. Локальная generation/version помечена как локальная. Server timestamps и client fetchedAt — разные поля. Незнакомый question type блокирует соответствующую отправку с понятной ошибкой, не превращается молча в Text; unknown JSON properties можно игнорировать. Null date/capacity законен только в draft.

### ADR-T10-03: сессия, секреты и очистка

Секрет bearer принадлежит data/platform TokenStore, не Session.id, NavKey, SavedStateHandle, Room snapshot или логам. Хранение в приватном файле с AEAD и ключом Android Keystore, атомарная запись, исключение из backup/device transfer; при потере/инвалидации ключа — безопасный logout. Пароли и setup/reset токены не persist. Восстановление: Unknown → чтение хранилища → /me → Authenticated; при сети недоступной не объявлять валидную сессию подтверждённой и не выдавать новые права. Не высчитывать авторитетное истечение как now+30d. На 401 текущего bearer один раз очистить сессию и её private state; поздний 401 старой сессии не выбивает новую. 403 очищает доступ к E, не весь аккаунт.

HTTPS, системная проверка сертификата, cleartext запрещён, без trust-all и самодельного pinning. Bearer добавляется только к API разрешённого origin; загрузка внешнего URL/redirect не уносит его на чужой host. Не логировать bodies/Authorization/guest query tokens/QR/PII. Remote не содержит demo tools/outbox/паролей.

Logout локально очищает токен, entry state, private read caches, scoped jobs, временные файлы и локальные данные аккаунта даже при ошибке сети. Удалённый logout без сети не гарантирует отзыв bearer: это ограничение отразить, не показывать серверный успех. DELETE /me при timeout — неопределённый исход, проверить /me; не считать аккаунт удалённым только по очистке UI. Успешное удаление очищает всё связанное локально. Pending draft при явном logout удаляется; при сетевой ошибке/неподтверждённом refresh не теряется, до повторного входа того же account недоступен.

### ADR-T10-04: guest и отложенные ссылки

Guest autoaccount не аутентифицирует приложение. QR/access capability — только у владельца текущего receipt flow; компактное восстановление той же entry, без Room/DataStore guest cache. guest capability не попадает в bearer interceptor. После закрытия flow — письмо или setup/login. Не сохранять полную анкету для переноса в login.

Verified App Links не реализуем сейчас. Verify/setup/reset открываются в браузере на серверном web origin; возвращение в приложение вызывает безопасный refresh профиля/сессии. Для открытия E в приложении на текущем этапе — внутренние переходы и явный ввод/вставка публичной event-ссылки с allowlist host + /e/{slug}, без автоматического перехвата ОС. Не заставлять пользователя копировать auth token в приложение. Итоговая ручная приёмка включает полный путь через браузер.

### ADR-T10-05: read state и mutation retry

Сохранить single-flight, visible/RESUMED, generation/late-result guards из T09a. Observe Flow не равно серверный push: read repository публикует последний ответ и freshness/error; mutation инвалидирует ровно затронутые E/tickets/stats, отдельный private resource scope. Не вводить бесконечный polling hidden экранов. При scanner resume/foreground и редком lifecycle-bound polling проверять права; backend всё равно проверяет каждый scan.

create/registration/scan: одна логическая отправка = один key и неизменное тело, явный повтор после неизвестного сетевого исхода использует тот же key, 24h retention сервера. Новый body → новая команда только после разрешения предыдущей. Текущая анкета валидируется локально лишь перед первой отправкой; replay pending body не перевалидируется по обновлённым questions/policy и не меняет исходные key/answers. Автоматический retry HTTP mutation отключён, проверка настроек транспорта обязательна. Cancellation не доказывает rollback сервера. Replay allowed отображается как восстановление результата, без нового зелёного допуска/сигнала. 429 хранит время доступного повтора, жест refresh не повторяет mutation.

Полное тело регистрации и QR scan не сохранять на диск ради retry. Живой operation owner хранит key/body в памяти через recreation; допустимый saved metadata — key/fingerprint/состояние неопределённого исхода, без ответов/credential. При process death без тела нельзя обещать replay: сначала сверка tickets/scanner, guest — письмо/setup. Не создавать автоматически новую заявку/проход. Draft create может восстанавливаться с неизменным телом из pending Room draft; локальная draft-ID → remote-ID mapping и key предотвращают дубли.

### ADR-T10-06: draft offline и честные конфликты

Room хранит accountId/localDraftId/remoteEventId, подтверждённый server snapshot, pending fields, базовую версию/снимок и sync state/error. API response обновляет подтверждённый snapshot; UI различает «сохранено на устройстве» и «сохранено на сервере». Локальная правка не исчезает от refresh. Локальная обложка до upload — отдельный pending asset, временный URI не является server assetId. Очистка/миграции/ограничения размера обязательны. Offline publish/check-in/регистраций не добавляем. WorkManager и фоновую универсальную очередь пока не вводим: foreground/явная синхронизация достаточно.

**Ограничение действующего API:** PATCH /events не принимает write revision/If-Match. Предварительный GET и сравнение base snapshot выявляет уже случившийся конфликт, но не устраняет гонку GET→PATCH. Dashboard revision этого не исправляет. При обнаруженном расхождении сохранить обе версии и предложить выбрать/объединить; безусловный автоматический replay PATCH запрещён. Для гарантии отсутствия lost update на двух устройствах нужен backend conditional write + различимый 409/412; это отдельная интеграционная доработка, а не уже существующий контракт. Без неё план допускает явное сохранение по серверной last-write-wins семантике с документированным ограничением, но не объявляет строгую multi-device защиту выполненной. Выбор/merge — часть итогового UI, не межэтапный вопрос пользователю.

### ADR-T10-07: документы и минимизация данных

Remote документ — descriptor/временный приватный файл/поток плюс account generation, request и MIME, не сериализованный workbook/bytes в VM. Скачать с текущей авторизацией, проверить размер/MIME/ошибку до пользовательского сохранения, затем существующий SAF. Отмена/смена аккаунта исключает запись чужого результата; pending SAF target не даёт повторно скачать файл после logout. Own guest PDF получает capability только из живого flow. Cache временный, bounded и очищаемый; никакой постоянной копии всех анкет/XLSX.

Domain document port допускает server download и demo generation без знания о CSV/HTTP/ContentResolver в VM. Локальные writers остаются demo implementation. Новый ticket resend удалить, не имитировать успех там, где API нет.

## 4. Последовательность этапов

Текущий срез: M00/M01 TECH_ACCEPTED, M02 FIX_REVIEW_PENDING, M03 TECH_ACCEPTED по подтверждению пользователя 17 ноября, M04 IMPLEMENTED/REVIEW_PENDING, M05–M09 TODO. В каждой строке обязательны отдельный завершённый diff, релевантные проверки, обновление текущих страниц и ревью. Коммит фиксировать перед ревью, рабочую базу/посторонние изменения явно перечислить; не коммитить весь текущий dirty tree автоматически. Push не входит в план без команды.

| Этап | Цельный результат / область | Минимальные доказательства выхода |
|---|---|---|
| **M00: актуальные правила и карта контракта** | Снять устаревшие запреты autoaccount/verified-only/закрытия регистрации из текущих инструкций и проверочных чеклистов, согласовать reviewer scope с новыми решениями. Сохранить старые отчёты как историю. Зафиксировать sanitized OpenAPI snapshot 1.1.1 и mapping всех существующих product ports на реальные операции/поля; список отсутствующих полей, статусов/ошибок и test fixtures. Новые endpoint IDs не равны старым 44 | Документационные проверки; каждый метод существующих Auth/Event/Registration/Ticket/CheckIn/Volunteer/Attendance/Profile/Documents помечен keep/change/remove с backend operation. Нет кода и нет ложных DONE. Ревью M00 — только согласованность документации, не фиктивное ревью приложения |
| **M01: transport + реальная сессия** | Добавить remoteDebug/data:remote, минимальный HTTP/serialization/error mapper и TokenStore. Только auth/signup/login/logout/GET me/consent; адаптировать domain+fake+Auth UI+SessionCoordinator. Выключить обязательный verify gate, убрать resetCode/resetGrant, browser mail flow. Remote служит рабочим срезом login → profile, ещё не объявляется полноценным MVP | demo+remote assemble/lint/DI/isolation; JVM transport с локальным HTTP test server: 200/204/401/403/422/429/5xx/malformed/timeout/cancel. Device storage: restart, key invalidation, backup exclusions; late401, logout offline, stale request, signup создаёт session; никаких secret logs |
| **M02: событие и модель анкеты** | EventId/slug, LIVE/effectiveEndsAt, capacity, доступ unverified, server summary/details/viewer, все Question/Answer types и form renderer. В том же срезе обновить editor/fake/codec consumers расширенных моделей, сохранить стабильные IDs. Закрытие регистрации убрать из UI/port, без изменения смыслов неизвестных полей | Public GET по slug; различие notfound/network/finished/full; live доступен; min2 options; boolean/string/list mapping; unsupported type/nullable draft safe. Domain/fake regression и миграции старых schema fixtures проходят, старый QR не падает от модели |
| **M03: заявки и билеты, первый полный путь** | Remote submit guest/auth, idempotency, accountCreated без auto-login, EMAIL_TAKEN без автосохранения анкеты. Списки upcoming/past/own ticket, self cancel, guest receipt state/capability. Новое поведение QR/status/code; guest PDF как первый серверный document download через SAF, без ожидания M08 | End-to-end login → E → submit → QR/PDF, guest путь, двойное нажатие, потерянный ответ/повтор одним key, process death/close flow, чужой ticket/capability, поздний ответ после logout. Существующие SAF recreation guards сохранены; userId ownership, Used/Cancelled display по server policy |
| **M04: remote editor/обложка/программа** | Draft CRUD через существующие POST/PATCH/read/publish, manual finish, nullable поля; upload/attach/detach обложки, program IDs, редактирование вопросов после регистраций. Убрать старые server-like expectedRevision/formVersion/lock promises; fake следует той же политике | Неполный draft сохраняется, publication validation, PATCH omission≠null, default4h/явный end, повтор publish/finish, foreign asset, file errors; historical answers не переинтерпретируются. UI/VM редактора и demo работоспособны |
| **M05: Room draft и восстановление** | Добавить data:local/Room: snapshot+pending, local→remote mapping, upload pending cover, foreground sync/conflict flow по ADR-T10-06. Схемы/миграции отдельно от demo codec; account partition и cleanup | Offline edit/restart, refresh не затирает dirty, timeout create не даёт duplicate, конфликт сохраняет обе версии, account switch/logout isolation, DB migration. Условный PATCH при наличии backend; иначе явно записанный residual risk без объявления строгой multi-device защиты |
| **M06: scanner и волонтёры** | EVT parser вместо derived ManualCode, реальный QR check-in, sparse result/replayed, server access и grant/revoke/publicId/email, счётчики. Никакого offline allowed. Сохранить CameraX/lifecycle/back ownership | Один новый allowed при двух scanner, used/cancelled/wrongE/finished/429, revoke во время запроса и после resume, replay не открывает ворота снова; отсутствие checkerId не превращается в invented UserId. Camera/perms device checks адресные, не полный автоклик UI |
| **M07: dashboard и owner cancel** | Stats/page/revision/search, единый согласованный refresh, STALE_PAGE recovery, sparse Attendee, ticketId cancellation, totals, volunteer count. Связать результаты M06 с read invalidation | Устаревшая вторая страница не дописывается к новой; query/E/session guards, totals без смешения revision, owner cancel USED/foreignE, cancel/scan race, refresh только видимого ресурса, hidden polling отсутствует |
| **M08: профиль, удаление, XLSX и удаление resend** | Полный PATCH me включая email/preference, общий Account update; DELETE cascade confirmation без password; server XLSX через document port M03. Полностью убрать ticket resend UI/port/fake product action, demo writers оставить demo-only | Смена email не теряет свои билеты, unverified session остаётся; delete success/401/timeout/нет сети, полная локальная cleanup и late result guards; XLSX owner auth/MIME/SAF, исторические ответы не собираются из текущей анкеты. Каскад проверять только на синтетических данных |
| **M09: интеграционная стабилизация** | Полная сверка mapping M00 с кодом, remote/fake parity принятых правил, миграции legacy schema, release-конфигурация без debug leakage, security/accessibility/network hardening, один итоговый APK и checklist | Все необходимые JVM/transport/device tests, demo+remote build/lint/изоляция, contract/staging targeted tests, secret/PII и backup audit, OS process restore, размер/скорость больших списков, release build при готовой конфигурации. Финальное ревью всей совокупности diff, затем READY_FOR_FINAL_HUMAN; human acceptance только после подтверждения пользователя |

Не связывать этапы незавершёнными API: если remote binding ещё не подключён, функция явно недоступна в частичной remote сборке, а не подменяется fake/успехом. До M03 частичный APK — технический, не готовый продукт. Ни один этап не оставляет domain consumers некомпилируемыми ради будущей задачи.

### Покрытие существующих HTTP-операций этапами

Все пути ниже относительно `/api`, по сохранённой live OpenAPI 1.1.1: **33 операции на 30 путях**. Это распределение уже существующих ручек, не требование новых URL. Полные DTO/optional/error mapping уточняются на M00 и проверяются соответствующим срезом.

| Владелец | Операции |
|---|---|
| M01 auth/session | POST `/auth/register`, `/auth/login`, `/auth/logout`, `/auth/resend-verification`, `/auth/forgot-password`, `/auth/email-link`; GET `/auth/consent`, `/me`. Email-link для remote временно исполняется web-страницей, Android не перехватывает token |
| M02 public E | GET `/events/{slug}` |
| M03 registration/tickets | POST `/events/{event_id}/registrations`; GET `/me/tickets`, `/tickets/{ticket_id}`, `/tickets/{ticket_id}/pdf`; POST `/tickets/{ticket_id}/cancel` |
| M04 editor | GET `/events/by-id/{event_id}`, `/me/events`; POST `/events`; PATCH `/events/{event_id}`; POST `/events/{event_id}/publish`, `/events/{event_id}/finish`; POST `/assets/event-covers`, GET `/assets/event-covers/{asset_id}` |
| M05 local | Новых HTTP-ручек не требует; использует editor операции M04. Conditional write — отдельное необходимое расширение только для строгой гарантии от lost update |
| M06 scanner/volunteers | GET `/events/{event_id}/scanner`; POST `/events/{event_id}/scan`; GET/POST `/events/{event_id}/volunteers`; DELETE `/events/{event_id}/volunteers/{volunteer_id}` |
| M07 dashboard/cancel | GET `/events/{event_id}/stats`, `/events/{event_id}/attendees`; POST `/events/{event_id}/attendees/{ticket_id}/cancel` |
| M08 profile/files | PATCH/DELETE `/me`; GET `/events/{event_id}/attendees/export?format=xlsx` |
| M09 | Новых ручек не требует, проверяет совокупность |

## 5. Обновление контекста между этапами

Перед ревью этапа исполнитель обязан:

1. В 06 поставить только фактический статус текущего M, в отчёте этапа указать base/target commit, APK/version/hash при выдаче, точные проверки и ограничения.
2. Переписать изменившиеся текущие разделы 02/responsibilities (модули и владельцы), 05/operations/invariants (порты, outcome, commit/retry), 03/state-ownership (routes, saved state, cleanup), 01/08 (правила/ADR). Заполнять только затронутые поверхности, не перечитывать и не дублировать весь архив.
3. Для persisted изменений обновить schema/migration matrix и fixtures; не считать успешный запуск на пустой базе миграционным тестом. Legacy demo v1–v5: что сохраняем, что переводим, что больше нельзя выразить — явно описать; не подставлять случайную capacity для старого Unlimited. Допустим явный versioned demo reset с объяснением, без удаления remote pending данных.
4. В отчёте handoff оставить короткий блок: «текущее поведение; действующие ADR; изменённые классы/порты; что удалено; ограничения backend; что следующий агент может/не может менять; следующий M и review SHA». Тестовые числа держать в отчёте, не размножать по всем схемам.
5. Запустить `python scripts/verify-architecture-docs.py`, `git diff --check`, проверки соответствующего этапа. Обновить ссылки 00. Ревьюер сверяет не только diff кода, но и точность обновлённого контекста; старые записи CODE_REVIEW.md сохраняются.

При запуске следующего агента давать номер **Mxx из этого документа**, base commit и последнее ревью, не только «выполняй T10». Минимальный маршрут: AGENTS → README → 08 → 06/T10 → нужный M этого файла → относящиеся страницы architecture → код. Прежняя команда на последовательные этапы не отменяет текущую передачу M02 и запрет субагентов от 15 ноября. Независимое ревью проводится отдельной последовательной задачей после отчёта и фиксации проверяемого состояния; исправления находок разрешены в пределах соответствующего этапа. Этот WIP-коммит не является review gate.

## 6. Внешние условия и финальная проверка человеком

Начало M01 не блокируют: отсутствие настроенных App Links, credentials общего seed, полная ручная приёмка прежнего T09a. Нужны HTTPS staging и актуальная схема; перед каждым сетевым этапом проверить drift версии, не переносить выводы 13 ноября на новый сервер автоматически.

До финальной ручной приёмки получить рабочие URL terms/privacy и доступ к тестовым письмам; подтвердить deploy SHA/версию, выделенные account/event fixtures. Conditional PATCH нужен для строгой защиты concurrent draft edits; без него остаток явно согласовать как ограничение перед финальной поставкой, не скрывать в README. Утрата доступа к staging блокирует только реальные интеграционные доказательства, локальные transport/contract tests могут продолжаться.

Единственная итоговая ручная сессия: guest/signup/login/verify/setup/reset через браузер; E live/finished/full; анкеты всех поддержанных типов; guest QR после recreation/закрытия; свои билеты/Used/PDF; draft offline/conflict/cover/program/publish/finish; права на разных E и два scanner; dashboard/cancel/XLSX; profile/email/logout/delete с последствиями; pull-to-refresh адресность T09a; Back/rotation/process death/IME/fontScale/TalkBack. Проверять на одном конкретном финальном APK с номером/hash и записать устройство/Android. Если найдена ошибка: исправление → автоматические проверки → ревью → перепроверка затронутых сценариев на новом APK; это не возврат к ручным gates каждого M.

## Основания технического выбора

- [Android data layer/offline-first](https://developer.android.com/topic/architecture/data-layer/offline-first): repository объединяет remote/local источники; для этого приложения offline объём ограничен draft, не всеми серверными командами.
- [Room](https://developer.android.com/training/data-storage/room): structured storage и schema migrations; конкретную совместимую версию выбираем при реализации.
- [Android security guidance](https://developer.android.com/privacy-and-security/security-tips): приватное хранение и защита сетевых данных. Политики TokenStore/backup/cleanup выше — решения нашего плана, не утверждение об уже существующей защите demo.

M00 актуализирует инструкции и [mapping](api/migration-mapping.md) по исходному плану. В M00 исходники, Gradle, зависимости и CODE_REVIEW.md не изменялись; тесты приложения не запускались, результаты [33](33-backend-update-2026-11-13.md) не выдаются за новый прогон.


### ADR-T10-08: отдельная сводка билета и transient document source (M03)

TicketEvent не содержит organizerId/questions/permissions: введён TicketEventSummary, регистрационный ID remote отсутствует (null), OwnTicketResult не требует фиктивного Event. Server scope/counters/status определяют группировку и QR; client clock применяется только к demo Event. DocumentSnapshot.Remote содержит transient DocumentSource; bounded PDF bytes существуют только внутри data-download, platform пишет private temporary и затем SAF с повторными owner checks. Не сериализовать источник или токены. Guest capability — только текущий receipt owner. Подробности/ответственность — [M03](architecture/t10-registration-tickets.md).

Прямое поручение пользователя 16 ноября «приступай к м03» отменило ожидание повторного M02 fix-review для этого запуска. Это не подтверждение приёмки M02, не отмена последующего ревью M03 и не разрешение агенту закрыть ручные H-пункты.

### ADR-T10-09: online owner projection и явный retry (M04, 17 ноября)

Server MyEvents возвращает typed summary только выбранного tab; nullable draft не превращается в выдуманный published Event. Remote revision/preconditions отсутствуют (null), terminal задаёт серверный read. Editor VM хранит pending create body в памяти, saved state — только marker/local ID/подтверждённый server ID. Adapter хранит ограниченный fingerprint/key/context/retention/cooldown; неизвестный create без тела после process loss не отправляется заново. PATCH включает лишь изменённые поля, null отличается от omission; uncertain PATCH/publish/finish требует read-сверки. Conditional write и durable offline merge не заявлены, остаются M05. RemoteCoverTransfer выполняет один upload с managed source и finally release, затем explicit attach/null detach; preview читает assetId через API origin без bearer на произвольный URL. [Текущие owners/ports/guards/tests](architecture/t10-editor-covers.md).

## Фактическое решение M05

ADR-T10-03/05/06 реализованы узким draft store, без универсальной очереди. [Schema1/owners/commit/retention](architecture/t10-room-drafts.md). Account partition и lease/incarnation CAS отделены от auth; confirmed/base/pending и immutable create command durable. Локальная ревизия не server lock. Source обложки noBackup, descriptor записывается до attach. Остаточный GET→PATCH race и неизвестный upload сохранены как ограничения. M05 IMPLEMENTED/REVIEW_PENDING; пользователь разрешил M06 без ревью. M06 IMPLEMENTED/COMPILE_VERIFIED, поведение NOT_VERIFIED.

## Фактический M06, сокращённый режим

Настоящие scanner/scan/volunteer операции подключены за прежними ports, sparse receipts/EVT/key retry и actor scope описаны в [текущей архитектуре](architecture/t10-scanner-volunteers.md). Проверена одна remote compilation; suites/lint/demo/staging/APK/device/review не выполнялись. M05/M02 gates не закрыты задним числом; M07 самостоятельно не начат.

## M07 — согласованное чтение и ticket cancellation (17 ноября)

Stats/page публикуются одним revision-bound snapshot; server revision не editor If-Match. Отсутствующие attendee registration/time/checker не выдумываются, исторические answers не перекодируются через текущие questions. Отмена по ticketId подтверждается backend; связанные read инвалидируются адресно. Query может содержать PII и остаётся только у живой VM. [Спецификация M07](architecture/t10-dashboard-attendees.md). Только compile подтверждён, M08 не начат.
