# T07 — grants/check-in и UI

**READY_FOR_HUMAN, актуальное исправление code 15.** Volunteers/Scanner UI интегрированы, schema 4 прежняя. Исправлены revoke Dialog error/retry и camera/autofocus requirement; 49 app JVM / 4 device и aapt/assemble/lint/isolation — [25](25-t07-fixes-validation.md). Полная интеграция code 14 и ручной camera checklist — [24](24-t07-ui-validation.md). Back/H2/T06 приняты на code 13, camera/UI T07 и H3/H4 ожидаются. Backend отсутствует/API на согласовании.

## История независимой foundation code 12

**IN_PROGRESS.** Пользователь прямо ответил: «Ещё не проверял — выполнить независимую часть T07». Организаторский smoke T06 ожидается на code 11. Реализованы только domain/fake/persistence, чистая модель scanner state и тесты; Volunteers/Scanner UI, routes, показ manual code и камера не подключены. T06 DONE/H3/H4 не отмечались. Архитектура текущей части — [t07-checkin-foundation](architecture/t07-checkin-foundation.md).

## APK и воспроизводимость

[app-demo-debug.apk](../artifacts/apk/eventpass-0.7.0-t07-foundation-demo-code12.apk): **0.7.0-t07-foundation-demo**, code **12**, **34079002 байт**, SHA-256 `ef163233826196c8006c05e8da12aebbdf0c2fdb5a400b6a1582136ad2ae9c69`. [Сохранённая копия](../artifacts/apk/eventpass-0.7.0-t07-foundation-demo-code12.apk). ApplicationId `ru.eventpass.android.demo`, minSdk 28, target/compile 37; aapt2/output metadata и debug-подпись v2 проверены. Test APK собран. На устройство ничего не устанавливалось; новых device/camera/OS process-kill прогонов нет.

Для ожидающей приёмки T06 сохранён [APK code 11](../artifacts/apk/eventpass-0.6.1-t06-demo-code11.apk), SHA-256 `ab1567bb070cab86dca460830feb9298144495b38a0eeeccf87b9aadcb7d028d`. Прежние 9 device относятся к T06, не T07. Сначала проверить T06 на этой сборке, затем устанавливать foundation: после записи schema 4 старый code 11 её не читает, обратной миграции нет. Данные автоматически не сбрасываются.

Toolchain/версии сохранены: JBR 21 `C:\Program Files\Android\Android Studio1\jbr`, wrapper Gradle 9.5, AGP 9.3.3/Kotlin 2.4.21. Новых внешних библиотек нет; domain tests получили ту же coroutines-test 1.10.2, что уже была у app/fake. Камера/decoder не выбирались и не загружались до gate. UI-kit/Figma nodes в этой независимой части не читались заново: UI не менялся. Seed `t02-v1`, технический `t01-v1`; `alex@example.com` и `host@example.com`, пароль `DemoPass123!`. Event Clock fixed `2026-10-27T09:00Z`, auth Clock system UTC, отдельные demo offsets прежние и не persist.

## Выполненный объём

