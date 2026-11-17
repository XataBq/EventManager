# M04: online редактор, MyEvents и обложки

Объём — online create/read/PATCH draft → publish → edit/finish на общих UI и ports. M03 принят пользователем на `cec5520`: [подтверждение](../validation/t10-m03-user-review-confirmation.md). M04 не закрывает M02 fix-review, M05–M09 и ручную приёмку. Команды/evidence/остаток — [отчёт M04](../validation/t10-m04-implementation.md).

## Модель и authority

`MyEventsTab` адресует отдельный server tab. `MyEventSummary` содержит id/slug, server status, nullable startsAt/ends/capacity, counts/location; это не Event с выдуманным owner. `MyEventsResult.Ready.tab/items/roles/counters` — подтверждённая projection одного tab. Старые organizing/volunteering/drafts массивы остаются для aggregate demo adapter. Незагруженный tab у VM имеет content=null/loading=true; загрузка одной вкладки не выдаёт остальные за пустые. Relation вкладки и server roles используются вместе, глобального User.role нет. Details не добавляются в list items.

Incomplete `EventFields` сохраняет null даты/capacity и стабильные IDs вопросов/options/sections. Все десять типов, help/placeholder/option hints кодируются в data:remote. Draft questions не обязаны быть готовы к публикации. Published поля и окончательную возможность записи проверяет сервер. `EventDraft.revision`, Event.revision/localSchemaRevision и expected revision nullable: remote null, локальный fake сохраняет положительные версии/проверки. PATCH не отправляет этих preconditions. ZoneId — форматирование Android, не серверное поле. PriceType free/paid — metadata без оплаты.

## Владельцы и жизненный цикл

MyEventsViewModel владеет выбранным tab в SavedStateHandle, readiness/content/error по вкладке, transient cache и single-flight refresh. Tab switch отменяет прежнее чтение и инвалидирует его owner/finally; pull читает только выбранный tab. Demo aggregate не заставляет загружать скрытые HTTP вкладки. Private denial очищает cache/данные.

EditorKey владеет raw EditorInput/dirty/busy, command generation и отдельным read generation. Dirty ввод не заменяется observer/refresh. Visible + RESUMED владеет чтением; hidden/background отменяет чтение/обновление и мутацию. При configuration change живая VM сохраняет ввод и текущую операцию. Pull редактора адресован его target, single-flight, блокируется busy/pending/confirmation. Back — save/discard/stay без autosave. Logout/401/403/смена actor не публикуют старые результаты; repository применяет captured context перед HTTP и перед commit projection, VM — перед command result/finally. Private owner M01 уничтожает старые entry stores.

Локальная draft identity и подтверждённый server ID различаются. RemoteOrganizerRepository держит их transient mapping в памяти. После подтверждённого create VM записывает только server ID и исходный local ID в SavedStateHandle entry; после process recreation читает этот server draft, не создаёт новый. Это не Room, не offline mapping и не сохранённая анкета. Подтверждённые draft fields читаются с сервера.

## Операции и retry

RemoteEventRepository сохраняет public GET и делегирует owner-операции RemoteOrganizerRepository через существующий EventRepository. AuthHttp/RemoteAuthRepository — единый bearer/epoch/401 gateway. GET owner — `events/by-id/{id}`, список — `me/events?tab=...`. HTTP остаётся в data:remote; VM/Compose не видят DTO/token.

`beginDraft` регистрирует новую локальную identity без POST. Явный save создаёт `POST events` с publish=false. Pending create: одна команда → один key + SHA-256 fingerprint тела/срок/context; само тело остаётся у живого VM. Неопределённый исход блокирует редактирование; явный повтор использует прежние поля/key. Single-flight и double press guards у VM, per-resource Mutex у adapter. Срок replay ограничен 24h, 429 сохраняет retryAt; HTTP не повторяет мутации. Pending metadata ограничена 32 командами. Marker без тела после process death блокирует новый POST и предлагает перечитать черновики. Угадывания identity по title нет.

