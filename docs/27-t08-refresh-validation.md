# T08: защита dashboard от позднего refresh

**READY_FOR_HUMAN, 2 ноября 2026.** Исправлена P2 по замечанию пользователя к `3426bdb`: запоздавший refresh A принимался после observer B и откатывал UI checkedIn=1 → 0. Store/check-in при этом не откатывались. Ручная camera/UI T07 и общая H3/H4 остаются ожидающими; Back/H2 и T06 приняты на code 13. Backend/API не согласованы.

## Исправление

- DashboardViewModel увеличивает observation generation при каждом результате observer (и Ready, и Failed). Refresh захватывает это поколение вместе с owner до suspend. Результат принимается только если observer за это время не продвинулся и owner тот же. Поздние success/error не заменяют новый snapshot/error. Hash revision остаётся opaque, event Clock не используется для определения порядка.
- Повторное чтение, запрошенное страницей/пользователем во время refresh, объединяется в один pending follow-up. После завершения текущего чтения busy снимается и выполняется отложенное чтение; mismatch страницы не застревает за refreshing guard. Это только чтение, не автоматический retry команды отмены/check-in.
- Stop/новый owner очищает pending; старый result/finally не разблокирует новый refresh и не переносит очередь прежней entry. Job публикуется до старта (CoroutineStart.LAZY): немедленный ответ не затирает ссылку на актуальное follow-up чтение, поэтому stop отменяет его. Lifecycle, repository ports, fake/codec/schema 4 и UI layout не менялись. Текущая [архитектура](architecture/t08-attendance.md) обновлена вместе с кодом.

## APK

[APK **0.8.1-t08-demo, code 17**](../artifacts/apk/eventpass-0.8.1-t08-demo-code17.apk), **44 367 304 байт**, SHA-256 `d42d9c95e2e27dbd1bfe75bf399c2274de00f77c50d528edf961129ef6028f3e`. ApplicationId `ru.eventpass.android.demo`, minSdk 28 / target 37, debug v2 signature и output metadata проверены. Архивные code 15/16 не перезаписывались. Обновление не меняет schema 4/credentials/seed и не сбрасывает данные. Toolchain/dependencies прежние, Gradle offline/JBR 21, global settings не менялись.

## RED/GREEN

Добавлены пять адресных JVM-регрессии с управляемыми continuations, без sleeps:

1. Suspend refresh A → observer B с checkedIn=1 и тем же timestamp → ответ A. Поиск `no-match` возвращает корректную пустую страницу, но независимые totals/revision должны остаться B, с завершёнными loading flags и без искусственной ошибки.
2. Observer TIMEOUT после начала refresh → поздний refresh NETWORK. Видимой остаётся более новая ошибка TIMEOUT и разрешённые старые rows.
3. Observer B → страница C с несовпадающей revision запрашивает refresh, пока A ещё занят → завершение A. Выполняется ровно одно follow-up чтение, получается согласованный snapshot/page C, busy/ошибки не застревают.
4. У старого owner pending refresh и queued read → stop/new owner/new refresh → старый ответ. Он не снимает новый busy и не переигрывает прежнюю очередь.
5. Main.immediate-подобный dispatcher: немедленный ответ refresh запускает suspend follow-up из-за page mismatch. Stop отменяет именно follow-up job, очищает state/busy.

