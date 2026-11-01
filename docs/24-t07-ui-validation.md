# T07 — Volunteers и общий Scanner: UI-интеграция

**READY_FOR_HUMAN, 1 ноября 2026.** Back/H2 и T06 приняты пользователем на code 13; T07 выполнена поверх `bb62cd0`/`555a2a0`, без повторной foundation. Backend отсутствует, API-предложение отправлено на согласование и не принято автоматически. Camera/UI-приёмка T07 и H3/H4 не отмечены пройденными; полная H3 после T08.

## APK

[Сохранённый APK code 14](../artifacts/apk/eventpass-0.7.2-t07-demo-code14.apk): **0.7.2-t07-demo**, versionCode **14**, applicationId `ru.eventpass.android.demo`, minSdk 28, target/compile 37. Размер и SHA-256: **44354768 байт**, SHA-256 `a7f99c3af36b7c166305123f520a3d5509113bdb4a39c3ab5aa0e38ec20bcb3d`. Output: `app/build/outputs/apk/demo/debug/app-demo-debug.apk`; debug-подпись v2 проверена. Старые code 11/12/13 сохранены. Общий server snapshot schema 4 прежний, reader v1–v4; обратной миграции к code 11 нет. На пользовательское устройство агент ничего не устанавливал и данные не сбрасывал.

## Готовые сценарии

- MyEvents получил Scanner для owner/active volunteer и Volunteers для owner; внутренние переходы сохраняют исходный таб/родителя. Проверка repository остаётся обязательной, кнопка не заменяет авторизацию.
- Owner-only list/grant по существующему email/ID/revoke с подтверждением, loading/empty/errors/retry/busy. Неизвестный аккаунт — ошибка; приглашений/signup нет. Тот же store обновляет grants/MyEvents, participation/ticket/история сохраняются. Владелец сканирует без self-grant.
- ScannerKey содержит только eventId/entry UUID; Hilt VM использует прежний ScannerController. Результат в одной entry, Back результата → ready, следующий Back → родитель. Admitted, AlreadyUsed(first checker/time, либо честное отсутствие legacy history), Invalid(unknown/wrong event/cancelled/inactive) различимы. Время первого прохода форматируется с явной UTC, checker показан существующим ID; нового account lookup/имени backend нет. Transport/timeout/storage — отдельная ошибка, не Invalid/допуск; автоматического retry нет.
- Существующий ManualCode показан на текущем своём билете и compact guest receipt. History без QR/code. Старый SavedStateHandle receipt format не менялся, guest lookup/cache не создавался.
- Runtime CAMERA/denial/settings, manual без камеры, CameraX preview/analyzer + прежний ZXing. Hidden/current-entry/RESUMED gating, background/Back/revoke/logout закрывают preview и очищают result/input. Каждый ImageProxy закрывается в finally, latest-frame backpressure, один worker и одно распознанное сообщение за цикл. Main callback захватывает generation цикла, stop guard блокирует поздний bind/callback. После нового процесса нет восстановленного Admitted/credential; recreation также возвращает ready, без старого допуска.

Не менялись domain/fake contracts, schema/migration, auth TTL/refresh, credential generation и API-предложение. Не добавлены dashboard/attendees/export/profile/remote/offline admission. [Фактическое wiring/lifecycle](architecture/t07-ui-lifecycle.md), [foundation](architecture/t07-checkin-foundation.md).

## Версии и дизайн

