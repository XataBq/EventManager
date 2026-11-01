# T07: исправления revoke dialog и optional camera

**READY_FOR_HUMAN, 1 ноября 2026.** Исправлены два замечания пользователя к code 14: скрытая модальным окном ошибка revoke и неявное требование задней камеры в APK. T07 camera/UI и H3/H4 остаются за человеком, Back/H2 и T06 приняты на code 13. Backend отсутствует; API/schema/права/check-in semantics не менялись.

## Сборка

[APK 0.7.3-t07-demo, code 15](../artifacts/apk/eventpass-0.7.3-t07-demo-code15.apk), **44 076 713 байт**, SHA-256 `0171bcc65518d65c82ba7af98be1bdd50c80f087710f2fcd357d1120a89b859d`. ApplicationId `ru.eventpass.android.demo`, min 28 / target 37, debug v2 signature проверена. App/test APK собраны текущим JBR 21/Gradle wrapper; версии библиотек прежние. Старый code 14 сохранён с прежним hash. Новый APK устанавливался только на отдельный AVD, пользовательские устройства/данные не менялись.

## Что изменилось

- VolunteersUiState.revokeError принадлежит confirmation. При Network/Timeout/Storage выбранный ID/строка остаётся, busy снимается, ошибка видна **внутри Dialog**. «Повторить» отправляет revoke того же ID; это не refresh списка. Успех закрывает confirmation; dismiss/новое подтверждение/stop очищают ошибку. AccessDenied по-прежнему очищает private UI и возвращает MyEvents. Ошибки списка/grant отображаются на основном экране, когда нет confirmation.
- Общий ConfirmDialog получил необязательный errorText, показываемый через InlineError под описанием. Для прежних callers null сохраняет содержимое; destructive/loading/dismiss guards прежние. Карта [lifecycle](architecture/t07-ui-lifecycle.md) обновлена.
- Manifest явно объявляет `android.hardware.camera`, `android.hardware.camera.autofocus`, `android.hardware.camera.any` с required=false. CAMERA permission сохранено для реального сканера; ручной ввод не требует камеры. По [официальным правилам Android](https://developer.android.com/guide/topics/manifest/uses-feature-element#permissions), одного camera.any=false недостаточно для отмены back-camera requirement, подразумеваемого CAMERA.

## Проверки

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :app:testDemoDebugUnitTest :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest :app:lintDemoDebug :app:verifyDemoIsolation -I .gradle/t06-evidence/repeat-tests.init.gradle --offline --console=plain --max-workers=2
```

**BUILD SUCCESSFUL, 1m 31s, 178 tasks: 54 executed / 124 up-to-date. 49 app JVM tests, 0 failures/errors/skipped**, включая три новые revoke-регрессии (Network/Timeout/Storage), прежние hidden/late-finally/access guards. Test task реально повторён init-скриптом; остальные задачи инкрементальны, clean не делался. Lint **0 errors / 4 прежних version notices**, DI/app/test compile и demo isolation успешны. Domain/fake/platform suites не повторялись: их исходники/контракты не менялись. Полные 138 JVM и 9 device в [24](24-t07-ui-validation.md) относятся к code 14 и не приписываются этой сборке.

На сохранённом code 14 aapt показал обязательный `android.hardware.camera` и uses-implied-feature с причиной `requested android.permission.CAMERA permission`. На code 15:

```text
uses-feature-not-required: name='android.hardware.camera'
uses-feature-not-required: name='android.hardware.camera.any'
uses-feature-not-required: name='android.hardware.camera.autofocus'
```

Нет required/uses-implied-feature для camera. Осталась прежняя стандартная faketouch feature. Проверена именно таблица features **упакованного APK**:

```powershell
& 'C:\Users\XataB\AppData\Local\Android\Sdk\build-tools\36.0.0\aapt.exe' dump badging artifacts/apk/eventpass-0.7.3-t07-demo-code15.apk
```

Device: **OK (4 tests), 48.404s, 0 failures**. Изолированный `EventPass_Insets_API33`, `emulator-5584`, API 33/portrait/three-button. Новая regression для всех трёх fault outcomes требует, чтобы сообщение и retry имели **isDialog ancestor**, проверяет видимость/сохранённый grant и успешный retry того же ID. Дополнительно normal grant/revoke/owner scan, отмена личного билета и dialog/fullscreen/event Back через прежний ConfirmDialog.

```powershell
$adb = 'C:\Users\XataB\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb -s emulator-5584 shell am instrument -w -r -e class 'ru.eventpass.app.CheckInUiIntegrationTest#revokeErrorsAreVisibleInDialogAndRetryRevokesTheSameVolunteer,ru.eventpass.app.CheckInUiIntegrationTest#ownerGrantsExistingAccountAndConfirmsRevokeWithoutLosingOwnTicket,ru.eventpass.app.RegistrationTicketsTest#authenticatedSubmitSelectsTicketAndCancellationMovesItToHistory,ru.eventpass.app.ProductBackNavigationTest#dialogAndEventBackReturnToCardBeforeReturningToList' ru.eventpass.android.demo.test/androidx.test.runner.AndroidJUnitRunner
```

Evidence — `.gradle/t07-fix-evidence/{verification.log,device.log,aapt-code14.txt,aapt-code15.txt,apk.json,signature.txt,unit-results/,lint.xml}`. `python scripts/verify-architecture-docs.py` и `git diff --check` выполняются перед передачей. CODE_REVIEW и посторонние изменения сохранены; review skill не запускался. Смешанные README/06/08/21 и canonical architecture правки отделяются от предсуществующего пользовательского пакета при коммите.

Google Play upload/filtering на реальном аккаунте, устройство без задней камеры, API 28/37 runtime и физическая camera/UI-приёмка не выполнялись. Вывод о feature requirements основан на aapt и официальных правилах; локальная установка APK сама по себе не доказывает Play visibility. CameraX выбирает заднюю камеру как прежде; без неё предусмотрен camera error/manual fallback, поддержки front-camera preview это исправление не добавляет.

## Ручная перепроверка

Установить code 15 обновлением; seed `t02-v1`, аккаунты `alex@example.com`/`host@example.com`, пароль `DemoPass123!`. Schema 4 и demo данные сохраняются. Для штатного пути: Alex → свои Volunteers → grant Host → revoke, confirm/dismiss/retry должны работать без скрытых сообщений. Network/Timeout/Storage управляемо проверены device-тестом через общий fake; реального backend ещё нет. На устройстве без задней камеры ручной ввод должен оставаться доступен; физический camera smoke/QR с бумаги — по [T07 checklist](24-t07-ui-validation.md).

T07 остаётся READY_FOR_HUMAN. Следующая функциональная задача — T08, H3 целиком после неё.
