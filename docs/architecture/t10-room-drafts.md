# M05: Room-черновики и явная синхронизация remote

Статус реализации и точные проверки — [отчёт](../validation/t10-m05-implementation.md). M04 на `8da678a` принят пользователем при запуске M05; повторного ревью автор не проводил. M05 IMPLEMENTED/REVIEW_PENDING, не human acceptance. По последней команде проверена только компиляция remote; сквозные сценарии и lint не выполнялись. Это текущая спецификация draft-пути, заменяющая transient draft cache M04 в remote DI.

## Границы и источники истины

| Владелец | Ответственность |
|---|---|
| domain DraftStore / DraftStorage | Узкая storage-граница, typed local state/commands/conflict; без Android/Room/DTO |
| data:local DraftDatabase / RoomDraftStore / DraftCodec | Account-scoped schema1, короткие SQL-транзакции, сохранённые snapshots/commands, lease + incarnation + storageVersion CAS |
| data:remote LocalDraftRepository | Локальный save, явный bounded foreground sync, server mapping/ACK/reconcile/conflict, cleanup references |
| RemoteOrganizerRepository | Серверные tab summaries, owner read, published update/finish; legacy draft implementation не используется remote DI |
| RemoteAuthRepository | Только проверенная session/profile даёт local доступ; captured context/epoch/401 gateway, explicit logout callback |
| AndroidEventCoverStore / RemoteCoverTransfer | Durable content copy/preview/read/release в platform; HTTP upload/GET/server descriptor в remote |
| EditorViewModel / MyEventsViewModel | Raw input и dirty у entry, отдельные busy/syncing/read owners; local status и local projection отдельно от server success/items/counters |

RemoteEventRepository сохраняет M02 public reads и делегирует owner-операции координатору. Domain/VM/Compose не знают SQL/HTTP DTO. App remoteImplementation включает data:local; demo DI/fake codec6 не переведены на Room. Verification-only JVM module компилирует те же production DAO/schema/store с настоящим Room/Bundled SQLite, а не имитацию DAO.

## Сохранённые данные и atomic boundaries

DB schema **1**: DraftAccountRow(accountId, generation, enabled), DraftRow composite PK(accountId, localDraftId). В строке — incarnation/storageVersion/localRevision, nullable remoteId/slug, confirmed/base/pending JSON, sync state/problem/invalidFields/retryAt, immutable command body/key/fingerprint/revision/firstAttemptAt, remoteConflict, pending cover reference и uploaded descriptor, serverStatus/finishedAt. Snapshot codec сохраняет nullable dates/capacity, Unicode, stable question/option/section IDs и metadata. Remote revision остаётся null; localRevision не If-Match. Нет formVersion/questionsLocked.

activate создаёт новый storage lease для проверенного account. replace/delete требуют активную generation и совпадающие version/incarnation; поздний update не upsert. purge атомарно отключает lease и удаляет account rows. HTTP/file IO не держат DB transaction. Публикация server mapping/confirmed/base/ACK — одна CAS-транзакция; ACK A сохраняет pending B при большей localRevision. Exact-CAS применяется к решениям, основанным на конкретном GET/base. Raw UI ввод сохраняется только явно, invalid дата/число не преобразуется в null.

Index(accountId, remoteId) не unique: импортированный server draft и ещё неизвестная create-команда могут временно обозначить один remote ID. findRemote предпочитает pending/local revision. Подтверждённые локальные копии дедуплицируются по remote ID, разные pending ветви сохраняются, server items/counters не модифицируются локальным содержимым.

Schema экспортирована для Android/JVM, destructive fallback нет. Предыдущей установленной Room schema0 нет; fake product v1–v6 — отдельный формат, не migration source. DB и обложки находятся в noBackupFilesDir, не участвуют в backup/device transfer. Room не хранит bearer/password/setup tokens, guest QR/capability, ответы участников, credentials сканера или exports. Command body содержит только определение собственного draft.

## Состояния и переходы

| Состояние | Причина / commit | Следующее действие |
|---|---|---|
| UNSAVED | beginDraft регистрирует identity без IO/HTTP | Явно сохранить |
| LOCAL | pending/create command надёжно записаны; UI «Сохранено на устройстве» | Явно синхронизировать |
| SYNCING | command и первый attempted-send timestamp записаны до HTTP; runtime owner активен | Дождаться/cancel, локальная правка B может сохраняться параллельно |
| SYNCED | Server snapshot/mapping/ACK приняты, pending нет | UI «Сохранено на сервере» |
| UNCERTAIN | Отмена/потерянный ответ/перезапуск без активного sync owner | Явный retry/reconcile; без автоматического POST |
| CONFLICT | GET отличается от base/status; сохранены base, local, remote | Accept remote / keep local / merge, затем отдельный sync |
| PUBLISHED | Подтверждённый server status не draft, pending нет | Receipt/open по canonical slug; online published editor |

saveDraft всегда local-only. Существующая create-команда A не меняется при сохранении B. sync берёт per-account/draft Mutex и выполняет максимум четыре шага текущего draft: create, optional upload, PATCH, один последующий pending. Автоматической очереди, polling скрытых drafts, WorkManager, offline registration/check-in/publish нет.

