# EventPass — Android

Android MVP: Kotlin/Compose, Hilt/MVVM, minSdk28. Основная поставка — remote с production API; demo остаётся изолированным вариантом.

**M09: PARTIAL_EXTERNAL / HUMAN_PENDING, code26.** Точный APK, commit, результаты и единый checklist — [отчёт M09](docs/validation/t10-m09-final-validation.md). Итоговую ручную приёмку подтверждает пользователь. Внешние mail/legal/staging ограничения не отмечаются PASS.

## Текущая реализация

Три независимых корневых стека: билеты, мои мероприятия и профиль. Общие Event/Editor/Scanner/Volunteers/Dashboard/Profile обслуживаются repository ports через DI. Remote использует серверные id/slug, сессию, билеты, права и check-ins; локальные черновики хранятся в настоящем Room с явной синхронизацией. Сервер определяет права, места и успешность операций.

Модули: app, core:domain, core:designsystem, core:platform, data:fake, data:remote, data:local. data:local:verification — отдельный JVM harness тех же production Room sources, не runtime модуль. Fake/tools/gallery и локальные PDF/XLSX writers подключены только demo. Remote загружает серверные файлы.

Сборка **code38 / 0.10.21-instant-navigation-remote** переключает корневые вкладки без затухания и меняет экран вместе с шапкой при входе в «Билеты из PDF» и выходе обратно. APK установлен на Redmi. [Навигация](docs/03-navigation.md).

Заставка запуска и скелетоны загрузки проверены тремя адресными UI-тестами на Android 9 в code36. [Запуск и загрузка экранов](docs/02-architecture.md#первый-запуск-и-загрузка-экранов).

Горизонтальная анимация PDF-билетов в карточке и развёрнутом коде проверена двумя UI-тестами на Android 9 в code35. [Архитектура, обработка и результаты AIP-004](docs/architecture/aip004-ticketvault.md).

## Сборка и проверки

Android Studio Quail 2026.1.2, JBR21; wrapper Gradle9.5.0 / AGP9.3.3, Kotlin2.4.21, Hilt2.60.1, KSP2.3.12. Версии закреплены в [каталоге](gradle/libs.versions.toml); стек при M09 не обновлялся. SDK compile/target37, min28, bytecode17. Состав и фактическая совместимость проверяются сборками и тестами, не номерами версий отдельно.

PowerShell из корня проекта:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :app:assembleRemoteDebug :app:assembleRemoteRelease :app:assembleDemoDebug
.\gradlew.bat :core:domain:test :data:fake:test :data:remote:testDebugUnitTest :data:local:verification:test :core:platform:testDebugUnitTest :app:testDemoDebugUnitTest :app:testRemoteDebugUnitTest
.\gradlew.bat :app:lintRemoteDebug :app:lintRemoteRelease :app:lintDemoDebug :app:verifyDemoIsolation
python scripts/verify-architecture-docs.py
```

При ограничениях локального Maven cache точные использованные init scripts/offline команды приведены в отчёте M09. Не отключать проверку TLS. Staging JVM smoke включаются отдельно `EVENTPASS_STAGING_SMOKE=1` и создают только свои synthetic fixtures. Live device smoke требует `m09Staging=1` и изолированного AVD; обычные connected tests не должны запускать его без opt-in.

`remoteRelease` оптимизируется R8/resource shrink и собирается **без подписи**. Production signing key не предоставлен; API URL настроен. Проверочная копия release подписывается только существующим debug test key для локального runtime smoke; это не production поставка. `demoRelease` отключён. Обычный acceptance APK — remoteDebug.

API base по умолчанию `https://eventpass.dikanskii.ru/api/`; build property `eventpass.apiBaseUrl` принимает только HTTPS directory без credentials/query/fragment. Ссылки мероприятий используют production origin; App Links отложены. Backend не предоставляет If-Match: ограничение двух устройств принято пользователем и не скрывается за localRevision.

## Приёмка и доступ

[Единый checklist и идентификация APK](docs/validation/t10-m09-final-validation.md). Автоматические проверки не закрывают visual/TalkBack/camera/human acceptance.

Тестовый аккаунт на почте пользователя сохраняется; автоматически не удалялся. Его секреты находятся только в игнорируемом `.gradle/m09-evidence/mail-account.private.json` и не входят в Git/APK. Для проверки удаления создавайте отдельный временный аккаунт.

Terms/privacy backend пока возвращает без URL/version; [проект политики](docs/legal/privacy-draft.md) и [условий](docs/legal/terms-draft.md) подготовлены для оператора. Реальную доставку/открытие browser-писем пользователь разрешил отложить до исправления backend. Это не подтверждённый email smoke и не юридическое утверждение документов.

## Документы

[Карта чтения](docs/00-documentation-map.md), [архитектура](docs/02-architecture.md), [навигация/mobile-frame](docs/03-navigation.md), [инженерные правила](docs/14-engineering-rules.md), [продуктовые решения](docs/08-decisions.md), [актуальные требования backend](docs/32-backend-final-requirements.md), [миграция API](docs/34-api-migration-plan.md), [журнал исполнения](docs/35-t10-execution.md).

Исторические demo APK/приёмки и отчёты T01–T09a сохранены в `artifacts/apk` и `docs`; они не являются результатом проверки нынешнего remote APK. Посторонние исходные изменения AGENTS/CODE_REVIEW/prompts/settings сохраняются. Push и публикация не выполняются.
