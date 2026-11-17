# T10 M08 — профиль, delete и XLSX

**IMPLEMENTED/COMPILE_VERIFIED; поведение NOT_VERIFIED.** 17 ноября 2026. Base `1a8eb321c4d99c7899dc789044868ba7f1e13d9d`; target — локальный коммит с этим отчётом. Сокращённый режим, без предварительного M07 review; M05–M07 поведение и прежние review/H-пункты не считаются проверенными/закрытыми. M09 не начат.

- Partial PATCH me: email/publicId и все разрешённые поля; null clearing/false/omission различаются, общий Account/User принят с actor/profile-version guards. SessionId/userId/epoch сохраняются, unverified не вызывает logout/Room purge. Dirty/recreation и save A/input B разделены в общей Profile VM/UI.
- DELETE me: local cascade confirmation, только204 — Deleted, неизвестный исход требует явной me сверки.401 не доказательство удаления. Cleanup переиспользует M05 account purge и session owners, добавлены scoped temp epoch и private cache callbacks. Server вызовы удаления в этой задаче **не выполнялись**.
- AttendanceXlsx через существующий Remote document → private temp → SAF, format=xlsx, без query/page. API-origin/MIME/32MiB/ZIP/CRC/OOXML/XML и cancellation guards; PDF сохранён. Local writers перенесены в app/demo, remote renderer fallback отсутствует.
- Product ticket resend удалён вместе с его состояниями/fake action/obsolete feature tests; auth verification/reset и initial ticket mail/outbox сохранены. Room/fake persisted schemas и зависимости не менялись. [Текущие owners/ограничения](../architecture/t10-profile-delete-documents.md).

## Проверка

Один Gradle compile, **exit0 / BUILD SUCCESSFUL in18s**, 45 tasks:16 executed/29 up-to-date. JAVA_HOME=`C:\Program Files\Android\Android Studio1\jbr`:

```powershell
.\gradlew.bat :app:compileRemoteDebugSources -I .gradle/m05-evidence/verified-artifacts.init.gradle -I .gradle/m01-verified-google.init.gradle --offline --console=plain --max-workers=1
```

Лог `.gradle/m08-evidence/remote-compile.log`: Kotlin/domain/remote/app, KSP/Java/DI скомпилированы без assemble. Оставшиеся safe-call/redundant-else warnings не были ошибками. Использован existing verified cache M05, новых версий/загрузок нет. Статический поиск product resend references после удаления пустой; auth resend не удалён. Однократно прошли `python scripts/verify-architecture-docs.py` (exit0:23 pages/431 links/9 Mermaid/18 mobile IDs/S01–S14) и `git diff --check` (exit0); это проверки документации, не поведения.

## NOT_RUN / риски / передача

Не запускались unit/scenario suites, lint, demo/isolation, staging, APK/test APK, device/emulator/SAF/UI и независимое ревью. Profile/session/late response, delete204/unknown401/cleanup/cascade/новый actor, PDF/XLSX bytes/corruption/ZIP/XML parser limits/revoke/recreation/provider failure и M03–M07 регрессии **NOT_VERIFIED**. Live API совместимость не доказана compilation; реальных аккаунтов/мероприятий не удалял, файлов со staging не скачивал.

XLSX limits:32MiB compressed/128MiB inflated/64MiB part/2MiB metadata/10k ZIP entries, без ZIP64/multipart. Server access GET→SAF write не атомарен; при позднем отказе provider может оставить неполный пользовательский файл, который приложение не удаляет. Temp cleanup bounded и зависит от IO/finally; повреждение хранилища/полный диск/реальное уничтожение процесса не воспроизводились. После unknown delete без успешного204 нельзя утверждать каскадную очистку сервера.

Baseline `.gradle/m08-evidence/baseline`, initial-status.txt: пользовательские AGENTS/CODE_REVIEW/00/06/08/14/34/35, prompts M04–M08 и JVM settings сохранены. Коммит только собственных изменений; dirty docs индексируются как изменения HEAD, дополнения остаются в worktree. Push не выполняется. M09/расширенные проверки — только по новой команде; code23 не содержит M08, новых APK нет.
