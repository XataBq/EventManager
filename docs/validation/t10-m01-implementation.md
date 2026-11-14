# M01 implementation report

## STATUS / BASE

14 ноября 2026. **REVIEW_PENDING** после перечисленных ниже автоматических проверок; человеческая приёмка ожидает M09. Base `bd6c29567d355c7eb0de2dabef194d40b02edb51` на codex/t10-api-integration. Коммит делает root после заморозки; CODE_REVIEW.md исполнителем не изменялся.

## SCOPE

- `core/domain`: AuthRepository/SessionState/consent/mail-link ports, nullable реально известный expiry, mandatory consent; typed FeatureUnavailable и снятие verified gate общей access policy.
- `data/remote` (новый): AuthHttp, RemoteAuthRepository, HTTP/session regression tests и opt-in staging adapter smoke. OkHttp/MockWebServer3 5.5.1, serialization существующая 1.9.0.
- `core/platform`: SessionTokenStore AEAD/Keystore/AtomicFile/noBackup implementation, JVM codec tests и device key lifecycle/IO tests. Новый чистый domain port SessionTokenStore.
- `app`: remoteDebug flavor/DI/Navigation3 auth/profile host; существующие demo hosts, gallery/tools/demo VM живут demo-only; явные unavailable bindings временно обслуживают уже существующие future ports без fake/ложного NETWORK. Auth VM/screens без code/grant, signup→session, browser mail flow, consent config, remote ACTION_VIEW ignore+intent cleanup. Version 0.10.1-m01, code21.
- `app` tests: fake-dependent unit suite перемещена в testDemo, cohesive existing device demo suite в androidTestDemo; чистые тесты остаются shared, отдельные testRemote/androidTestRemote для URI boundary. Смысл auth tests адаптирован к новым правилам; тесты не удалялись ради зелёной сборки.
- `data/fake`: отдельный согласованный helper изменил auth и все verified auth guards; EPA2 auth magic сохраняет proofs/accounts/names/verify links, отбрасывает legacy reset grants; product schema 5 unchanged. Независимые старые binary fixtures сохранены.
- `settings.gradle.kts`, version catalog, app/platform/remote Gradle — только необходимые module/flavor/test/runtime dependencies. Общий toolchain не обновлялся.
- Текущие 02/03/05, architecture owners/operations/invariants и source links обновлены; [фактическая карта M01](../architecture/t10-session-transport.md). verifier теперь видит test/testDemo/testRemote.

Исходные dirty документы скопированы перед изменениями в `.gradle/m01-baseline`; остальные пользовательские изменения сохранены. Root отдельно сверяет итоговый scope/manifests перед commit; .gradle logs не входят в product source. Журнал35 root-owned.

## REQUIREMENTS

| Критерий | Реализация / evidence | Результат |
|---|---|---|
| Реальный signup/login→profile без verify gate | RemoteAuthRepository + UserDto/profile; staging smoke через новый adapter | Успех; fake/domain/navigation согласованы |
| Нет выдуманного token expiry/id | nullable Session.expiresAt; random local id; тест signupCreatesUnverifiedSessionWithNoInventedExpiryAndRedactedScope | Успех |
| Защищённый persistent token | AndroidSessionTokenStore, 4 JVM record tests + 4 real Keystore device tests | Успех, новый instance/key invalid/corrupt/atomic failure |
| Recoverable bootstrap | SessionState.Recovery, retry UI; HTTP/IO errors не превращаются в Authenticated/вечный Loading | Unit success |
| Late401/login401/logout race | epoch guard, early logout invalidation, RemoteAuthRepositoryTest | Unit success |
| Cancel после durable write | компенсация + cancelledDurableWriteCompensatesBeforeAnyFutureBootstrap / logoutInvalidatesAcceptanceBeforeWaitingForStoreLock | Unit success |
| HTTP errors/body/cancel/storage | 401/403/422/429/503, malformed JSON200, empty204, timeout, cancel, foreign redirect, writefailure | 12 transport/session tests success |
| Согласия/mail/browser | /auth/consent и EmailDispatch; signup explicit consent; code/grant UI removed | Build/unit success; реальные mail links не проверялись |
| Изоляция вариантов | main imports guard, remote runtimeClasspath guard, APK DEX inspection root | Успех; нет fake/tools/gallery/demo defined classes |
| Старые тесты не потеряны | 30 source-set moves; shared tests остаются; auth semantics обновлены | Оба unit/test APK compile; полный demo device suite не прогонялся |