На старом коде `:app:testDemoDebugUnitTest --tests '*DashboardViewModelTest'` завершился **8 tests / 2 failures**, BUILD FAILED за 5s: воспроизведены откат dashboard и замена новой observer error старой refresh error. Три теста follow-up/owner/немедленного ответа служат регрессиями для изменённой координации; пятый добавлен после исходного RED; не заявляются упавшими на старом коде. RED XML/log сохранены. Независимый Java helper из CODE_REVIEW не изменялся/не запускался заново; новая Kotlin regression проверяет тот же порядок на реальном VM.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :app:testDemoDebugUnitTest :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest :app:lintDemoDebug :app:verifyDemoIsolation -I .gradle/t06-evidence/repeat-tests.init.gradle --offline --console=plain --max-workers=2
```

**Финальный GREEN: 58 app JVM tests, 0 failures/errors/skipped; BUILD SUCCESSFUL, 39s, 178 tasks: 23 executed / 155 up-to-date.** Все девять DashboardViewModelTest и прежние auth/submit/editor/volunteer regressions исполнены. Init script принудительно повторил Test task; compile/assemble/lint частично инкрементальны, clean не выполнялся. App/test APK/Hilt/DI и isolation успешны, lint **0 errors / 4 прежних version notices**, baseline/подавлений нет. Domain/fake/platform suites не повторялись: эти исходники/контракты не менялись. Прежний полный прогон 155 JVM code 16 не приписывается новой сборке.

Device финального APK: **OK (3 tests), 33.175s**, завершённый instrumentation result. Предыдущий промежуточный code 17 также прошёл 3/3 (37.334s), но не подменяет финальный прогон. Использован только отдельный AVD `EventPass_Insets_API33`, `emulator-5584`, API 33/portrait/three-button. Существующий DashboardIntegrationTest повторён без новых UI selectors: owner dashboard → готовый Scanner/manual → Back/updated attendance → Volunteers → Back; query/recreation/modal Network+Timeout+Storage/cancel/retry/refresh error/hidden/logout; forged volunteer route без PII. Это integration smoke; точный порядок гонки доказывает адресная JVM regression.

```powershell
$adb = 'C:\Users\XataB\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb -s emulator-5584 install -r artifacts/apk/eventpass-0.8.1-t08-demo-code17.apk
& $adb -s emulator-5584 install -r app/build/outputs/apk/androidTest/demo/debug/app-demo-debug-androidTest.apk
& $adb -s emulator-5584 shell am instrument -w -r -e class ru.eventpass.app.DashboardIntegrationTest ru.eventpass.android.demo.test/androidx.test.runner.AndroidJUnitRunner
```

Evidence: `.gradle/t08-refresh-evidence/{red.log,red-unit-results/,verification.log,final-verification.log,unit-results/,device.log,device-final.log,lint.xml,signature.txt,badging.txt,apk.json,output-metadata.json,architecture.txt}`. `python scripts/verify-architecture-docs.py` прошёл: 13 страниц, 238 локальных ссылок, 9 Mermaid blocks, 18 mobile IDs, S01–S14 и named test references. `git diff --check` прошёл. Mermaid не менялся; rendering не запускался. CODE_REVIEW сохранён побайтно (SHA-256 сверена), полный review skill не запускался; пользовательский пакет API/архитектуры/prompts/daemon criteria не присваивается коммиту. Смешанные README/06/08 staging отделён от пользовательской базы; свои уточнения untracked maps остаются поверх неё. Подагентов/push нет.

## Ручная перепроверка и пределы

Установить code 17 обновлением; seed `t02-v1`, `host@example.com` / `alex@example.com`, пароль **DemoPass123!**. Повторить Dashboard → Scanner/manual/check-in → Back: явка и Used не должны уменьшаться после «Обновить», поиск не меняет totals. Отмена другого Active участника обновляет counters/места; ошибку refresh можно явно повторить. Точные задержки A/B управляются JVM-тестом, обычный локальный fake может отвечать слишком быстро для ручного воспроизведения гонки.

Физическая камера/бумага/фокус/слабый свет, API 28/37 runtime, landscape/gestures/TalkBack и новый OS process-kill не проверялись. [Полный H3 checklist T08](26-t08-validation.md) и [T07 camera/UI](24-t07-ui-validation.md) остаются человеку; device smoke и code review их не принимают. T08 остаётся READY_FOR_HUMAN, T07/H3/H4 не закрыты. Следующая функциональная задача — T09, backend нужен для T10.