- CheckInRepository: observeAccess/refreshAccess/checkIn(eventId, typed credential). Owner без self-grant; volunteer только active grant. Actor/current verification/expiry/session generation/права перепроверяются под общим Mutex на IO. Ни actorId, ни global role из UI не принимаются.
- VolunteerRepository: owner-only list/grant по email/ID существующего аккаунта/revoke по userId; typed account-not-found, access, transport/storage/session errors. Grant/revoke идемпотентны, приглашений/писем/аккаунтов не создают. MyEvents использует тот же grants list. Revoke не удаляет registration/ticket/первый проход и не убирает owner capability.
- Active → Used + первый CheckInRecord с event/ticket/checker/Event Clock/name/display number. Повтор QR или manual → AlreadyUsed с прежним record, без второго прохода. Used нельзя отменить текущим TicketRepository; количество занятых мест не меняется. WrongEvent/Cancelled/Unknown/неактивное E не меняют данные. Forbidden/Network/Timeout/Storage не Invalid и не «можно пройти».
- Временный default: закрытие регистрации/startsAt не запрещают вход, Active на Finished/Cancelled/после endsAt не допускается. Used остаётся AlreadyUsed с сохранённой историей. Это demo policy рядом с прежним Ticket.isCurrent, не production API/time contract.
- ManualCode отдельно от QrToken/displayNumber. 12 символов `23456789ABCDEFGHJKLMNPQRSTUVWXYZ`, uppercase, ввод с пробелами/дефисами и нижним регистром нормализуется. Детализация derivation — в архитектуре/ADR-24: domain-separated SHA-256 от opaque QR, 60 bits. Не вычисляется из публичного номера/ID и не заявляется production credential security. Credential wrappers/snapshot/record имеют redacted diagnostic representation. Lookup возможен только после проверки права на eventId, без произвольного guest ticket API.
- Один общий FakeProductState/Mutex/storage с auth/tickets/editor; DI bindings новых портов только demo. Storage failure не публикует ложный Admitted/Used/grant. Queued cancel не мутирует данные и освобождает caller, уже выполненный commit не обещает rollback после отмены.
- ScannerController в core:domain — чистая entry-owned coroutine модель, без Android ViewModel/Compose/CameraX. Access/permission/foreground/ready/scanning/submitting/result/error различимы. Manual работает после denial, пустой frame не Invalid, одна лексикографически первая строка за цикл, stale generation/duplicate frames блокируются. Result останавливает анализ до next; Back результата → ready. Background/close/revoke/new session сбрасывают PII/result и pending operation. Late result/finally не меняет новый scan. Transport failure не повторяет credential автоматически; новый процесс начинает новый scan, старый «допуск» не сериализуется.

## Snapshot и совместимость

Schema **4** расширяет прежний v3 payload ticket/manual mapping и first check-in records. Reader поддерживает auth v1 и product v2/v3/v4. Прежние accounts/proofs/challenges/answers/events/drafts/revisions/cover/grants/tickets/status/QR сохраняются. Для v1/v2/v3 manual code детерминированно получается из прежнего QR; первая успешная запись любого writer выдаёт v4. Уникальность и связи records/codes проверяются, unknown/truncated/trailing snapshot блокирует операции со StorageUnavailable без записи seed.

Legacy/debug Used мог не иметь checker/time: для него AlreadyUsed.first = null, история не выдумывается и Used не превращается в Active. Новые реальные demo check-in records сохраняются после restart/revoke и соседних auth/editor/registration записей. Debug setTicketStatus не может обратить уже записанный check-in. Старый GuestReceiptState JSON не менялся: compact snapshot получает code из уже имеющегося payload без нового lookup; UI его пока не показывает.

Замороженный `LegacyV3Fixture` взят из прежнего codec, не текущего encoder. Тесты покрывают v3→v4, legacy Used и corruption; прежние v1/v2 regression tests сохранены с обновлённым ожидаемым номером writer 4 (исторические имена содержащие V3 оставлены). Auth session/outbox/scanner credentials/UI results по-прежнему не persist.

