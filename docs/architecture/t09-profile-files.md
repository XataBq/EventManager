# T09: профиль, документы и demo resend

Независимый срез поверх T08 `aaca508`. Q10/каскад удаления не реализован; backend/API не согласованы. T07 camera/UI и H3/H4 ожидают человека. Технические результаты и единый checklist — [28](../28-t09-validation.md).

## Ответственность и профиль

| Владелец | Контракт и реализация |
|---|---|
| Domain | [ProfileModels](../../core/domain/src/main/kotlin/ru/eventpass/core/domain/profile/ProfileModels.kt): ProfileRepository, ProfileFields/Policy; [DocumentModels](../../core/domain/src/main/kotlin/ru/eventpass/core/domain/documents/DocumentModels.kt): DocumentsRepository, DocumentSnapshot/Stamp, DocumentFiles, typed errors/resend |
| Общий fake | [FakeProfileDocumentsOperations](../../data/fake/src/main/kotlin/ru/eventpass/data/fake/product/FakeProfileDocumentsOperations.kt): прежние state/Mutex/storage/Clock, guards и snapshot; без Android runtime |
| UI | [ProfileViewModel](../../app/src/main/kotlin/ru/eventpass/app/feature/profile/ProfileViewModel.kt), [ProfileRoute/Screen](../../app/src/main/kotlin/ru/eventpass/app/feature/profile/ProfileScreen.kt); [DocumentsViewModel](../../app/src/main/kotlin/ru/eventpass/app/feature/documents/DocumentsViewModel.kt), [DocumentActions](../../app/src/main/kotlin/ru/eventpass/app/feature/documents/DocumentActions.kt) в Tickets/Dashboard |
| Platform | [AndroidDocumentFiles](../../core/platform/src/main/kotlin/ru/eventpass/core/platform/AndroidDocumentFiles.kt), [DocumentStreams](../../core/platform/src/main/kotlin/ru/eventpass/core/platform/DocumentStreams.kt), [TicketPdfWriter](../../core/platform/src/main/kotlin/ru/eventpass/core/platform/TicketPdfWriter.kt), [AttendanceXlsxWriter](../../core/platform/src/main/kotlin/ru/eventpass/core/platform/AttendanceXlsxWriter.kt) |

Account остаётся identity id/email/emailVerified; Profile объединяет её с изменяемыми полями. Единственный источник имени — AuthData.names (сохранённые signup-имена не мигрируют в конкурирующую карту). FakeProductState.profileDetails хранит только optional phone/organization/emailNotifications. Legacy default: имя из names либо пустая форма, phone/organization=null, reminders=true. Данные чужой Registration для профиля не используются. Имя проверяет прежняя AuthInputPolicy, телефон — общий RegistrationValidator.normalizeRussianPhone; организация optional до 200 символов, без control characters.

Observe/save требуют healthy snapshot и verified/current/nonexpired actor. Save запоминает auth generation до ожиданий, повторяет guard под Mutex на IO, атомарно пишет полный snapshot, затем публикует StateFlow. Account/Session/generation при сохранении имени не меняются. Disk commit — точка принятия: отменённый после записи caller может не получить UI-успех, но совершённая запись не откатывается. Ошибка записи не публикует сохранение.

Новые формы берут актуальные name/email/phone через RegistrationContact. При открытии новой формы и leaveForm используются текущие контакты. Старые Registration, Ticket.attendeeName, answers и CheckInRecord не переписываются. Организация профиля не становится ответом на вопрос organization. Настройка reminders сохраняется явно; confirm/reset/выдача билета/запрошенный resend не отключаются. Это demo default, а не утверждение серверных категорий писем. Необязательная автоматическая рассылка в fake отсутствует.

## Владение состоянием и Back

ProfileViewModel принадлежит прежнему RootTab.Profile; четвёртого таба/новой identity нет. ProfileRoute наблюдает данные только visible + RESUMED. Несохранённые fields/dirty/error/confirmation — память, без SavedStateHandle. Observer обновляет baseline, сохраняя dirty-поля. Save single-flight; owner generation защищает result/finally после stop/new owner. Hidden/background/уход уничтожают transient форму; новый процесс читает последнюю сохранённую версию. Background не является автосохранением.

Системный Back, выбор другого таба, logout и demo tools из профиля проходят requestLeave. Dirty → Save/Discard, Back/dismiss диалога продолжает редактирование. Save failure видна внутри диалога; busy не даёт закрыть/повторить. Copy ID использует Android clipboard, только публичный user ID. Logout использует прежний AppSessionViewModel/AuthRepository и private-owner cleanup. Внешние host-переходы сохраняют существующую session/navigation policy.

DocumentsViewModel — отдельный VM того же Tickets root/Dashboard entry, transient picker ID/request/session stamp/jobs/errors/messages. PII/credentials/URI/temp path не сериализуются. Route rememberSaveable хранит только случайный launch ID для доставки ActivityResult; после нового процесса отсутствие pending в VM делает результат неактуальным. Старый picker ID не совпадает с новой операцией. Смена session snapshot, hidden/resource change/dispose отменяют job и очищают state. SAF временно приостанавливает RESUMED dashboard observer; DocumentActions остаётся в header-композиции и не исчезает вместе с dashboard данными. Новый экспорт без доступного dashboard выключен. Запущенный picker не переоткрывается при recreation.

## Авторизация и файловый путь