После известного server ID PATCH сравнивает текущие поля с подтверждённым baseline. Отправляются только изменённые ключи. `endsAt:null` снимает explicit end, `coverAssetId:null` снимает обложку; отсутствующий ключ сохраняет поле. JSON body сохраняется при явном method=PATCH; DELETE без тела и POST/key не меняются. Raw неверная дата/число дают validation, не превращаются молча в null.

Publish/finish работают по уже известному server ID. После неопределённого PATCH/publish/finish adapter требует явный успешный owner read перед следующей записью. Это сверка состояния, не безусловный replay. Dirty fields остаются у VM. Сервер не предоставляет conditional PATCH/write revision: строгая multi-device lost-update защита не заявлена. Полноценный merge/conflict UI и durable pending — M05.

## Обложка

Local asset ID/URI не отправляются как server coverAssetId. AndroidEventCoverStore ограничивает content import JPEG/PNG 5 MiB, проверяет bounds, декодирует preview с downsample вне main. CoverAssetReader читает только управляемый private asset с ограничением размера. RemoteCoverTransfer захватывает actor до импорта, выполняет один multipart `file` upload и проверяет context после ответа. Imported EventCover несёт отдельный remoteAssetId; VM явно сохраняет его в EventFields для последующего attach. Detach — явный null. До save upload не прикрепляет картинку к событию.

Upload не имеет idempotency/retry; потерянный ответ может оставить orphan asset. DELETE asset/event/draft не выдуман. Управляемый локальный файл освобождается в finally, включая cancel/logout. Ошибка не заменяет прежнюю обложку и не выдаётся за успешное сохранение. Поздний upload после смены actor/event не попадает в другую форму.

Preview выполняет bounded GET только по `assets/event-covers/{assetId}` через настроенный API origin, с текущим bearer для private asset. Произвольный coverUrl не получает Authorization. Redirect/retry отключены; MIME/размер проверяются до bitmap decode, preview bounded до 1024 вне main. После logout старый context не публикует bytes. Guest может прочитать опубликованную обложку только по решению сервера. Упавший/неизвестный preview не меняет event fields.

## Навигация, хранение и граница M05

Remote shell открывает общие MyEventsRoute и EditorRoute. DraftId, internal eventId и public slug не подменяются друг другом. Публичный переход/copy/share использует HTTPS `/e/{slug}`, не demo URI. Editor result остаётся в своей entry. Scanner/Volunteers/Dashboard в remote остаются явно unavailable до соответствующих M.

Схема fake snapshot остаётся 6; nullable remote versions/remote cover metadata не записываются в fake codec как выдуманные значения. Fake writers продолжают писать положительную revision; legacy fixtures сохраняют прежние байты/правила. Local cover release относится к remote upload, не удаляет сохранённые demo assets. Shared registration/ticket flows M03 не переписаны.

## Проверки

- RemoteOrganizerRepositoryTest: incomplete create/read/PATCH, null/omission, стабильные typed questions/sections, lost response key/body, scoped list/counters, late logout, publish/finish, обязательная read-сверка после uncertain PATCH, DELETE compatibility.
- RemoteCoverTransferTest: multipart/server ID, игнорирование чужого URL, MIME/size/redirect, guest/missing managed asset, cancel/late actor и release.
- M04EditorStateTest: unknown create/double press/immutable поля, restored unknown marker, подтверждённый server ID, dirty input через pause/resume, Forbidden cleanup.
- M04MyEventsStateTest и RefreshOwnersTest: readiness, late old tab, visible scoped refresh, single-flight/late finally. Прежние EditorViewModel/M03/RemoteTickets/RemoteEvent/fake/codec suites сохраняют совместимость.

Список выполненных проверок и NOT_RUN — в отчёте; эти указатели не являются обещанием device/staging/ручного прогона. Новые APK не собираются.
