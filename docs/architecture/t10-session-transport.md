# M01: фактические auth, transport и ownership

14 ноября 2026. Реализован технический remoteDebug срез signup/login → read-only profile. Другие remote функции ещё не подключены и возвращают FeatureUnavailable через существующие ports. Release отключён, человеческая приёмка ожидает M09.

## Ответственность

| Владелец | Ответственность |
|---|---|
| data:remote AuthHttp | OkHttp 5.5.1, kotlinx.serialization, cancellable async HTTP, bounded body 1 МиБ, typed status errors, no redirect/retry/logging |
| RemoteAuthRepository | Один bearer в памяти/TokenStore, /me bootstrap, SessionSnapshot generation, защищённое принятие сессии, read-only ProfileRepository |
| SessionTokenStore / AndroidSessionTokenStore | Domain порт; platform AES256/GCM Android Keystore + AtomicFile/noBackup, IO/Mutex, recovery corrupt/missing key, IOException при storage failure |
| AppSessionViewModel / SessionCoordinator | Auth gate, private/public entry owners, безопасный return target; подтверждённый email не является gate |
| app demo / remote hosts | Отдельный source-set composition root, общие auth ViewModels/Screens; Navigation3 entry decorators, private owner очистка при logout |
| RemoteModule | Реальные auth/profile/Clock/TokenStore bindings; остальные существующие ports — временный UnavailableRemoteFeatures |

app/main не импортирует concrete fake/remote или demo tools/gallery. data:remote зависит от domain; platform реализует TokenStore. data:local/Room ещё отсутствует. Fake остаётся только demo implementation/testDemo, не network fallback.

## Протокол и состояние

Signup с обязательным consentAccepted и login возвращают Session. Session.id — локальный случайный несекретный scope, bearer в Session не хранится. expiresAt nullable: remote не знает deadline, fake знает свой. Unverified email допускает auth и права на конкретный E. Значение emailVerified — информация аккаунта, не user role.

Bootstrap: Unknown → TokenStore.read → GET /me → Authenticated. Пустое хранилище даёт Guest, ошибка сети/сервера/декодирования/чтения даёт Recovery с явным retry. Непроверенный сохранённый Account не используется как разрешение. На 401 текущего bearer — локальная инвалидация; login401 не выбивает существующую session, late protected401 старого epoch не выбивает новую. 403 отличим и не вызывает глобальный logout.

Session epoch меняется до ожидания mutex для auth command/logout. Принятие: ответ → проверка актуальности/cancellation → durable TokenStore.write → повторная проверка epoch/cancellation → публикация SessionSnapshot. Если запись была отменена/не подтверждена, компенсация удаляет bearer и не позволяет следующему bootstrap незаметно принять отменённый login. CancellationException не превращается в обычную network ошибку.

Logout очищает локальную память, TokenStore и private owner независимо от сетевого результата; server logout best effort, без гарантии удалённого отзыва при отсутствии сети. Ошибка удаления ключа/файла требует явного storage recovery. Пароли/setup/reset токены/полные анкеты не пишутся в saved state.

## Ссылки и auth UI

Code/resetGrant шагов в общем UI нет. Mail requests возвращают EmailDispatch без выдуманного challenge ID/expiry. Конфигурация terms/privacy приходит через /auth/consent; null отображается как отсутствие серверных документов, не как рабочая ссылка. Signup требует явного consent.

Remote ACTION_VIEW не принимает demo/auth URI: variant boundary возвращает false, Activity всё равно очищает data/action перед сохранением состояния. Setup/reset/verify выполняются browser web-страницей; при возвращении приложение перечитывает /me. Verify сохраняет session, смена пароля требует ручного login. App Links отложены.

Demo outbox поддерживает verify/reset mail tokens, fake completeEmailLink нужен для имитации browser. Fake policy: 24h links и 30d session; unverified login разрешён. Auth-only snapshot EPA2 имеет magic 0x45504132, legacy auth v1/accounts/proofs/names сохраняются; старые reset code/grants отзываются. Product schema остаётся 5. Guest autoaccount/setup, event lifecycle и прочие новые product rules выполняются на последующих M.

## Проверки и предел

Точные команды/XML/APK hashes и результаты — в [отчёте M01](../validation/t10-m01-implementation.md). Модульные тесты проверяют generation/401/cancellation, IO и HTTP статусы; platform instrumentation — реальный Keystore/AtomicFile. Существующий demo suite перемещён в testDemo/androidTestDemo без удаления сценариев; remote имеет отдельные URI boundary tests. Человеческая визуальная/IME/TalkBack/два физических устройства приёмка не заявляется.
