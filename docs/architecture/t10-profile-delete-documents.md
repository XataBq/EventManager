# M08: профиль, удаление аккаунта и серверные документы

**IMPLEMENTED/COMPILE_VERIFIED; поведение NOT_VERIFIED.** [Проверка и NOT_RUN](../validation/t10-m08-implementation.md), [локальный API mapping](../api/migration-mapping.md). M08 продолжает M07 без review gate по команде пользователя. Старые M02/M05 review и H-пункты не закрыты, M09 не начат.

## Профиль и Account

[RemoteAuthRepository](../../data/remote/src/main/kotlin/ru/eventpass/data/remote/RemoteAuthRepository.kt) остаётся единственным владельцем подтверждённого User и bearer. Profile содержит ту же Account(id/email/emailVerified), редактируемые поля и publicId. ProfileFields.email — кандидат ввода; в подтверждённом Profile он обязан совпадать с Account.email. UserDto.profile сохраняет все поля User, включая notificationsEnabled=false.

PATCH `me` строится по разнице с captured подтверждённым User: только fullName/email/phone/organization/notificationsEnabled. JsonNull явно очищает optional phone/organization, omission сохраняет старое, false не теряется. Remote policy допускает непустое имя до300 code points, email validation, phone до50 и organization до300 без управляющих символов. Legacy demo phone policy остаётся отдельно, email там read-only.

Response User проходит actor/id/validation guards, затем обновляет canonical User, Session.account и profile projection. SessionId/epoch/userId сохраняются: email/unverified изменение не вызывает logout, destructive purge или новый private owner. Profile observe фильтрует промежуточное несоответствие двух projection одному Account. profileVersion запрещает старому GET/refresh принять прежний User после PATCH; accountOperations Mutex ограничивает save/delete. Общий HTTP gateway по-прежнему отзывает только текущий bearer401.

[ProfileViewModel](../../app/src/main/kotlin/ru/eventpass/app/feature/profile/ProfileViewModel.kt) хранит raw/dirty только в памяти entry. Edit revision позволяет вводу B пережить поздний save A; observer/refresh не стирают dirty. Busy блокирует второй save, поля при save остаются редактируемыми. Configuration recreation сохраняет живую VM/команду; background отменяет операцию с owner/finally guards, не стирая raw ввод. Email conflict, validation,403/401, cooldown и transport типизированы. Remote ProfileEditKey использует общий P03 ProfileRoute с email/publicId/delete, без demo tools.

## Delete и общий cleanup

ProfileRepository добавляет typed deleteAccount/reconcileDeletion. Confirmation объясняет каскад account/created events/билетов всех участников на эти события. Password и server preview не добавлены. AuthHttp требует **HTTP204**, пустой ответ не разбирается как JSON. Только этот ответ означает подтверждённое удаление. Network/Timeout/503/неоднозначный protocol → Uncertain; сохранённая работа не purge как будто success уже доказан. Явная сверка GET me возвращает Present или AccessRevoked;401 — утрата доступа, а не доказательство серверного каскада. Повтор DELETE только после явного решения, не фоновой очередью.

`retire` — общий путь explicit logout и подтверждённого delete: до cleanup отзываются epoch/acceptance и private session, очищаются bearer/Keystore через существующий SessionTokenStore, затем вне auth lock вызывается прежний account-scoped callback M05. Callback инвалидирует private export epoch этого account, удаляет его Room pending/command/mapping и освобождает managed covers без ссылок других accounts. Room напрямую не удаляется, schema не меняется. Scoped late response не создаёт строки/Session заново; чужой текущий actor не завершается старым delete reply. Storage cleanup failure переводит retired generation в Recovery, не обещает полную локальную очистку.