Добавлен только CameraX **1.6.2** (core/camera2/lifecycle/view), закреплён в catalog; ZXing Core **3.5.4** уже использовался. [Официальные release notes](https://developer.android.com/jetpack/androidx/releases/camera) называют 1.6.2 stable на 21 октября 2026; [analyze guide](https://developer.android.com/media/camera/camerax/analyze) — источник lifecycle/backpressure/ImageProxy.close. Decoder опирается на [ZXing PlanarYUVLuminanceSource](https://github.com/zxing/zxing/blob/zxing-3.5.4/core/src/main/java/com/google/zxing/PlanarYUVLuminanceSource.java). Camera-view транзитивно приносит camera-video/media3, приложение не пишет видео. Точное сочетание подтверждено сборкой, lint и AVD API 33, а не произвольным Android runtime.

JBR 21/Gradle 9.5/AGP 9.3.3/built-in Kotlin 2.4.21 и остальной stack сохранены. Compile прошёл online после штатного получения missing Maven runtime dependencies; финальные прогоны offline. TLS/certificate checks не отключались, глобальные настройки не менялись.

Прочитан figma-design-to-code skill. Сессия `3e0c3e1a-09b0-4a4a-8284-84022f512df5`; O05 `29:833`, V03 `29:289`, V04 `29:336`, V05 `29:375` вернули Starter limit, V02 `29:243` — Transport closed. Живая визуальная сверка этих узлов не заявляется. Использованы сохранённые mobile texts `docs/sources/figma-2026-10-21.json`, реестр mobile-frame и существующий core:designsystem/Inter. Статические Figma assets для этих узлов инструмент не выдал. Просмотрены screenshots текущих Volunteers/Result/Camera на AVD; это инженерная проверка, не человеческая приёмка или доказательство фокуса.

## Реально выполненные проверки

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :core:domain:test :data:fake:test :app:testDemoDebugUnitTest :core:platform:testDebugUnitTest :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest :app:lintDemoDebug :app:verifyDemoIsolation -I .gradle/t06-evidence/repeat-tests.init.gradle --offline --console=plain --max-workers=2
```

**138 JVM, 0 failures/errors/skipped:** domain 20, fake 66, app 46, platform 6. Восемь новых: пять VM wiring и три decoder. Все прежние auth/submit/editor/migration suites сохранены и исполнены, старый GuestManualCompatibilityTest проходит. Test outputs принудительно исполнены init-скриптом; остальные задачи инкрементальны, clean не запускался. App/test APK/Hilt compilation/isolation успешны, lint **0 errors / 4 прежних version notices**, без baseline/подавления новых warnings. После последней фиксации callback-generation отдельно повторены app 46 tests/assemble app+test/lint/isolation.

Диагностические неуспехи сохранены: offline missing CameraX/transitive runtime deps; первый Volunteers VM test показал guard, ошибочно зависящий от живого observer Job после finite Ready flow. Исправлено отдельным lifecycle active flag + generation, регрессия проходит. Новый UseKtx warning устранён. Helper генерации QR сначала выбрал javadoc вместо runtime JAR; исправлен только helper, APK этим не менялся.

Устройство — отдельный AVD `EventPass_Insets_API33`, `emulator-5584`, API 33/portrait/three-button navigation. Только этот AVD устанавливался/сбрасывался fixture-тестами; физический телефон не использовался. Device proof: **OK (8 tests), 83.629s + OK (1 test), 11.902s — все 9 сценариев прошли на точном финальном APK**.

Device-набор: CheckInUiIntegrationTest (manual Invalid/Timeout/Admitted/repeat Used/recreation/Back; owner grant/revoke/owner scan; CameraX STREAMING и unbind на hidden/background/Back/revoke/logout; системный permission denial и настоящий decoder на controlled frame) + четыре ProductBackNavigationTest + guestReceiptIsOneSnapshotAcrossBackRecreationAndFlowRemoval с новым manual-code assert. Отдельный denial run подготавливается `adb -s emulator-5584 shell pm revoke ... CAMERA` и clear-permission-flags **до** instrumentation; callback denial выбирается в настоящем permissioncontroller, а не подменяется VM-флагом.

Controlled frame/ZXing → controller и CameraX STREAMING/unbind — разные доказательства. Автоматический тест не подносит бумажный QR к физической камере и не доказывает lens focus/exposure. На двух устройствах fake store не общий. Нового OS process-kill proof нет; recreate не равен уничтожению процесса. Landscape/gesture Back/API 28/37 runtime/TalkBack matrix не прогонялись заново.

Точные device-команды после установки app/test APK через `adb -s emulator-5584 install -r`:

```powershell
$adb = 'C:\Users\XataB\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb -s emulator-5584 shell am instrument -w -r -e class 'ru.eventpass.app.CheckInUiIntegrationTest#manualInvalidTransportAdmittedRepeatAndBackUseSameEntry,ru.eventpass.app.CheckInUiIntegrationTest#ownerGrantsExistingAccountAndConfirmsRevokeWithoutLosingOwnTicket,ru.eventpass.app.CheckInUiIntegrationTest#cameraIsReleasedOnHiddenTabBackgroundBackAndRevoke,ru.eventpass.app.ProductBackNavigationTest,ru.eventpass.app.RegistrationTicketsTest#guestReceiptIsOneSnapshotAcrossBackRecreationAndFlowRemoval' ru.eventpass.android.demo.test/androidx.test.runner.AndroidJUnitRunner
& $adb -s emulator-5584 shell pm revoke ru.eventpass.android.demo android.permission.CAMERA
& $adb -s emulator-5584 shell pm clear-permission-flags ru.eventpass.android.demo android.permission.CAMERA user-set user-fixed
& $adb -s emulator-5584 shell am instrument -w -r -e class 'ru.eventpass.app.CheckInUiIntegrationTest#realDecoderOnControlledFrameFeedsScannerAndDenialKeepsManualAvailable' ru.eventpass.android.demo.test/androidx.test.runner.AndroidJUnitRunner
```

Evidence: `.gradle/t07-ui-evidence/` — release-verification/final-callback-check logs, device-release/device-release-denial logs (ранние device-final/device-denial-decoder сохранены отдельно), XML unit-results, lint.xml, output-metadata.json/signature.txt, screenshots. Архитектурные canonical/maps обновлены в рабочих текущих sections; отдельная собственная t07-ui-lifecycle page входит в коммит. Большой предсуществующий пакет архитектуры/API/prompts/CODE_REVIEW не включается автоматически в коммит. Смешанные README/06/08/21 stage отделяется от пользовательской базы; остальные собственные уточнения canonical/maps остаются поверх неё в рабочем дереве. CODE_REVIEW/AGENTS/API-файлы не менялись.

Структурная проверка python scripts/verify-architecture-docs.py прошла: 12 текущих страниц, 215 локальных ссылок, 9 Mermaid blocks, 18 mobile IDs, S01–S14/named test references; git diff --check прошёл. Новый lifecycle описан таблицами/текстом; Mermaid rendering не выполнялся.

## Ручной smoke T07

Seed `t02-v1`; `host@example.com` / `alex@example.com`, пароль **DemoPass123!**. Event Clock по-прежнему `2026-10-27T09:00Z`, auth Clock UTC; существующие данные при обновлении сохраняются. [Demo QR «Мастер-класс Kotlin»](../artifacts/demo/t07/alex-attending.png), [QR «Командный хакатон»](../artifacts/demo/t07/alex-mixed.png), [manual credentials](../artifacts/demo/t07/credentials.json). Эти артефакты только seed demo, не production credentials; после пользовательского reset снова соответствуют seed. Значения не печатаются в обычные логи. Открыть PNG на другом экране либо распечатать.

1. Host → MyEvents → «Мастер-класс Kotlin» → Volunteers → выдать Alex право; неизвестный email даёт ошибку, повтор grant не дублирует строку. Войти Alex → «Волонтёр» → этот E → Scanner. Проверить manual по artifact и результат Admitted; повтор — AlreadyUsed. Проверить, что свой билет сохранён, теперь Used в истории без QR/code. Владелец также открывает Scanner без self-grant.
2. Для ATTENDING использовать QR MIXED → WrongEvent, неизвестный корректный manual → Unknown. Для Cancelled отдельно отменить ещё Active билет MIXED и сканировать его в MIXED; не пытаться отменить уже Used. Network/Timeout в UI не превращаются в допуск (эти fault branches автоматизированы).
3. Отказать CAMERA; manual доступен. Повторный/постоянный отказ → объяснение/settings; разрешить и явно начать камеру. Прочитать настоящий QR с другого экрана/бумаги, проверить фокус/слабый свет. Result останавливает анализ; next начинает новый цикл. Системный и верхний Back результата → ready, затем MyEvents, без растущего стека.
4. С активной камерой свернуть приложение, сменить таб, повернуть, выйти из аккаунта; убедиться, что камера освобождена и старого допуска нет. Revoke у owner и возврат volunteer → доступа к E нет, личный билет/участие сохраняются. В одном fake смена личности/возврат проверяет доступ после новой проверки; реальный одновременный revoke двух устройств требует backend.

T07 — READY_FOR_HUMAN до ответа человека по camera/UI. T05/H2 и T06 не переоткрывались: Back/guest regressions проходят. Следующая функциональная задача — T08 dashboard/явка и соединение сценариев, полная H3 после неё.