## ARCHITECTURE

Применены ADR-T10-01–05/07 в относящейся части; Room/draft ADR-T10-06 ещё не реализован. Server auth authoritative; Snapshot.generation/epoch локальные и не серверная версия. Credentials не сохраняются в Session/NavKey/SavedState/Room. Typed FeatureUnavailable временно используется только для существующих ports; кнопки будущих remote функций не открыты. Read-only profile берётся из того же UserDto, что Session, а не отдельной fake identity.

При auth ответе bearer сначала durable write, затем active generation/cancellation check, затем publish; отмена/смена epoch компенсирует запись. Logout инвалидация начинается до ожидания store mutex; удалённый logout best effort, локальное состояние очищается независимо от HTTP. HTTPS системный, redirect/retry отключены; auth header добавляется только к разрешённому API origin. Remote не принимает demo token URI, Activity очищает intent даже при игнорировании.

Fake verified-only gates убраны и у смежных ports; guest autoaccount/setup — M03, lifecycle/анкеты — M02+, не объявлены реализованными через auth.

## CHECKS

Все команды PowerShell из корня, JAVA_HOME = `C:\Program Files\Android\Android Studio1\jbr`. В среде Gradle TLS download одного Google artifact падал; файл tracing1.0.0 загружен Windows HTTPS с официального dl.google.com и сверён по опубликованному SHA1, SHA256 `07b8b6139665b884a162eccf97891ca50f7f56831233bf25168ae04f7b568612`. Fallback init `.gradle/m01-verified-google.init.gradle` exclusive только этому exact module/version; TLS/trust/committed repositories не ослаблялись. Проверка root `.gradle/m01-verified-google/checksums.json`.