## Выполненные проверки

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :core:domain:test :data:fake:test :app:testDemoDebugUnitTest :core:platform:testDebugUnitTest :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest :app:lintDemoDebug :app:verifyDemoIsolation -I .gradle/t06-evidence/repeat-tests.init.gradle --offline --console=plain --max-workers=2
```

**BUILD SUCCESSFUL, 1m 2s**, 187 tasks: 32 executed / 155 up-to-date. Init script принудительно исполнил все четыре JVM test tasks, компиляция/assemble/lint частично инкрементальны. Clean не выполнялся.

| Модуль | Tests | Failures / errors / skipped |
|---|---:|---|
| core:domain | 20 | 0 / 0 / 0 |
| data:fake | 66 | 0 / 0 / 0 |
| app | 37 | 0 / 0 / 0 |
| core:platform | 3 | 0 / 0 / 0 |
| **Итого** | **126** | **0 / 0 / 0** |

25 новых тестов: 9 scanner/model/credential + 15 fake contract/migration + 1 legacy guest saved-state compatibility. Прежние 101 сохранены, включая восемь auth-регрессий, обе cancelled submit finally и editor late save/finally. Проверены очередь с revoke/generation, cancel при занятом Mutex и живой единственный поток caller, Active→Used→repeat, три invalid ветки, time/expiry/unverified, owner-only grants, одинаковый first record после restart, все writers, storage failure, legacy v3 Used без истории и отсутствие PII при denied/late result. Параллельные запросы доказывают согласованность одного локального store, не многосерверную атомарность или requestId-протокол.

После полного прогона усилена существующая проверка сохранения истории после revoke; адресный `:data:fake:test` повторно прошёл, production/APK не менялись. Initial диагностические прогоны обнаружили два старых ожидания schema 3 и отсутствующую domain test dependency; они исправлены, не считаются зелёными прогонами. Lint **0 errors / 4 прежних version notices**, baseline/исключений нет. Hilt app/test compilation и demo isolation успешны.

Материалы: `.gradle/t07-evidence/verification.log`, `final-fake.log`, `unit-results/`, `lint.xml`, `signature.txt`, `output-metadata.json`, `apk.json`. Demo credential-примеры — отдельный `.gradle/t07-evidence/demo-credentials.json`, созданный reflection-helper из настоящих compiled fixtures; значения не выводились в обычные логи. Это локальные seed credentials, не production данные и не доказательство работы камеры. В текущем APK scanner UI их ещё не принимает. Для восстановления после reset использовать seed `t02-v1`; новые guest билеты получают другие opaque tokens.

Архитектура обновлена в текущих 02/03/05 и responsibilities/state-ownership/operations/invariants, отдельно добавлена страница T07. `python scripts/verify-architecture-docs.py` успешен: 11 текущих страниц, 185 локальных ссылок, 9 Mermaid blocks, 18 mobile IDs, S01–S14 и ссылки на именованные regression tests. `git diff --check` также успешен. Mermaid parser/rendering не выполнялся: существующие edges сверены с тем же общим state/Mutex; структурный verifier не доказывает semantics/rendering. Предсуществующие пользовательские архитектурные страницы/скрипт не включаются в локальный коммит автоматически: собственная новая страница T07 входит, правки смешанных canonical/maps остаются в рабочем дереве поверх сохранённой пользовательской базы. CODE_REVIEW.md, AGENTS.md, prompts и daemon criteria не изменялись этой задачей.

## Остаток и ручной checklist

Сейчас человек принимает **T06/code 11** по [20-t06-validation.md](20-t06-validation.md): draft/restart/questions/cover/publish/share/guest/schema lock/close/finish. Подтверждение не запрашивается повторно в этой задаче; пользователь сообщил, что ещё не проверял. Foundation code 12 подготовлен для проверки кода/контрактов, не передан как готовый scanner UI.

После приёмки остаётся T07: O05 grant/revoke UI, переходы из существующего MyEvents/owner surface, Scanner/Result routes и Hilt VM, показ manual code на своих билетах/guest receipt, CameraX + один QR decoder с актуально проверенными совместимыми версиями, CAMERA denial/settings flow, lifecycle binding/unbind и ImageProxy/backpressure. Нужны адресные device-проверки permission/hidden tab/revoke/background/rotation и настоящего decoder; Figma O05/V02–V05 прочитать перед реализацией. Эти функции сейчас не заглушены фиктивным success.

Будущий scanner smoke (пока **не готов к выполнению в UI**): owner выдаёт grant → volunteer открывает E → Active QR → Admitted → повтор AlreadyUsed; WrongEvent/Cancelled/Unknown; manual code; deny/permanent deny камеры и ручной ввод; background/смена таба; revoke при камере; свой билет остаётся, Used не отменяется; restart сохраняет first record. Реальный QR с другого экрана/бумаги/слабый свет — отдельная проверка на конкретном устройстве. H3 целиком после T08; T08 dashboard/grants counters/search/export/offline/real API в этой части не реализуются.
