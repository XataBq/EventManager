# Исправление системного Back после замечаний H2

**1 ноября 2026 — READY_FOR_HUMAN для адресной перепроверки.** Пользователь сообщил: остальные проверенные пункты H2 без замечаний, но Back из карточки билета выводит из приложения, а MyEvents → страница мероприятия → Back возвращает в Tickets. Build/устройство исходной проверки не сообщены. Оба дефекта воспроизведены на сохранённом code 12 и исправлены. Приёмка исправления человеком ожидается; прежняя запись H2 сохранена как история. Организаторский smoke T06 не подтверждён, T07 остаётся независимой частью, H3/H4 ожидаются.

## Сборка

[APK code 13](../artifacts/apk/eventpass-0.7.1-t07-foundation-demo-code13.apk), **0.7.1-t07-foundation-demo**, versionCode **13**, **34 079 002 байт**, applicationId `ru.eventpass.android.demo`, minSdk 28 / target 37. SHA-256: `5afa868a6eb19e301e24b83f9da1018985c2a03a7e6e8b11d271f3cf40ff41c8`. Output: `app/build/outputs/apk/demo/debug/app-demo-debug.apk`; debug-подпись v2 проверена. Код production/UI и APK после итоговых проверок не менялись; последние изменения device-тестов пересобраны отдельно.

Использован прежний toolchain Quail/JBR 21, wrapper Gradle 9.5, AGP 9.3.3 / built-in Kotlin 2.4.21; версии библиотек не менялись. T06 UI и T07 foundation входят в сборку, Volunteers/Scanner UI и камера ещё не подключены. Общий snapshot schema 4 и reader v1–v4 не менялись. Для повторной проверки и ожидающего smoke T06 использовать code 13; старый code 11 не читает schema 4, обратной миграции/автоматического сброса нет. Старые APK code 9/11/12 сохранены.

## Причина и исправление

- Карточка билета — `TicketsUiState.selected` у root Tickets VM, а не отдельная запись Nav3. У root нет entry для pop, обработчик Back карточки отсутствовал. TicketsRoute теперь вызывает `vm.select(null)`: очищается сохранённый выбор, остаётся сегмент current/history. На списке handler выключен. Fullscreen QR и confirmation используют собственные Dialog окна и закрываются раньше карточки.
- `NavigationState.openEvent` принудительно выбирал Tickets и очищал стек. Теперь внутренние кнопки сохраняют текущий таб/родителя и добавляют EventKey: MyEvents → E → тот же список/сегмент, editor/result → E → прежний editor/result, ticket → E → выбранная карточка. Одинаковый верхний E не дублируется.
- Внешние ссылки и ReturnDestination.Event после входа явно используют `openLinkedEvent` с прежним родителем Tickets и dedup/reset. Public flow/auth guards/guest receipt, права и persistence не менялись.

Текущие правила обновлены в [навигации](03-navigation.md), [state ownership](architecture/state-ownership.md) и [инвариантах](architecture/invariants.md); решение ADR-25 — в [08](08-decisions.md). Эти canonical/maps содержали предсуществующие пользовательские изменения; собственные уточнения внесены поверх них. В коммит не включается чужая документальная переработка/новые карты целиком. README/06/08/19 разделены с пользовательской базой при staging; CODE_REVIEW.md/AGENTS.md/API-контракт и прочие файлы не менялись этой задачей.

## Проверки

RED на прежнем коде:

- NavigationStateTest: 6 tests / 2 failures — теряется вкладка Events и родитель editor.
- Сохранённый code 12 с SHA-256 `ef163233826196c8006c05e8da12aebbdf0c2fdb5a400b6a1582136ad2ae9c69`, новый test APK: 2 device tests / 2 failures. После Back карточки нет Compose hierarchy; при открытии E из MyEvents `tab-Events` не выбран.