SessionCoordinator уничтожает private/root/hidden VM owners и jobs. Remote cache owners зарегистрированы узкими in-memory cleanup callbacks у auth; они снимают projections/attempt metadata при закрытии private session, не при profile update. Callback не выполняет HTTP/SQL и не является product event bus. Auto401 сохраняет прежнюю Room retention policy M05; пользовательский logout/delete используют scoped purge. New session и files другого account не удаляются старым account cleanup.

Delete uncertainty живёт только в текущем auth/profile процессе; после process loss успешность удаления по отсутствию session не выводится. При переходе в guest подтверждение «удалено» не подменяется обычным401. Cold /me и дальнейший безопасный вход — прежние auth правила, не новый механизм восстановления токенов.

## XLSX / PDF / SAF

[RemoteTicketDocuments](../../data/remote/src/main/kotlin/ru/eventpass/data/remote/RemoteTickets.kt) поддерживает прежний server TicketPdf и AttendanceXlsx через DocumentSnapshot.Remote/DocumentSource. XLSX URL содержит **`format=xlsx`**, без dashboard query/page; полный состав/history формирует backend. VM не получает workbook/DTO/bytes. Download ограничен API origin; redirects/mutation retries не включаются.

До пользовательской записи проверяются MIME и размер **32MiB compressed**. [XlsxFormat](../../data/remote/src/main/kotlin/ru/eventpass/data/remote/XlsxFormat.kt) проверяет ZIP directory/EOF/CRC, обязательные OOXML части, XML namespaces/content type и well-formed XML с отключёнными DTD/external entities; лимиты10k entries/128MiB inflated/64MiB part/2MiB metadata. ZIP64/multipart не поддерживаются. JSON/HTML/truncated/неподходящий файл → Protocol, не успешный XLSX. Проверка идёт вне main с cancellation checks, без логирования содержимого.

Server owner проверяется при download и повторным M04 owner GET после него. Captured context guard действует перед записью, после скачивания и при каждом chunk SAF через existing DocumentStreams. Это не атомарный server lease на всю запись: между последней проверкой и последующими bytes остаётся обычное окно конкурентного изменения прав. Runtime revoke/401/logout/account change прекращают использование прежнего context; сценарно это не проверено.

[AndroidDocumentFiles](../../core/platform/src/main/kotlin/ru/eventpass/core/platform/AndroidDocumentFiles.kt) готовит private temp, затем копирует в content URI с guards и finally cleanup. Temp prefix — hash account, purge ограничен этим prefix и private каталогом (до256 удалений за проход), отдельный account epoch запрещает позднюю запись. Нельзя удалить SAF-файлы пользователя; provider не обещает rollback уже переданных bytes, возможна неполная пользовательская копия при позднем отказе. Startup sweep прежних orphan files сохранён. SAF picker/recreation owner из M03/T09 не заменён.

Local TicketPdfWriter/AttendanceXlsxWriter перенесены в app/src/demo и передаются renderer callback только DemoModule. Remote platform binding не имеет local renderer/fallback при server error. [Demo writers](../../app/src/demo/kotlin/ru/eventpass/core/platform/AttendanceXlsxWriter.kt) не выдаются за реальный server export; совместимость demo не проверена.

## Ticket resend и остаток

Product resendTicket/ResendResult удалены из domain, VM/UI/queue/cooldown, remote unavailable и fake product operation. Runtime ticketResendAt/FakeDocumentsPolicy и две устаревшие проверки удалённой функции убраны, fake codec6/QR/legacy proofs не переписывались. Первоначальное ticket email/outbox и auth verification/reset resend остаются. Удаление операции — не отключение этих писем.

Фактически выполнены только remote Kotlin/Java/DI compile и структурные документы/whitespace. Profile races/delete unknown/cascade/cleanup/account switch, XLSX corruption/MIME/bounds/XML runtime/SAF/recreation/revoke и сохранённые PDF/M03–M07 регрессии **NOT_VERIFIED**. Ни одного destructive server вызова не выполнялось. Следующие проверки/M09 — только по отдельной команде.
