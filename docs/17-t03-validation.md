# T03 — UI-кит и передача на H1

Дата: 27 октября 2026. Статус T03 — **READY_FOR_HUMAN**. Ручная приёмка **H1 ожидается**; агент не отмечает её пройденной. T01 также остаётся READY_FOR_HUMAN, T02 — IN_PROGRESS, продуктовые экраны T04/T05 не реализованы.

## Исправление P2 после ревью a263c21

Текущая сборка для повторной H1: **[app-demo-debug.apk](../app/build/outputs/apk/demo/debug/app-demo-debug.apk)**, `0.3.1-t03-demo`, versionCode `3`, 32170659 байт. SHA-256: `cb6d2bf9247e11c7a54c7a37eb08135fadd47bb995df79b67e47af9b06f0115b`. ApplicationId `ru.eventpass.android.demo`, minSdk 28, compile/target 37; aapt metadata и `apksigner verify` проверены. Библиотеки, toolchain и domain/fake не менялись.

Подтверждённый дефект из пользовательского отчёта: в landscape с трёхкнопочной навигацией профиль оказывался под боковой системной панелью. Shell теперь применяет/потребляет горизонтальные `WindowInsets.safeDrawing` вокруг всех слотов Scaffold; TopBar по умолчанию использует safeDrawing сверху и по горизонтали. Дочерние панели получают только непотреблённую часть: нет второго горизонтального отступа, вертикальное владение и единственный IME padding сохранены. Использованы динамические insets без привязки к модели/ширине панели; основание — [официальная документация Compose о потреблении insets](https://developer.android.com/develop/ui/compose/system/insets-ui#inset-consumption).

Добавлен `WindowInsetsRegressionTest`: portrait, landscape и reverse landscape, фактические bounds кнопки и содержимого внутри текущих systemBars/displayCutout, затем экранное нажатие через `UiAutomation.injectInputEvent` и ожидание открытия профиля. Нажатие проходит через систему и обнаруживает перехват системной панелью.

| Проверка исправления | Фактический результат |
|---|---|
| Red на исходном APK a263c21 | Портретное нажатие прошло; landscape упал на правой границе: кнопка x=2232…2358 px, безопасный правый край 2274 px при right inset 126 px |
| Assemble / lint / isolation | BUILD SUCCESSFUL, lint 0 ошибок / 6 прежних version notices, demo isolation прошла |
| App unit | 6/6, без ошибок/пропусков: ViewModel 2, navigation state 4; domain/fake/platform повторно не запускались |
| Все device-тесты, API 33 / три кнопки | 6/6, без ошибок/пропусков: DI/очистка entry/navigation/lifecycle/gallery и новая регрессия insets |
| Новая регрессия, API 33 / жесты | 1/1, все три ориентации, реальные экранные нажатия; это тот же тест в другом режиме навигации |
| Метаданные / подпись APK | versionCode 3, versionName 0.3.1-t03-demo, SDK 28/37; apksigner verify успешен |
| Адресный runtime-снимок | Три кнопки, landscape: профиль вне боковой панели; обычный adb screen tap открыл профиль |
| H1 / повторное независимое ревью | H1 ожидается; полное ревью повторно не запускалось, CODE_REVIEW.md сохранён |

Среда проверки: отдельный служебный `EventPass_Insets_API33`, профиль Pixel 7, Android 13/API 33, Google APIs Play Store x86_64, 1080×2400/420dpi, естественный вырез экрана. AVD создан в `.gradle/t03-insets-avds`; пользовательский Pixel_7 и его настройки не менялись. Для исправленной landscape-кнопки x=2106…2232 px при безопасном диапазоне x=136…2274; reverse landscape x=2096…2222 при диапазоне x=126…2264. Bounds содержимого совпадают с этими горизонтальными безопасными границами, без повторного padding. С жестами вместо фиксированного бокового отступа учитывался реальный вырез слева/справа.

Команды исправления, PowerShell из корня проекта, JBR из Quail 2026.1.2; полный device-прогон выполнен после включения трёхкнопочной навигации на этом служебном AVD, второй — после включения жестовой:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
$env:ANDROID_SERIAL = 'emulator-5554'
.\gradlew.bat :app:assembleDemoDebug :app:lintDemoDebug :app:testDemoDebugUnitTest :app:verifyDemoIsolation :app:connectedDemoDebugAndroidTest --offline --console=plain --max-workers=2
.\gradlew.bat :app:connectedDemoDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=ru.eventpass.app.WindowInsetsRegressionTest' --offline --console=plain --max-workers=2
```

Локальные доказательства (игнорируются Git): `.gradle/t03-insets-red-device.log`, `.gradle/t03-insets-threebutton.log`, `.gradle/t03-insets-gesture.log`; сохранённые XML/logcat обоих режимов — `.gradle/t03-insets-evidence/threebutton` и `gesture`. Стандартный connected report после последнего прогона содержит только отфильтрованный тест. Снимки: [исправленная оболочка](../.gradle/t03-insets-landscape-fixed.png), [профиль после экранного нажатия](../.gradle/t03-insets-profile-tap.png). Это адресная техническая проверка P2; вся визуальная матрица H1, клавиатура во всех ориентациях, TalkBack и физическое устройство остаются для человека.

Ниже сохранены исходные результаты T03 до исправления P2. Текущие APK и результаты повторной проверки указаны выше.

## Что изменилось

- `core:designsystem`: semantic colors, размеры/отступы, shapes, Inter 400/500/600/700, Material typography; тёмная тема без dynamic color.
- Кнопки Primary/Secondary/Danger/Text с enabled/disabled/loading/compact; поля, пароль с видимостью, поиск, checkbox/switch, option card и tabs со счётчиками; статусы, result marks, avatar, key-value, metric/section; empty/error/inline error и обычный/destructive confirm dialog.
- TopBar Guest/Authenticated/Nested и BottomBar с тремя установленными вкладками и настоящими векторными иконками. Области касания ≥48dp, визуальные min-height кнопок 44/36dp, текст может увеличивать высоту. Аватар растёт с крупными инициалами.
- Общие панели встроены в существующую оболочку. Исходное распределение insets (status/navigation у панелей) оказалось недостаточным для landscape; исправление описано выше. IME padding применён один раз, NavDisplay потребляет inner padding. Светлые системные значки заданы для тёмного UI независимо от системной темы.
- Галерея в `app/src/demo`: семь групп «Основа / Действия / Поля / Выбор / Данные / Состояния / Навигация». Ввод/поиск/видимость пароля, checked/selected/tab, счётчик нажатий и диалоги интерактивны; loading/disabled/error воспроизводимы. Примеры не вызывают продуктовые репозитории и не обещают бизнес-успех.
- `GalleryKey` добавлен в прежнюю Navigation 3 схему. Вход поверх demo-счётчика сохраняет его entry/owner; Back возвращает туда. Повторный вход не создаёт дубликат, переключение табов не удаляет скрытых owners. Повтор demo link из галереи возвращает существующий demo-entry.
- Добавлены previews кнопок/полей/выбора/статусов: 360/390dp, fontScale 1.3 и 2.0. Preview-функции компилируются; массовый screenshot regression не создавался.

API/файлы и происхождение assets — в [04-design-system.md](04-design-system.md) и [asset manifest](../core/designsystem/src/main/assets/sources/README.md). Изменений в domain/fake, правах, email matching и RegistrationError нет. Версии библиотек и wrapper не менялись.

## Источники и предел точности

Figma session `2b5e9949-8b3a-424a-9853-52e3741a9e01`, только чтение. Получены полный high-fidelity context и общий screenshot `35:486`, отдельные context/screenshots `35:157`, `35:217`, `35:480`. Запрос `35:285` достиг Starter limit. Использован уже полученный родительский контекст и исходный snapshot `docs/sources/figma-2026-10-21.json`; исходный файл не переписывался. Подробная визуальная сверка статусов, расширенных Android-состояний и всех панелей остаётся частью H1.

Прямые ссылки для человека:

- [Цвета 35:79](https://www.figma.com/design/i2NNhWGwunnvhZZX3GPuSR/Untitled?node-id=35-79), [типографика 35:105](https://www.figma.com/design/i2NNhWGwunnvhZZX3GPuSR/Untitled?node-id=35-105).
- [Кнопки/иконки 35:157](https://www.figma.com/design/i2NNhWGwunnvhZZX3GPuSR/Untitled?node-id=35-157), [формы 35:217](https://www.figma.com/design/i2NNhWGwunnvhZZX3GPuSR/Untitled?node-id=35-217).
- [Статусы/данные 35:285](https://www.figma.com/design/i2NNhWGwunnvhZZX3GPuSR/Untitled?node-id=35-285), [mobile navigation 35:480](https://www.figma.com/design/i2NNhWGwunnvhZZX3GPuSR/Untitled?node-id=35-480), [tab bar 35:437](https://www.figma.com/design/i2NNhWGwunnvhZZX3GPuSR/Untitled?node-id=35-437).

Inter: [официальный Google Fonts источник](https://github.com/google/fonts/tree/main/ofl/inter), SIL OFL 1.1, локальный variable TTF 876576 байт. SHA-256 `29160a80ff49ddcab2c97711247e08b1fab27a484a329ce8b813d820dc559031`. Проверены cmap кириллицы и fvar оси opsz 14–32 / wght 100–900. Font declarations используют wght 400/500/600/700; сетевой загрузки на запуске нет. Новые Material glyph — Apache 2.0, Figma glyph остаются предоставленным проектным источником. SVG/viewBox/paths сохранены локально; rect/circle переведены в эквивалентные VectorDrawable paths, stroke/alpha сохранены.

## Исходная сборка T03 и команды

Исходная сборка `a263c21` до исправления P2: `0.3.0-t03-demo`, versionCode `2`, applicationId `ru.eventpass.android.demo`, namespace `ru.eventpass.app`, minSdk 28, compile/target 37. Сохранённый локальный APK для red-проверки: `.gradle/t03-insets-before/app.apk`; обычный output APK теперь содержит версию 0.3.1. Demo-счётчик использует прежний `t01-v1`; product fake — `t02-v1`; галерея независима от seed/аккаунта.

APK 32658438 байт, SHA-256: `7912b8c741a3b9f8b88704604a5ded3e112acbf4c1b280a5fbb3864499172601`. aapt metadata и debug-подпись v2 проверены, apksigner verify успешен.

| Техническая проверка | Фактический результат |
|---|---|
| Итоговый wrapper-прогон | BUILD SUCCESSFUL; assemble, lint, JVM tests, isolation и connected tests на конечных исходниках |
| Lint | 0 ошибок / 6 прежних уведомлений о новых версиях; проверки не отключались, baseline не добавлен |
| Unit | 25/25: domain 6, product fake 11, прежний demo fake 1, app 6, platform 1; ошибок/пропусков нет |
| Device | 5/5 на EventPass_T03 API 36.1: прежние DI/entry cleanup/navigation/lifecycle/deep links плюс gallery Back/tab/recreation и интерактивные checked/selected/editable/password visibility |
| Demo isolation | Только demoDebug; fake отсутствует в main implementation |
| Assets/font | 19 непустых SVG и соответствующие VectorDrawable; исходные размеры и path data, rect/circle conversion, лицензии; cmap/fvar Inter проверены |
| Ручная H1 | Не проводилась; таблица ниже для пользователя |

Логи/отчёты: `.gradle/t03-verified.log`, `app/build/reports/lint-results-demoDebug.html`, `app/build/reports/androidTests/connected/debug/flavors/demo/index.html`, `build/test-results` соответствующих модулей. Outputs/AVD/временные capture tools игнорируются Git.

Дополнительно агент просмотрел ограниченные runtime-снимки: [кнопки при 393dp](../.gradle/t03-preview/actions-393.png), [поля с открытой IME при 393dp](../.gradle/t03-preview/fields-keyboard-393.png), [поля при 360dp/fontScale 2.0](../.gradle/t03-preview/fields-360-font2.png). В этих областях видны перенос длинной кнопки, рост полей и отдельные системные/IME отступы. Это не полный просмотр всех групп/состояний и не человеческая H1. Software keyboard включалась только в служебном AVD, `mInputShown=true` подтверждено. Его размер/шрифт после снимков возвращены к 1080×2340/440dpi и 1.0. IDE preview render отдельно не запускался.

PowerShell, рабочая папка `F:\ASProjects\MospolytechProject`, JBR 21.0.10 из Quail 2026.1.2:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :app:assembleDemoDebug :app:lintDemoDebug :core:domain:test :data:fake:test :app:testDemoDebugUnitTest :core:platform:testDebugUnitTest :app:verifyDemoIsolation --offline --console=plain --max-workers=2
$env:ANDROID_SERIAL = 'emulator-5554'
.\gradlew.bat :app:connectedDemoDebugAndroidTest --offline --console=plain --max-workers=2
```

Для итогового прогона задачи из двух команд также запускались одним вызовом wrapper. Новых Gradle-зависимостей и обновлений стека не потребовалось. Assets загружены обычным HTTPS; TLS не отключался. Пользовательские global Java/Git/Gradle/Studio settings не менялись.

Первый device-прогон прошёл на существующем Medium_Phone_API_36.1 в read-only режиме. На повторной установке его data-раздел оказался заполнен; `install-create` завершился `not enough space`. Пользовательские данные/AVD не очищались. Создан отдельный `EventPass_T03` под `.gradle/t03-avds`, на установленном API 36.1 Google APIs Play Store x86_64, Pixel 5, data 6G, без snapshot. Команды запуска служебного эмулятора и его логи — в локальной `.gradle`.

## Как открыть галерею

1. Установить указанный APK на тестовое Android-устройство или эмулятор.
2. Открыть EventPass Demo → вкладка **Билеты** → **Открыть UI-кит**.
3. Группы наверху прокручиваются горизонтально; содержимое — вертикально. Для сохранности demo-entry можно сначала открыть «Открыть демо-экран», увеличить счётчик и открыть UI-кит поверх него.
4. Закрыть галерею системным Back, верхней стрелкой или действием «Закрыть UI-кит» внизу группы.

Галерея доступна только в текущем demo source set; четвёртый продуктовый таб не добавлен. Навигационные образцы в группе «Навигация» меняют только локальное состояние и не выполняют вход. Пароль и текстовые поля — временные примеры, без постоянного сохранения. Декоративный/рабочий QR не добавлялся.

## H1 — чек-лист для пользователя

Каждая строка ниже: **ожидается подтверждение человеком на новой сборке**. Записать устройство/Android, build `0.3.1-t03-demo`, SHA-256 APK, результат/дефект.

| Проверка | Действия и ожидаемый результат |
|---|---|
| Цвета/типографика | Сравнить «Основа» с 35:79/35:105: тёмные поверхности, accent/muted/status colors, Inter 400/500/600/700 и mobile H1 26sp; кириллица читается |
| Кнопки | В «Действия» проверить Primary/Secondary/Danger/Text, disabled/loading, компактную кнопку, длинную надпись. Loading switch блокирует действие, счётчик нажатий меняется только от доступной кнопки |
| Формы | В «Поля» ввести имя/email/поиск, открыть/скрыть пароль, проверить фокус и переключение ошибки, disabled и многострочное поле. Клавиатура не закрывает доступ к активному полю и закрытию; нет двойного нижнего отступа |
| Выбор | В «Выбор» переключить consent/switch, выбрать карточку и tabs с count/0; длинные подписи переносятся, disabled не меняется |
| Данные | Сравнить Badge/Dot/ResultMark с 35:285; прочитать статусы без опоры только на цвет. Проверить длинное имя/email/карточку, пустой avatar и fallback другой письменности |
| Состояния | Empty, retry, inline error, обычный/destructive диалог. Отмена ничего не меняет, подтверждение влияет только на демонстрационный счётчик |
| Оболочка | Сверить 35:480/35:437: реальные glyph, три таба, top variants, Back. Счётчик под галереей сохраняется после tab switch/recreation и возврата |
| Размеры | 360, 390 и 412dp, fontScale 1.0/1.3; критические кнопки, поля, диалоги и bottom bar дополнительно 2.0. Нет обрезанных действий/текста и перекрывающихся touch targets |
| Системные панели / повтор P2 | В портретной и обеих альбомных ориентациях, сначала с тремя системными кнопками, затем с жестами, нажать верхний профиль: открывается «Профиль». Top/bottom actions и содержимое не заходят под боковую панель/вырез, нет второго бокового отступа. Затем в «Поля» открыть клавиатуру, прокрутить к полям и закрытию, проверить Back и отсутствие двойного нижнего отступа; светлые системные значки читаются |
| TalkBack | Порядок фокуса, подписи действий/пароля, checked/selected/disabled/error и loading; статусы читаются текстом. Проверить движение между группами и диалогом |

## Ограничения и дальнейшая работа

H1 не пройдена. Полная проверка TalkBack, весь диапазон размеров/шрифтов, реальные устройства и runtime API 28/37 остаются пользователю. Preview-функции не означают просмотр каждого IDE preview. Disabled/focus/error/loading расширяют исходные статические образцы и требуют H1. Нет T04/T05, реального QR/камеры/guest saved state, auth, production App Links, release/signing и API-интеграции.

Существующие `CODE_REVIEW.md`, `docs/prompts/` и добавленный пользователем абзац-ссылка в плане сохраняются вне коммита T03. Полное Android Code Review не запускалось. Подагенты не запускались. Следующий этап после замечаний H1 — T04 и нужное для него расширение T02, затем T05/H2.
