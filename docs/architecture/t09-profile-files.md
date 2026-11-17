# Профиль, документы и demo writers после M08

Текущая remote спецификация — [M08](t10-profile-delete-documents.md), [реальная проверка/NOT_RUN](../validation/t10-m08-implementation.md). Историческая T09/SAF приёмка — [28](../28-t09-validation.md), [29](../29-t09-saf-recreation-validation.md); она не подтверждает remote поведение.

ProfileRepository использует Profile/Account, partial save и typed delete/reconcile в remote. ProfileEditKey открывает прежний P03; dirty input живёт у entry VM, save/refresh/generation guards сохраняются. Demo FakeProfileDocumentsOperations по-прежнему хранит имя в AuthData.names и optional phone/organization/notifications в profileDetails; remote canonical User находится только у auth adapter. Новая fake/Room migration ради M08 не добавлена.

DocumentsRepository имеет prepare/validate. Product ticket resend/queued/cooldown удалены, первоначальные ticket email и auth verification/reset outbox остаются. Не реконструировать resend из прежних отчётов или fixture tests.

[AndroidDocumentFiles](../../core/platform/src/main/kotlin/ru/eventpass/core/platform/AndroidDocumentFiles.kt) и [DocumentStreams](../../core/platform/src/main/kotlin/ru/eventpass/core/platform/DocumentStreams.kt) владеют private temp/content SAF, доступом до первого byte/перед chunks и finally cleanup. Remote descriptor скачивает проверенный server PDF/XLSX; VM не хранит bytes/workbook. Picker owner переживает Activity recreation, hidden entry/account change очищает pending/job. Per-account private epoch предотвращает позднюю выдачу после purge, пользовательские SAF файлы не удаляются.

Локальные [TicketPdfWriter](../../app/src/demo/kotlin/ru/eventpass/core/platform/TicketPdfWriter.kt) и [AttendanceXlsxWriter](../../app/src/demo/kotlin/ru/eventpass/core/platform/AttendanceXlsxWriter.kt) теперь только app/demo source set; DemoModule передаёт renderer callback. Они создают прежние demo документы из авторизованного snapshot; remote binding не имеет такого fallback. Не выдавать эти файлы за server export.

User-facing сохранение может оставить неполный файл при provider/позднем auth failure; rollback SAF не гарантирован. Исторические counters/clock/consent time demo не являются server fields. Прежние ProfileDocuments/DocumentsViewModel/SAF suites в M08 не запускались, две проверки удалённого resend действия убраны вместе с действием.