Пользователь выбирает CreateDocument с MIME/безопасным именем → VM проверяет прежнюю сессию → DocumentsRepository.prepare получает авторизованный immutable snapshot → DocumentFiles.save генерирует private temp на IO → validate перед provider, после open и перед каждым chunk → запись/flush/close → окончательный validate → UI-успех. Ошибки типизированы; повтор — явное действие, не очередь команд. Будущий remote может заменить реализацию DocumentsRepository/DocumentFiles, без fake/DTO в Compose. HTTP jobs/polling не вводились.

PDF разрешён только своему ACTIVE/current билету; чужой, Used, Cancelled, завершённый/отменённый event недоступны по прежнему Ticket.canDisplayQr. Проверяется подтверждённая принадлежность email текущей сессии, generation, актуальный status и неизменность ticket snapshot. Документ содержит выданные title/time/zone/venue/name/displayNumber/QR/manualCode. Credential не генерируется повторно. Native PdfDocument использует переносы и дополнительные A4 страницы; QR берёт прежний TicketQrEncoder с quiet zone. История/guest receipt не получают PDF.

XLSX требует owner canExport на E и текущую сессию. Все Registration E и их tickets/answers/status/check-in взяты из одного state под Mutex, независимо от dashboard query/page. SnapshotAt — event Clock. Время регистрации явно consentAcceptedAt; Used без first record имеет пустые checkedAt/checker. Headers ответов содержат stable questionId/title, choice сохраняет label + optionId, checkbox — boolean, отсутствие — пустое. Архивный неизвестный label не выдумывается. QR/manual не входят. Два листа: участники и metadata снимка. Streaming ZipOutputStream/SpreadsheetML без desktop runtime/новой зависимости; пользовательские значения — inlineStr, без formula cells, даже при `=`, `+`, `-`, `@`. Ограничение строки Excel — 32767 UTF-16 символов, превышение даёт ошибку файла, без молчаливого усечения. Общий fake snapshot ограничен существующими 8 MiB, поэтому экспорт не является доказательством remote scaling.

Android принимает только content URI, открывает ContentResolver stream в режиме wt; broad storage permission/произвольного пути/Share/file:// нет. Picker cancellation не готовит данные и не считается сохранением. Temp UUID files в cacheDir/eventpass-documents исключены из backup, удаляются в finally; EventPassApplication запускает cleanup оставшихся от прошлого процесса файлов через app-owned IO scope. Cleanup singleton инициализируется один раз под отдельным Mutex и не удаляет текущие параллельные операции.

Граница гарантии: до первого байта после provider open повторяется доступ; во время передачи проверка идёт перед каждым chunk, после close повторяется до успеха. Уже переданные внешнему provider байты отозвать нельзя. При IO/close failure или смене доступа может остаться пустой/неполный внешний документ; UI не показывает успех, предлагает проверить выбранную папку. Удалять чужой/заменённый документ по догадке не пытаемся. Зависший сторонний provider может удерживать IO-вызов до ответа; main не занят, поздняя доставка проверяет отмену/сессию.

## Snapshot и resend

Единое описание schema 5 и v1–v4 migrations — [05](../05-data-contracts.md). Сессия/outbox/cooldown/faults остаются transient; downgrade на code 17 после первой записи v5 не поддерживается. Старые APK сохранены, автоматического reset нет.

Resend использует тот же own/current ACTIVE guard, recipient из Registration, auth generation под общим Mutex. Новый FakeTicketEmail добавляется в прежний outbox; ticket/registration/места/QR не меняются. FakeDocumentsPolicy задаёт минутный cooldown по auth Clock, retryAt возвращается в Queued/Cooldown; event Clock не двигается. UI показывает честный demo-result и сервероподобный retryAt; нажатие до него получает Cooldown, отдельного UI таймера/авто-retry нет. Cooldown в памяти общего store, хранить его между процессами как backend гарантию не обещаем.

## Доказательства и остаток

- [ProfileDocumentsTest](../../data/fake/src/test/kotlin/ru/eventpass/data/fake/product/ProfileDocumentsTest.kt): profile/new contacts/history/session/storage, v1–v4/defaults/all writers/restart/corruption, own PDF/status, owner full snapshot, resend/outbox/cooldown.
- [ProfileViewModelTest](../../app/src/test/kotlin/ru/eventpass/app/feature/profile/ProfileViewModelTest.kt), [DocumentsViewModelTest](../../app/src/test/kotlin/ru/eventpass/app/feature/documents/DocumentsViewModelTest.kt): dirty observer/Back, single flight, late result/finally/new owner, picker cancellation, logout и retry.
- [DocumentStreamsTest](../../core/platform/src/test/kotlin/ru/eventpass/core/platform/DocumentStreamsTest.kt): close/cancel/denial/partial transfer; [ProfileFilesIntegrationTest](../../app/src/androidTest/kotlin/ru/eventpass/app/ProfileFilesIntegrationTest.kt) и [ProfileRestartTest](../../app/src/androidTest/kotlin/ru/eventpass/app/ProfileRestartTest.kt): Android/provider/QR/system picker/profile/restart. Реальные запуски — в 28, наличие тестов не равно прохождению.

Q10: требуется решение об активных собственных E, чужих tickets/местах/письмах, USED/check-in audit/checker identity и повторном signup. Кнопки ложного удаления/reset нет. H3/H4, физическая камера, независимые providers и реальные backend files/delivery не принимаются автоматизацией.