Create replay использует сохранённые body/key, не перевалидирует по новой форме и не сериализует B вместо A. firstAttemptAt появляется перед первым HTTP и не продлевается. После 24h неизвестного create — EXPIRED_CREATE, новый POST/key не создаётся; пользователь проверяет серверные drafts и явно решает судьбу локальной копии. По совпадению title identity не угадывается. После определённого validation rejection команда снимается; новая явная правка/save может создать исправленную команду. Неопределённый исход этого не разрешает.

Для известного remote ID перед новым PATCH выполняется GET/base comparison. PATCH содержит только изменённые ключи: omission сохраняет поле, endsAt:null снимает explicit end, coverAssetId:null снимает обложку. После неизвестного PATCH следующий sync сначала GET: совпадение с отправленным snapshot подтверждает команду, расхождение сохраняет conflict. Слепого replay PATCH нет. Rate limit сохраняет retryAt. После согласованного merge/keep-local новая команда строится относительно явно выбранного remote base; старое неизвестное тело под тем же ключом не меняется.

MergePolicy — трёхсторонний field merge с stable IDs, deletion-vs-edit/order/type конфликтами и атомарным cover intent. UI показывает варианты, предлагает объединение независимых правок и локальный вариант спорных полей для явной проверки. Анкету/блоки можно взять целиком с одной стороны, остальные поля редактируются в общем редакторе; подтверждение MERGED сохраняется локально. Terminal server состояние разрешает accept remote, не offline publish. Исторические registration answers не пересобираются.

Publish явно синхронизирует текущий сохранённый draft, требует отсутствия pending/conflict, перечитывает известный server ID и выполняет online publish. Published update/finish остаются online M04. Server status/finishedAt, а не часы телефона, определяют terminal authority. Backend не предоставляет conditional PATCH: между GET и PATCH возможна другая запись. **Строгая multi-device lost-update защита не доказана и не заявлена** (ADR-T10-06).

## Auth, lifecycle и retention

Unknown/Guest/Recovery не получают данные из Room. После cold start даже сохранённый bearer требует успешного /me; Room не создаёт session. Warm уже проверенная Authenticated identity остаётся активной для local edit при Network/Timeout/503/429 refresh; это не расширяет серверные права. Auto401 и account switch сохраняют работу приватно, но скрывают её; re-auth того же account восстанавливает доступ с новым context/lease. Другой account не видит строки/обложки/ошибки.

Explicit logout сначала отзывает auth epoch, очищает session, затем purge account с DB lease fence и освобождает его cover refs, не принадлежащие другим retained rows. Cleanup failure не объявляется успехом: Recovery(StorageUnavailable). Сетевой refresh failure работу не удаляет. Late HTTP/ACK после logout/delete не создаёт запись заново. Post-commit state/result проверяет captured context; consumer-edge sanitization закрывает queued private emissions.

EditorKey различает draftId/remoteDraftId/eventId. VM сохраняет только безопасный resolved local ID, не body/token. Raw/dirty сохраняются у живой VM при configuration change; process death восстанавливает лишь явно сохранённый pending. Background/hidden cancel reading/sync с generation guards, configuration change не отменяет активную операцию. Back save/discard/stay относится к raw вводy; отдельный discard persisted удаляет local-only строку или сбрасывает известный pending к confirmed, без server DELETE. Pull не отправляет POST и не стирает dirty/pending/conflict.

## Lifetime обложки

Importer сначала копирует content URI в managed JPEG/PNG ≤5 MiB с bounds/decode проверкой. Durable opaque local ID + metadata входят в pending. URI/bitmap/BLOB в Room не записываются. Только после известного remote event выполняется upload; server descriptor надёжно записывается до attach PATCH. Сохранённый descriptor повторно используется, raw локальный ID не отправляется как server assetId. Late upload проверяет actor и прежний local asset ID перед обновлением pending, не прикрепляет новую/чужую картинку.

Replacement/discard/ACK/logout освобождают файл лишь после DB перехода и проверки retained refs всех accounts. Orphan sweep выполняется один раз перед первым импортом процесса, до появления несохранённых файлов: только managed каталог/разрешённые имена, максимум 128 удалений. Crash до DB reference оставляет orphan до такого sweep. Upload без idempotency может оставить серверный orphan при потерянном ответе; повтор явный, exactly-once/DELETE asset не придуманы. Published online save загружает выбранный local source перед update, без offline очереди.

## Проверка и следующие действия

В раннем срезе прошли три настоящих Room JVM-теста (reopen, SQL rollback, CAS/account purge) и семь pure merge тестов. После интеграции пользователь ограничил проверку remote compile: она прошла, KSP/Hilt/Room подключение скомпилированы. Это **не** доказательство crash/recovery/logout/cover/VM сценариев. Matrix, команды, NOT_RUN и основания версий — в [отчёте](../validation/t10-m05-implementation.md). Перед M06 требуется отдельное ревью M05 с явным учётом этой нехватки evidence; ручная приёмка только после M09.
