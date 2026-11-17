# T10 M07 — dashboard, участники и owner cancel

**IMPLEMENTED/COMPILE_VERIFIED; поведение NOT_VERIFIED.** 17 ноября 2026. Base `57279e5005c294d0655801e5dc13f395681afdc1`; target — локальный коммит с этим отчётом. M07 начат по прямой команде без ревью M06; M05/M06 поведенчески не проверены, M02/M05 review и human-пункты не закрываются. M08 не начат.

- Подключён RemoteAttendanceRepository: owner header через M04 mapping, Stats + первая страница с одной revision, query/server-page cursor, bounded STALE_PAGE recovery, typed historical answers без вымышленных registration/time/checker данных.
- Общая Dashboard VM принимает snapshot атомарно, сохраняет observer-over-refresh guard, отменяет старый query/page и ограничивает recovery. Поиск/selection/PII только в памяти entry, не SavedState/Room.
- Owner cancel использует ticketId; после подтверждения перечитывается видимый E/query, инвалидируются связанные ticket/scanner projection. Unknown/Used/access failure не становятся успехом или очередью. Volunteer count берётся из M06, неизвестное не равно0.
- Remote DI/host/MyEvents используют прежние Dashboard/Scanner/Volunteers; XLSX скрыт до M08. Fake adapter изменён только для nullable legacy fields и реального ticket→registration соответствия; demo не проверен. [Текущая архитектура](../architecture/t10-dashboard-attendees.md).

## Выполненная проверка

Ровно один Gradle compile, **exit0, BUILD SUCCESSFUL in13s**, 45 tasks:15 executed/30 up-to-date. JAVA_HOME=`C:\Program Files\Android\Android Studio1\jbr`:

```powershell
.\gradlew.bat :app:compileRemoteDebugSources -I .gradle/m05-evidence/verified-artifacts.init.gradle -I .gradle/m01-verified-google.init.gradle --offline --console=plain --max-workers=1
```

Лог `.gradle/m07-evidence/remote-compile.log`: domain/remote/app Kotlin, KSP/Java/DI сгенерированные классы, без assemble. Версии и зависимости не менялись; использован существующий verified cache M05. Контракт прочитан из локального OpenAPI1.1.1, live/staging не проверялись. Осталось неошибочное предупреждение существующего RemoteTickets `!!`. Однократно прошли `python scripts/verify-architecture-docs.py` (exit0:22 pages/428 links/9 Mermaid/18 mobile IDs/S01–S14) и `git diff --check` (exit0); это проверки документации, не поведения API.

## NOT_RUN / риски / передача

По сокращённому режиму не запускались unit/scenario suites, lint, demo/isolation, staging, APK/test APK, device/emulator/UI и независимое ревью. Согласованность при конкурентных изменениях, STALE_PAGE budgets, быстрый поиск/late finally/actor cleanup, cancel vs scan, Used/revoke/429/transport и возврат из M06 **NOT_VERIFIED**. Серверная атомарность отмены и check-in остаётся server authority; header GET не имеет revision. Компиляция не доказывает поведение и live совместимость, прежние результаты M03–M06 не выдаются за новый прогон.

Baseline `.gradle/m07-evidence/baseline`, initial-status.txt: пользовательские AGENTS/CODE_REVIEW/00/06/08/14/34/35, prompts M04–M07 и local JVM settings сохранены. Коммит содержит только собственные изменения; для dirty документов подготовлены изменения поверх HEAD, дополнения остаются в worktree. Push не выполняется. Следующий этап M08 — только по новой команде; расширенные проверки/review и human acceptance отдельно.
