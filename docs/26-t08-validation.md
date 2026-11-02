# T08 — dashboard, участники и явка

**READY_FOR_HUMAN — независимая кодовая часть, 2 ноября 2026.** Реализовано по `prompts/t08-dashboard-attendance.md` на HEAD `6de02da`. Пользователь прямо разрешил независимую T08 до ручной camera/UI-приёмки T07. T07/code 15, общая H3 и H4 остаются ожидающими; Back/H2 и T06 приняты на code 13. Backend отсутствует, API-предложение на согласовании. Автоматизация T08 не принимает физическую камеру за человека.

## Сборка и данные

[APK 0.8.0-t08-demo, code 16](../artifacts/apk/eventpass-0.8.0-t08-demo-code16.apk), **44 366 256 байт**, SHA-256 `800ae88e4282696985f6c12ccbcfd55022453221f211b54ffa87241ba408ebf2`. ApplicationId `ru.eventpass.android.demo`, min 28/target 37, debug v2 signature и aapt metadata проверены. Camera/camera.any/autofocus по-прежнему optional. Сохранённый code 15 не перезаписывался: `0171bcc65518d65c82ba7af98be1bdd50c80f087710f2fcd357d1120a89b859d`.

Toolchain и зависимости прежние: JBR 21 Quail, Gradle wrapper 9.5/AGP 9.3.3/built-in Kotlin 2.4.21. Новых библиотек нет. Все сборки offline; глобальные настройки и SDK не менялись. Versioned snapshot **schema 4 прежняя**: totals, row projections, revision tokens и volunteer counters вычисляются, transient faults/read errors не persist. Все прежние codec/writer/migration suites прошли. Архитектура — [t08-attendance](architecture/t08-attendance.md), текущие 02/03/05 и карты обновлены вместе с кодом.

## Выполнено

- AttendanceRepository: owner-only observeDashboard/refreshDashboard/attendees/cancelRegistration. Используется тот же FakeProductState/Mutex/IO/storage/event/auth Clock. Actor не приходит из UI, права EventAccessPolicy общие; чужие owner/volunteer/participant/guest/forged resource/page не получают закрытые данные.
- Summary из одного снимка: registered=Active+Used, checkedIn=Used, notCheckedIn=Active, remaining=null для unlimited, иначе capacity−registered с нижней границей 0. Cancelled остаются в списке, не totals. Zero percent безопасен. Started не Finished; текущим — «Ещё не пришли», после завершения — «Не пришли».
- Проекция Attendee без QR/manual/phone/answers: ID регистрации/билета, имя/email/номер/status, optional first checkedAt/checker. Legacy Used считается в явке, но история не выдумывается. Volunteer.checkedInCount считает реальные первые records конкретного checker на E; повтор/invalid/revoke/regrant не увеличивают/не стирают историю.
- Поиск trim/case-insensitive по имени и печатному номеру. Страница ≤20, сортировка consentAcceptedAt DESC + registrationId/ticketId; consentAcceptedAt — документированный demo timestamp регистрации. SHA-256 revision проекции + event/query/offset cursor защищают согласованность страниц. Это локальный consistency protocol, не принятый backend cursor. Relevant mutation сбрасывает UI к странице 1, старый query/result/finally не меняет новую выдачу. Totals не зависят от поиска/страницы.
- Owner отменяет выбранную registration E; ACTIVE→CANCELLED, повтор idempotent, USED запрещён, check-in/cancel сериализованы общим Mutex. Self и owner используют общий status transition с разными guards. Вопросы/ответы/registration history не меняются. Storage failure не публикует отмену/counters. Modal Network/Timeout/Storage видны внутри Dialog, retry той же записи; busy блокирует дубли/закрытие.
- DashboardKey(eventId,entryId), Hilt VM/UiState, stateless LazyColumn/stable keys. MyEvents owner → Dashboard → существующие Scanner/Volunteers → Back в тот же E/query/таб. Тяжёлое чтение только current/visible + RESUMED. Hidden/background/logout/denied очищают private rows/confirmation и отменяют jobs; query безопасно восстанавливается, данные перечитываются. Старый snapshot остаётся при transport refresh error только при сохранённом доступе. UI показывает event-clock timestamp и честное локальное обновление, не синхронизацию телефонов.