GREEN:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :app:testDemoDebugUnitTest :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest :app:lintDemoDebug :app:verifyDemoIsolation -I .gradle/t06-evidence/repeat-tests.init.gradle --offline --console=plain --max-workers=2
```

BUILD SUCCESSFUL, 1m 49s, 178 tasks / 33 executed. **41 app JVM-тест, 0 failures/errors/skipped**, включая четыре новые проверки стеков, восемь прежних auth-регрессий и late-result/finally tests. Init принудительно исполнил Test task; compile/assemble/lint частично инкрементальны, clean не выполнялся. Domain/fake/platform unit suites не запускались заново: их исходники/контракты не менялись. Прежние 126 JVM в отчёте T07 не выдаются за новый полный прогон. App/test APK и Hilt compilation успешны, demo isolation прошла; lint **0 errors / 4 прежних version warnings**, baseline/исключений нет.

После уточнения device-тестов повторно выполнены `:app:assembleDemoDebugAndroidTest :app:lintDemoDebug` — BUILD SUCCESSFUL, 9s. Production APK тот же.

Устройство: отдельный существующий AVD `EventPass_Insets_API33`, `emulator-5584`, API 33, portrait, трёхкнопочная навигация (`navigation_mode=0`). Пользовательский телефон не использовался. Установка code 12 → code 13 с `adb -s emulator-5584 install -r`, затем test APK; состояние fixture сбрасывается только тестами в этом AVD.

```powershell
$adb = 'C:\Users\XataB\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb -s emulator-5584 shell am instrument -w -r -e class 'ru.eventpass.app.ProductBackNavigationTest,ru.eventpass.app.NavigationSmokeTest,ru.eventpass.app.EntryOwnershipTest,ru.eventpass.app.EventEditorIntegrationTest#savedDraftRecreationPublishGuestRegistrationAndSchemaLockShareOneStore' ru.eventpass.android.demo.test/androidx.test.runner.AndroidJUnitRunner
```

**OK (10 tests), 78.843s.** Четыре новые ProductBackNavigationTest + четыре NavigationSmokeTest + один EntryOwnershipTest + усиленный editor integration. Настоящий `KEYCODE_BACK`, не вызов callback/legacy dispatcher: current ticket/recreation/очистка selection после нового recreation, history/сохранение сегмента, fullscreen и confirmation прежде карточки, ticket → E → card → list, MyEvents owned/volunteer/rotation/hidden-tab возврат, editor publication → E → тот же result, затем прежний guest registration/schema lock flow. Дополнительно прежние demo link/tab/entry lifecycle checks.

Первый диагностический GREEN device-прогон дал 3/4: history сразу после Back ещё видел selected-ticket. Отдельная диагностика с проверкой скрытой IME прошла (1/1), оставшаяся клавиатура как причина не подтверждена. Тесты теперь явно закрывают IME после login и ждут наблюдаемый результат системного Back вместо немедленной проверки; нет sleep/retry команды Back. Итоговый набор 10/10 прошёл. Этот промежуточный неуспех не считается успешной проверкой приложения.

Evidence: `.gradle/back-navigation-baseline/{red-build.log,red-test-apk.log,red-device.log,red-unit-results/,verification.log,green-unit-results/,green-device.log,history-diagnostic.log,final-test-build.log,final-device.log,lint.xml,signature.txt}`. В UI/логах не добавлены QR credentials/анкеты/пароли. `python scripts/verify-architecture-docs.py` прошёл: 11 current pages, 194 local links, 9 Mermaid blocks, 18 mobile IDs, S01–S14 и named test references. `git diff --check` прошёл; Mermaid диаграммы в этой задаче не менялись, rendering не выполнялся.

Не проверялись заново: реальный жест Back/predictive animation, landscape, API 28/37 runtime, физическое устройство и OS process death. Recreation проверяет Activity, а не уничтожение процесса. Нового полного ревью/ручной визуальной приёмки нет.

## Ручная перепроверка

Установить code 13 обновлением. Demo: `alex@example.com`, пароль `DemoPass123!`; seed `t02-v1`, Event Clock фиксирован как раньше. При сохранённых изменениях demo выбрать имеющийся билет/сегмент; автоматического сброса данных нет.

1. «Мои билеты» → карточка → системный Back: остаётся список билетов, приложение не закрывается. Повторить для «Истории»; сегмент сохраняется.
2. Карточка → fullscreen QR → Back: карточка. Ещё Back: список. Аналогично открыть отмену и вернуться Back без отмены регистрации.
3. «Мои мероприятия» → «Организую» → «Открыть страницу мероприятия» → Back: исходный список «Мои мероприятия». Повторить из «Волонтёр», затем после поворота/переключения вкладок.
4. Из карточки билета открыть мероприятие → Back: та же карточка. Из результата публикации T06 открыть страницу → Back: тот же результат. Полный организаторский smoke T06 — отдельный checklist [20](20-t06-validation.md), на этой же новой сборке.

После подтверждения пользователем закрывается адресное замечание Back H2. Это не принимает организаторский smoke T06/H3/H4 автоматически. Следующая функциональная работа — оставшийся UI T07 после подтверждения T06.
