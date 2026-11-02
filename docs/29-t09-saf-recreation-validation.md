# T09: сохранение SAF export при пересоздании Activity

**READY_FOR_HUMAN, 2 ноября 2026.** Исправлена P2 `8d01048`: DocumentActions безусловно вызывал clear на dispose и mount, хотя ViewModel и launch ID переживали configuration change. После возврата URI pending уже отсутствовал, запись не начиналась и UI не сообщал об ошибке. Полный T09 остаётся IN_PROGRESS; Q10/deletion/API не согласованы, T07 camera/UI и H3/H4 ожидают человека.

## Исправление и границы

Route использует LocalActivity.isChangingConfigurations при dispose. VM получает идемпотентный bind(request,visible): повторное подключение того же ресурса не стирает picker/launched flag, captured session, busy или выполняющийся writer. Unbind при configuration recreation сохраняет операцию; настоящий выход из композиции, hidden/resource change и прежний session watcher очищают её. Новая Activity повторно регистрирует ActivityResult callback и использует прежний saveable launch ID; повторного открытия picker нет. Не отключались системные configuration changes, не добавлялись URI/PII в saved state. Порты, schema 5, репозитории, writers, credentials и зависимости прежние.

После process death нового pending в VM нет: late result игнорируется, экспорт нужно запустить заново. Возобновление после смерти процесса этой правкой не обещается. Реальные источники: [DocumentActions](../app/src/main/kotlin/ru/eventpass/app/feature/documents/DocumentActions.kt), [DocumentsViewModel](../app/src/main/kotlin/ru/eventpass/app/feature/documents/DocumentsViewModel.kt), [текущий lifecycle](architecture/t09-profile-files.md). Проверены официальные [Activity configuration flag](https://developer.android.com/reference/android/app/Activity#isChangingConfigurations()) и [ActivityResult recreation contract](https://developer.android.com/training/basics/intents/result).

## APK

[APK **0.9.1-t09-demo, code 19**](../artifacts/apk/eventpass-0.9.1-t09-demo-code19.apk), **44488135 байт**, SHA-256 `660f12f354dd963066a24f3a17cee318fcec7a302961748d4acefbd7f25c0ece`.

ApplicationId `ru.eventpass.android.demo`, minSdk 28 / target 37, debug v2 signature/output metadata/aapt проверены. Архивные APK code 15/17/18 и старые sample PDF/XLSX не менялись. Toolchain/runtime dependencies прежние: JBR 21, wrapper Gradle 9.5, AGP 9.3.3, Kotlin 2.4.21; offline build. Schema 5 без новой миграции; ограничение downgrade из docs/28 остаётся. Seed `t02-v1`, `alex@example.com` / `host@example.com`, пароль **DemoPass123!**.

## Доказательства

- **66 app JVM tests, 0 failures/errors/skipped**, включая все пять DocumentsViewModelTest. Две новые регрессии проверяют PDF/XLSX picker → configuration unbind/bind → доставку URI ровно один раз; тот же переход во время suspended writer; настоящий exit/hidden/другой ресурс игнорируют прежний URI. Прежние logout/session/late result/finally, ошибки, auth и dashboard regressions повторно исполнены.
- `SafRecreationTest`: настоящий DocumentsUI открыт поверх host. Для PDF остановленная Activity пересоздаётся прямым Activity.recreate(); для XLSX применяется UiAutomation rotation 90°. Прежняя MainActivity entry возвращается на передний план с FLAG_ACTIVITY_REORDER_TO_FRONT, реальный ActivityResultRegistry доставляет controlled URI тестового ContentProvider. Проверяется новая Activity, «Файл сохранён», непустые bytes с `%PDF`/`PK`. Это реальный Android/configuration lifecycle и controlled result/provider, а не автоматическая проверка выбора папки/кнопки Save во всех системных providers.
- **RED на установленном code 18:** оба теста дошли до URI delivery и не получили document-saved за 15s; завершено **2 tests / 2 failures, 45.791s**, `red-device-4.log`. Production app code 18 оставался установленным, пересобирался только test APK. Этот RED совпадает с исходной потерей pending; failed harness-попытки до него не считаются доказательством P2.
- **GREEN code 19: OK (5 tests), 43.105s** — те же две SAF regressions и три прежних ProfileFilesIntegrationTest. Проверены обычные picker cancel/success, profile/recreation/logout/new actor, настоящие PDF/XLSX/provider error/temp cleanup/PDF QR decode. Только отдельный `EventPass_Insets_API33`, emulator-5584, Android 13/API 33, three-button, fontScale 1.0; XLSX rotation → landscape → portrait cleanup.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :app:testDemoDebugUnitTest :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest :app:lintDemoDebug :app:verifyDemoIsolation -I .gradle/t06-evidence/repeat-tests.init.gradle --offline --console=plain --max-workers=2
# BUILD SUCCESSFUL in 1m 23s; 178 tasks: 35 executed / 143 up-to-date.
# После финальной правки только device-test harness:
.\gradlew.bat :app:lintDemoDebug :app:assembleDemoDebugAndroidTest --offline --console=plain --max-workers=2
# BUILD SUCCESSFUL in 10s; 148 tasks: 3 executed / 145 up-to-date.

$adb = 'C:\Users\XataB\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb -s emulator-5584 install -r artifacts/apk/eventpass-0.9.1-t09-demo-code19.apk
& $adb -s emulator-5584 install -r app/build/outputs/apk/androidTest/demo/debug/app-demo-debug-androidTest.apk
& $adb -s emulator-5584 shell am instrument -w -r -e class 'ru.eventpass.app.SafRecreationTest,ru.eventpass.app.ProfileFilesIntegrationTest' ru.eventpass.android.demo.test/androidx.test.runner.AndroidJUnitRunner
```

App/test APK, Hilt/DI и verifyDemoIsolation успешны. Lint **0 errors / 4 прежних version notices**, без новых подавлений/baseline. Unit Test task принудительно повторён init script; compile/assemble/lint incremental, clean не выполнялся. Domain/fake/platform suites в этой правке не повторялись: их код/контракты не менялись. Полные 176 JVM/13 invocations code 18 — история [28](28-t09-validation.md), не новый прогон code 19.

Промежуточные harness failures сохранены: не удалось получить DocumentsUI accessibility nodes; ActivityScenario.recreate/moveToState требует RESUMED и не подошёл для STOPPED picker host. Итоговый тест не использует эти пути. Android CLI использован для official docs search/fetch; CLI не нашёл task-local AVD, layout helper не смог получить дерево, поэтому AVD запущен штатным SDK emulator. Эти инструментальные ограничения не выданы за дефекты приложения. Параллельных Gradle builds/подагентов/review skill/push нет; CODE_REVIEW не менялся.

`python scripts/verify-architecture-docs.py` прошёл: 14 current pages, 275 local links, 9 Mermaid blocks, 18 mobile IDs, S01–S14 и named test references. `git diff --check` прошёл. Диаграммы не менялись, Mermaid rendering не запускался.

Evidence: `.gradle/t09-saf-recreation/` — baseline/initial-status/review-sha, official activity-results.txt, red-build/device attempts, verification.log, final-device-build.log, green-device.log, unit-results/summary, apk.json/signature/badging/lint/output metadata, architecture.txt. API/OpenAPI/daemon criteria и посторонние изменения сохранены; own-only staging README/06/08 отделён от пользовательской базы. Текущие untracked architecture maps уточнены поверх прежнего пакета, без присвоения его коммиту.

## Ручная перепроверка

1. На code 19 войти Alex, открыть текущий Active билет → PDF. Пока открыт системный выбор места, повернуть устройство; сохранить, открыть файл внешним viewer. Повторить с отменой picker: не должно быть ложного успеха/зависшего busy, следующая попытка доступна.
2. Войти Host → dashboard E → XLSX. Во время системного picker повернуть в landscape и обратно; сохранить, проверить файл и сообщения результата. Уход с dashboard/смена ресурса/logout должны игнорировать прежний результат.
3. Не путать поворот с убийством процесса. Для H4 отдельно остаются реальный provider/внешний viewer на устройстве, API 28/актуальная версия, gestures/TalkBack и полная матрица из [28](28-t09-validation.md). Физическая camera/UI T07 и общая H3/H4 не приняты. Следующий шаг — ручная проверка code 19, Q10 отдельно, T10 после backend/API.
