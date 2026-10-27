# Android-стек и технические источники

Проверено по первичным источникам 2026-10-21. Это **рекомендация для T01**, не уже собранная совместимая конфигурация. При следующем уточнении 2026-10-21 выполнено первичное обследование локальных SDK/JDK/Studio, см. [отчёт о готовности](11-start-without-api.md). Набор версий сборкой ещё не проверен, Android-проект не создан.

## Рекомендуемый набор

| Часть | Решение | Почему |
|---|---|---|
| Язык | Kotlin 2.x, конкретный stable patch фиксировать в T01 | Пожелание пользователя; согласовать compiler/KSP/AGP |
| UI | Jetpack Compose + Material 3 primitives + собственный EventPass design system | Нативный декларативный UI с точными токенами Figma |
| State | ViewModel, Coroutines, StateFlow, lifecycle-aware collection | Один поток состояния экрана, общий repository source |
| DI | Hilt с KSP2 | Замена data implementation централизована; типизированные зависимости |
| Навигация | Navigation 3 stable 1.2.0 — предпочтительный кандидат | Compose-only проект; сохраняемые типизированные ключи и явный back stack |
| Минимальная OS | Android 9 / API 28 | Разумный исходный охват без требований повысить минимум |
| Build | Gradle Kotlin DSL, wrapper, version catalog | Воспроизводимость и единое место версий |
| Камера | CameraX + bundled ML Kit Barcode Scanning (QR only) | Своя камера по макету, без загрузки модели при первом проходе |
| Настройки | DataStore; Room пока не нужен | Небольшой объём настроек; persisted demo store отдельно от production cache |
| Network позже | Retrofit/OkHttp + Kotlin Serialization как кандидат | Только в data:remote после backend-контракта; можно заменить без UI-изменений |
| Время | java.time + инъекция Clock, zoneId мероприятия | API 28 позволяет использовать стандартное время; тестовые даты не стареют |
| Файлы/обложки | Android document picker / URI / image picker adapters | Без запроса широкого доступа ко всему хранилищу |
| Тесты | Unit + coroutines test, repository contract; точечные Compose/device smoke | Логика автоматизирована, визуальная приёмка человеком |

Android 9+ поддержать предлагаю. Само приложение не требует поднять минимум до Android 10/11. Статистика устройств аудитории неизвестна, поэтому это продуктовый выбор, не вывод из рыночной доли. Если позже будут требования охватить Android 8 или ограничения корпоративных устройств, пересмотреть до фиксации release.

`minSdk` не равен `targetSdk`: compile/target выбирать по актуальному стабильному SDK, совместимости toolchain и требованиям канала распространения в T01/T10. Не ставить target 28 только потому, что поддерживаем Android 9. Проверять edge-to-edge и актуальные platform behavior changes отдельно.

## Navigation 3 или Navigation 2

[Официальный release log Navigation 3](https://developer.android.com/jetpack/androidx/releases/navigation3) показывает stable **1.2.0**, выпущенную 23 сентября 2026; на дату чтения в шапке также есть alpha-ветка. Для MVP брать stable, не копировать alpha из примера зависимостей на странице.

Navigation 2 не является ошибкой и не объявляется устаревшей этой документацией. Для нового проекта без Fragment-наследия предлагаем Navigation 3. Выбор проверяем до реализации фич: три независимых таба, повторный tab click, back, deep link cold/warm start, сериализация ключей, lifecycle и Hilt ViewModel на entry.

[Сохранение state в Navigation 3](https://developer.android.com/guide/navigation/navigation-3/save-state) использует сохраняемые ключи (`NavKey`, `@Serializable`) и `rememberNavBackStack`. В T01 отдельно настроить ViewModel ownership/decorators по выбранной версии и проверить, что ViewModel очищается при удалении entry. Публичные feature callbacks не должны передавать NavController внутрь всех компонентов.

Если spike выявит несовместимость либо команда предпочитает известный Navigation Compose 2.x, зафиксировать fallback в ADR-03 до feature implementation; модели прав и repository-контракты от этого не меняются.

## Версии: исходные ориентиры, не готовый lockfile

| Источник | Что подтверждено на дату исследования | Что проверить в T01 |
|---|---|---|
| [AGP 9.2 release notes](https://developer.android.com/build/releases/agp-9-2-0-release-notes) | Ветка 9.2, есть 9.2.1; compatibility table: Gradle 9.4.1, JDK 17, max API 37.0; таблица KGP указывает 2.2.10 | Реально выбранный AGP patch, встроенный Kotlin и requirements всех plugins; не считать эту ветку автоматически самой новой |
| [Built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin) | AGP 9 включает Kotlin по умолчанию, отдельный kotlin-android больше не требуется для Android-модуля | Не подключать конфликтующие legacy plugins; pure Kotlin JVM domain имеет собственную plugin-конфигурацию |
| [Compose BOM](https://developer.android.com/develop/ui/compose/bom) | Пример stable BOM `2026.09.00`; Compose compiler с Kotlin 2.x следует версии Kotlin | Выбрать согласованный BOM; compiler plugin не входит в BOM |
| [Hilt setup](https://dagger.dev/hilt/gradle-setup.html) | Документация показывает `2.60.1`, KSP поддерживается | Проверить release notes и требования Kotlin/AGP/JDK; одна версия plugin/compiler/runtime |
| [Dagger releases](https://github.com/google/dagger/releases), [KSP releases](https://github.com/google/ksp/releases) | Первичные журналы для сверки | Не брать Hilt и KSP независимо по слову latest; собрать вместе |
| [Kotlin releases](https://kotlinlang.org/docs/releases.html) | Stable и preview имеют отдельные циклы | Выбрать именно Kotlin 2.x stable, совместимый со всеми plugins |

Не утверждаем совместимость произвольной комбинации чисел из таблицы. T01 фиксирует проверенную комбинацию целиком в version catalog и README, запускает assemble/lint/DI smoke. JVM/toolchain выбирать после проверки требований, а не по системному `java` из PATH. JDK 17 — минимум из указанной AGP-таблицы, не безусловное требование ко всем будущим библиотекам.

## Обоснование архитектуры и устройств

[Рекомендации Android по архитектуре](https://developer.android.com/topic/architecture/recommendations) поддерживают разделение UI/data, репозитории, Coroutines/Flow и однонаправленный поток данных. Наша детализация модулей и stateful fake — проектное решение, а не обязательное предписание Android.

[ML Kit Barcode Scanning](https://developers.google.com/ml-kit/vision/barcode-scanning/android) допускает bundled модель, доступную сразу, и собственный UI. Документация указывает минимум API 23; выбор minSdk 28 не ограничен этим scanner API. Декодирование возможно локально, однако **проверка допуска на мероприятие всё равно требует backend**.

[Compose accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults) — источник требований к минимальным областям касания. Визуальные кнопки Figma 36/44px адаптировать с touch target не менее 48dp и без наложения зон. На физическом устройстве проверить QR, permission lifecycle и сохранение документов.

## Что ещё не выбрано

Точный Kotlin/KSP/AGP/BOM patch set; applicationId; production domain; auth/session storage; механизм realtime; формат transport API; конкретный QR encoder; библиотека image loading; формат и источник XLSX/PDF; Crash reporting/analytics. Последние два продукта наблюдаемости не подключать автоматически. Эти решения не мешают описать порты и UI, но не должны быть скрыты под заявлением «стек уже проверен сборкой».