Экспорт скрыт до настоящих PDF/XLSX T09. MAX/profile/auth/API migration/polling/SSE/offline admission не добавлены. Camera pipeline T07 не переделывался; сохранены прежние routes/entry result/lifecycle.

## Дизайн и проверки

Прочитан figma-design-to-code skill. Read-only session `7630ed65-a2c5-44df-85f6-72ea3bfe89d9`, O04 `29:950` вернул Starter limit. Живая визуальная сверка не заявляется. Использованы сохранённые mobile texts `docs/sources/figma-2026-10-21.json`, ID из 03 и существующий core:designsystem; статические assets для O04 инструмент не выдал. Текущий screenshot сохранён в evidence и просмотрен отдельно; это инженерная проверка, не H3.

Сначала отдельно исполнен рабочий срез `:data:fake:test --tests '*AttendanceTest'`: shared owner summary → реальный CheckInRepository → Used в dashboard и личном билете. Затем добавлены поиск/страницы/отмена/UI.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :core:domain:test :data:fake:test :app:testDemoDebugUnitTest :core:platform:testDebugUnitTest :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest :app:lintDemoDebug :app:verifyDemoIsolation -I .gradle/t06-evidence/repeat-tests.init.gradle --offline --console=plain --max-workers=2
```

**155 JVM tests, 0 failures/errors/skipped:** domain 20, fake 76, app 53, platform 6. Четыре Test task реально исполнены принудительным init script; compile/assemble/lint частично инкрементальны, clean не запускался. Финальный full run — **BUILD SUCCESSFUL, 19s, 187 tasks: 10 executed / 177 up-to-date**, после первого полного прогона 154 tests/59s добавлена expiry/startsAt regression. Первая компиляция UI app/test прошла отдельно (1m). Hilt compile/isolation успешны. Lint **0 errors / 4 прежних version notices**, baseline/подавления не добавлены. После уточнения Started badge и device selectors повторены assemble app/test/lint; последний test APK build — 9s. Production APK после device-прогона не менялся.

Новые 10 fake tests: guards/forged page/registration, shared totals after guest/auth/checkIn, owner/self cancel/capacity/questions lock, 8 concurrent check-in/cancel races, 43-row paging/search/revision, legacy Used/real volunteer count/regrant, storage/transport/restart, queued cancel/session, startsAt/end/expiry. Новые 4 VM tests: late query/finally, hidden/new owner + late cancel, denied/PII + refresh/freshness, revision replacement + independent totals. Все прежние auth/registration/editor/check-in/cancel/migration tests сохранены и реально повторены.

**Device: OK (3 tests), 34.205s**, отдельный `EventPass_Insets_API33`, `emulator-5584`, API 33/portrait/three-button. Сценарии: owner dashboard → готовый Scanner/manual → Back → обновлённая явка → Volunteers → Back; поиск/Activity recreation/modal Network+Timeout+Storage/retry/отмена/refresh error/hidden/logout; forged volunteer dashboard без private rows. Первоначальный прогон был **2/3**: неоднозначный тестовый selector выбрал заголовок и кнопку с одинаковым текстом. Уточнён hasClickAction; приложение в этом исправлении не менялось. Это диагностический неуспех, не успешный прогон.

```powershell
$adb = 'C:\Users\XataB\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb -s emulator-5584 install -r artifacts/apk/eventpass-0.8.0-t08-demo-code16.apk
& $adb -s emulator-5584 install -r app/build/outputs/apk/androidTest/demo/debug/app-demo-debug-androidTest.apk
& $adb -s emulator-5584 shell am instrument -w -r -e class ru.eventpass.app.DashboardIntegrationTest ru.eventpass.android.demo.test/androidx.test.runner.AndroidJUnitRunner
```

Evidence — `.gradle/t08-evidence/{slice.log,attendance-tests.log,first-build.log,verification.log,final-verification.log,device-build.log,final-device-build.log,device.log,device-final.log,unit-results/,lint.xml,signature.txt,aapt.txt,output-metadata.json,t08-dashboard.png}`. Архитектурный verifier прошёл: 13 current pages, 236 local links, 9 Mermaid blocks, 18 mobile IDs, S01–S14/named test references; git diff --check прошёл. В существующих схемах уточнены подписи owners/operations, Mermaid rendering не выполнялся. Большой предсуществующий пакет API/архитектуры/prompts не включается в коммит целиком: собственная t08-attendance page и отчёт входят; уточнения смешанных canonical/maps остаются поверх пользовательской базы. CODE_REVIEW, source snapshot, daemon criteria/API-предложение и прочие посторонние файлы не менялись. Подагенты не запускались, push не выполнялся.

## Ограничения и H3 checklist

Нет backend и синхронизации двух устройств. Fake вычисляет проекции по общему in-memory store; больших production объёмов/remote paging/indexes не доказывает. Event Clock фиксирован `2026-10-27T09:00Z`, поэтому freshness timestamp может оставаться тем же при повторном чтении; auth Clock UTC как раньше. Нового physical camera/фокуса/бумаги/слабого света, API 28/37 runtime, landscape/gesture/TalkBack и OS process-kill proof нет. Recreation не равен уничтожению процесса.

Seed `t02-v1`, аккаунты `host@example.com` / `alex@example.com`, пароль **DemoPass123!**; существующие данные сохраняются при обновлении. [Demo QR ATTENDING](../artifacts/demo/t07/alex-attending.png), [MIXED](../artifacts/demo/t07/alex-mixed.png), [manual codes](../artifacts/demo/t07/credentials.json) — прежние seed-артефакты, не production credentials. Использовать билет в соответствующем E; после пользовательских изменений seed-статус может отличаться.

1. Host → «Мои мероприятия» → «Командный хакатон» → Dashboard. Проверить totals/строки/даты/статусы; query по имени/номеру и пустой query не меняют общие числа. Ноль и unlimited — отдельные состояния, Cancelled показан в списке с ясной подписью.
2. Dashboard → Scanner: owner без self-grant сканирует Active/вводит manual → Admitted → повтор AlreadyUsed. Back результата → ready → Dashboard того же E; checkedIn+1, личный билет Alex Used. В Volunteers проверять счётчик конкретного volunteer после его прохода, а не общую явку owner.
3. Host выдаёт/отзывает Alex право, проверяет Volunteers Back → Dashboard. Alex имеет отдельные owner/volunteer/participant/draft связи; чужой Dashboard недоступен, revoked grant убирает только сканирование E, свой ticket/история сохраняются.
4. Отменить ещё Active участника из Dashboard с подтверждением: место освобождается один раз, личный билет обновляется; Used не отменяется. Network/Timeout/Storage и гонка с check-in управляемо проверены автоматизацией. После refresh error видны старые разрешённые данные и ошибка, retry обновляет чтение; logout не показывает прежний список новой личности.
5. Незакрытая T07 camera/UI-приёмка: реальный QR с экрана/бумаги, фокус/слабый свет, denial/permanent denial/settings/manual, hidden/background/поворот/Back/revoke/logout. Физическая камера не принята контролируемым decoder/device smoke T08. Проверить текст/скролл/IME/крупный шрифт на своём устройстве.

После ответа человека отдельно фиксируются T07 и общая H3 на указанной сборке/устройстве. Сейчас T08 — READY_FOR_HUMAN. Следующая функциональная задача — T09, backend требуется для T10, не для этого demo dashboard.
