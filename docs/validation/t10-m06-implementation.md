# T10 M06 — remote scanner и волонтёры

**IMPLEMENTED / COMPILE_VERIFIED, поведение NOT_VERIFIED.** 17 ноября 2026. Base `9d56e6973fecb093b92bfdacc13f8266304f2ec7`; target — локальный коммит, содержащий этот отчёт. M06 начат без ревью M05 по прямой команде пользователя и актуальному AGENTS. M05/M02 review не объявляются закрытыми, M07 не начат, human acceptance — после M09.

## Изменения

- RemoteCheckInRepository: настоящий GET scanner / POST scan, sparse receipt без вымышленных ticketId/checkerId, непрозрачный QR и отдельный EVT manual parser, неизменная memory-only команда/key на retry, firstAttempt24h/429 guards, actor/event scope, server history/counts.
- RemoteVolunteersRepository: GET/POST/DELETE, identifier email/publicId, полноценная Volunteer projection, Revoked по userId и 204 без JSON; single-flight, ошибки и устаревшие чтения ограничены scope/version.
- Подключены существующие Controller/VM/CameraX UI, remote DI и MyEvents navigation. Replayed receipt нейтральный; unknown attempt блокирует новый scan и предлагает тот же retry/сверку. Volunteer показывает имя/publicId/time/count. Отказ доступа не становится offline успехом или общим logout при403.
- [Текущие владельцы/операции/ограничения](../architecture/t10-scanner-volunteers.md). Room/fake codec/dependencies не менялись. Shared domain/UI расширены совместимыми legacy ветвями; demo не проверен.

## Проверка

Ровно один Gradle compile, exit **0**, **BUILD SUCCESSFUL in 18s**, 45 tasks:15 executed /30 up-to-date. JAVA_HOME=`C:\Program Files\Android\Android Studio1\jbr`:

```powershell
.\gradlew.bat :app:compileRemoteDebugSources -I .gradle/m05-evidence/verified-artifacts.init.gradle -I .gradle/m01-verified-google.init.gradle --offline --console=plain --max-workers=1
```

Лог `.gradle/m06-evidence/remote-compile.log`: domain/remote Kotlin, app KSP/Kotlin/Java/DI сгенерированные классы скомпилированы, без assemble/APK. Использован существующий verified dependency cache M05, новых версий/загрузок не добавлено. Контракт прочитан из локального OpenAPI1.1.1, live/staging не проверялись. Однократно прошли `python scripts/verify-architecture-docs.py` (exit0:21 pages/410 links/9 Mermaid/18 mobile IDs/S01–S14) и `git diff --check` (exit0). Это документационная проверка, не доказательство scan поведения.

## NOT_RUN / риски

По заданному сокращённому режиму не запускались unit/сценарные suites, lint, demo/isolation, staging, APK/test APK, device/emulator/camera/UI и независимое ревью. Конкурентные scan/replay/late result/revoke/refresh/cooldown, ротация и реальные сетевые отказы **NOT_VERIFIED**. Старые T07/M03/M04 результаты не выдаются за новый прогон M06. Серверная авторизация и атомарность check-in остаются обязанностью backend; совместимость с live API не доказана одним compile. M05 непроверенные сценарии сохраняют прежний статус.

Baseline до работы: AGENTS.md, CODE_REVIEW.md, docs/00/06/08/14/34/35 и пользовательские prompts M04/M05/M06/local JVM settings. Копии — `.gradle/m06-evidence/baseline`, status — initial-status.txt. Чужие файлы исключены из коммита; для dirty документов индекс содержит только собственные изменения поверх HEAD, дополнения остаются в worktree. Push не выполняется.

Следующая задача — M07 только по новой команде. Дополнительные проверки/review отдельно по запросу; техническая или ручная приёмка M06 из компиляции не следует.