- `.gradle/m01-sixth-build.log`: exit0, BUILD SUCCESSFUL, 354 tasks/2m34s. Команда `./gradlew.bat -I .gradle/m01-verified-google.init.gradle :app:assembleDemoDebug :app:assembleRemoteDebug :app:testDemoDebugUnitTest :app:testRemoteDebugUnitTest :core:domain:test :data:fake:test :data:remote:testDebugUnitTest :core:platform:testDebugUnitTest :app:assembleDemoDebugAndroidTest :app:assembleRemoteDebugAndroidTest :core:platform:assembleDebugAndroidTest :app:lintDemoDebug :app:lintRemoteDebug :app:verifyDemoIsolation --console=plain`.
- После последних remote host/test изменений `.gradle/m01-ninth-build.log`: exit0/1m, `EVENTPASS_STAGING_SMOKE=1`; команда `./gradlew.bat -I .gradle/m01-verified-google.init.gradle :data:remote:testDebugUnitTest :core:platform:connectedDebugAndroidTest :app:assembleRemoteDebug :app:assembleRemoteDebugAndroidTest :app:lintRemoteDebug --console=plain`.
- Актуальные JVM XML: domain20, fake92, platform12, remote13 (12 regression + 1 live staging), app demo72, app remote23; **232 test invocations, 0 failures/errors/skips**. Общие app tests выполняются на двух flavors, число не означает 232 разных сценария. Пути: `<module>/build/test-results/<task>/TEST-*.xml`.
- StagingAuthSmokeTest действительно выполнен с opt-in (0 skipped), synthetic example.com signup/session→/me→logout→unverified login→profile→DELETE собственного аккаунта→local logout; secrets/PII не выводятся и не записываются. Env input объявлен в Gradle, чтобы up-to-date не подменял smoke skipped-результатом.
- `core/platform/build/outputs/androidTest-results/connected/debug`: **4/4**, emulator-5554 Pixel_7 API33. Проверены реальный AndroidKeyStore/AtomicFile, fresh store instance restore/clear, removed key, corrupt record, failed replacement.
- APK code21: demo 44,725,902 bytes, SHA256 `1e2caa89c6632fcd720adeac9e07e8c84f37b2c3c901e32401b126a918dd6b69`; remote 45,562,185 bytes, SHA256 `cc216ec04a5e0fa8a0f062ebb32da4d7ed987e839d38d61f02400c1ed6e20f13`. Перед freeze root повторяет APK analysis/hash; источник `app/build/outputs/apk/{demo,remote}/debug`.
- Runtime dependency/source isolation task success. Root independently parsed remote DEX class definitions: 0 forbidden data/fake, app/tools/gallery/demo prefixes; `.gradle/m01-remote-dex-isolation.json`. Basic APK analysis `.gradle/m01-remote-apk-analysis.json`.
- `python scripts/verify-architecture-docs.py`: exit0, 16 pages/330 local links/9 Mermaid blocks; `git diff --check`: exit0, только CRLF normalization warnings. Это structural check, не semantic Kotlin/Mermaid rendering.
- Remote Activity device regression: normal MAIN launch и реальная ACTION_VIEW/SINGLE_TOP доставка, смена сохранённого Intent на свежий sanitized instance без data/extras. Проверяется наблюдаемый новый intent, не только неизменный старый getter. Итоговый прогон `.gradle/m01-final-build.log`: exit0, BUILD SUCCESSFUL/1m27s/254 tasks, remote connected XML **1/1**, 0 failures/errors/skips. Команда `./gradlew.bat -I .gradle/m01-verified-google.init.gradle :app:connectedRemoteDebugAndroidTest :app:assembleDemoDebug :app:lintRemoteDebug :app:lintDemoDebug :app:verifyDemoIsolation --console=plain`. Итого device 5/5 (4 platform + 1 Activity); root сверяет перед commit.

Промежуточные неуспехи сохранены в first–eighth logs: TLS, Kotlin exhaustiveness/source-set auth migration, JUnit method inferred non-void (исправлен явным Unit), test property PowerShell quoting, ActivityScenario launch matcher для намеренно мутируемого ACTION_VIEW (fixture переведён на normal MAIN + actual new VIEW delivery). Это не скрытые skipped проверки; каждый исправленный путь перепроверяется.

## NOT_RUN / RISKS

Полный demo device suite, человеческая UI/Figma/IME/fontScale/TalkBack и два физических устройства — не запускались; ручная приёмка только после M09. Device test проверяет restore новым store instance, но отдельный OS kill/restart authenticated remote UI ещё не доказан. Setup/reset/verify реальными письмами не проверены: Mailpit access и заполненные URL документов остаются внешними условиями финальной приёмки. Server consent URL сейчас nullable, UI честно отображает отсутствие. Политики будущих Event/guest/ticket/scanner/files/Room не заменяются этим M01. Release/signing не включены. Backend write revision отсутствует, его риск относится к M05, не скрыт.

Точная первичная проверка библиотек: Maven Central okhttp/mockwebserver3 metadata5.5.1 и опубликованные POM; okhttp-jvm5.5.1 → kotlin-stdlib2.1.21/okio-jvm3.18.1. Фактическая совместимость с существующим AGP/Kotlin/min28 подтверждена перечисленными build/tests, не заявляется для будущих обновлений.

## HANDOFF

После независимого review M01 следующий M02: EventId/slug/LIVE/effectiveEndsAt/capacity/question types. Не предполагать, что remote product ports уже работают: временные FeatureUnavailable заменять целевыми adapters по очереди. Сохранять session epoch guards, entry ownership и отсутствие credential persistence вне TokenStore. Не возвращать verified-only/login challenge/reset-code semantics из старых отчётов. Не удалять demo или его regression coverage. Root делает commit и назначает review; M02 до gate не начинался.
