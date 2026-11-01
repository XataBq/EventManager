# T07: Volunteers и общий Scanner — Android wiring

Текущая UI-интеграция использует существующую [foundation](t07-checkin-foundation.md), без новой state machine/store/migration. Schema 4, ManualCodePolicy и demo auth/credential semantics прежние. Backend/API-предложение не согласованы. T06/Back приняты на code 13; T07 camera/UI ожидает человека. Build/evidence — [24](../24-t07-ui-validation.md).

## Ответственность

| Владелец | Ответственность |
|---|---|
| [PrivateShell](../../app/src/main/kotlin/ru/eventpass/app/navigation/EventPassApp.kt) | ScannerKey/VolunteersKey с eventId/UUID, исходный таб/стек; current visible flag; top/system Back; denied → MyEvents + сообщение |
| [ScannerViewModel](../../app/src/main/kotlin/ru/eventpass/app/feature/checkin/ScannerViewModel.kt) | Создаёт ровно один прежний ScannerController с viewModelScope; transient manual input/camera failure; onCleared → close |
| [ScannerRoute](../../app/src/main/kotlin/ru/eventpass/app/feature/checkin/ScannerScreen.kt) | Lifecycle RESUMED + current entry, runtime CAMERA, launcher/settings, camera preview только при current access/permission/scanning |
| [VolunteersViewModel](../../app/src/main/kotlin/ru/eventpass/app/feature/volunteers/VolunteersViewModel.kt) | Owner list/grant/revoke через прежний порт; transient input/confirmation/busy, generation и ensureActive после suspend |
| [VolunteersRoute](../../app/src/main/kotlin/ru/eventpass/app/feature/volunteers/VolunteersScreen.kt) | RESUMED/current → start, hidden/background → stop; confirmation, owner-only error/empty/retry; denied → MyEvents |
| [QrCameraPreview](../../core/platform/src/main/kotlin/ru/eventpass/core/platform/QrCameraPreview.kt) | PreviewView + ImageAnalysis, bind/unbind своих use cases; single worker, latest-frame backpressure, close ImageProxy в finally |
| [QrFrameDecoder](../../core/platform/src/main/kotlin/ru/eventpass/core/platform/QrFrameDecoder.kt) | Y plane stride/pixel stride/crop → packed luminance → ZXing QR candidates; empty/multiple/rotation, без ticket semantics |

ViewModel/Screen не знают fake/remote/DTO/хранение. Platform не знает repository/actor/разрешение прохода. Только CheckInRepository решает исход операции; никакого offline admission. Hilt bindings уже были в demo foundation, не продублированы.

## Lifetime и Back

MyEvents показывает Scanner владельцу и active volunteer, Volunteers — только owner-список. Это видимость кнопок, repository повторно проверяет доступ. Владелец не получает self-grant. Входы открывают entry текущего таба, результат не добавляет NavKey.

Scanner StateFlow — прежний ScannerState. VM хранит manual input только в памяти, submit сразу очищает строку. Credential не попадает в route/SavedStateHandle/log. Controller устанавливает submitting синхронно, получает один outcome и сверяет поколение/current access. Preview исчезает при submitting/result/error. Result Back → ready; следующий Back удаляет ScannerKey. Top bar использует тот же VM back, повтор текущего таба также проходит этот путь. Next — явное начало нового цикла, без автоматического retry команды.

Permission-request marker сохраняется как boolean для объяснения/перехода в настройки. Реальный CAMERA grant заново читается из ОС на RESUMED; marker не выдаёт доступ. Первый запрос только по кнопке. После denial/permanent denial ручной ввод остаётся доступным. Возвращение из системного окна/фона может оставить ready: для камеры предусмотрено явное «Сканировать QR».

Route передаёт foreground только для visible/current и RESUMED. На hidden/background/dispose controller отменяет pending operation и очищает result; input также удаляется. Пересоздание Activity возвращает ready, старый допуск не показывается. Новый процесс создаёт новый controller и заново проверяет access. GuestReceiptState сохранён как раньше, его snapshot уже содержит детерминированный ManualCode; постоянного guest lookup/cache нет.

AccessDenied от observer или команды удаляет результат/PII, останавливает preview и возвращает доступный MyEvents с сообщением. Logout/expiry/scope replacement уничтожает parent/entry owners; camera preview dispose закрывает ресурсы. Transport/storage не Invalid и не допуск; UI показывает отдельную ошибку, повтор только явный ввод/кнопка. Первый CheckInRecord может быть null только у старого Used; UI не выдумывает checker/time.

## Камера и callbacks

В одном preview cycle один executor, ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST, каждый ImageProxy.close в finally. CameraX отдаёт YUV; читается только Y plane с учётом padding/stride/crop. ZXing Core 3.5.4 уже присутствовал для генерации; второй decoder не добавлен. Несколько распознанных строк упорядочены; ScannerController выбирает одну и игнорирует следующие frames во время запроса.

Atomic stopped/delivered блокируют поздние provider/analyzer callbacks. Распознанный результат передаётся на main dispatcher с generation текущего цикла. Dispose clearAnalyzer/unbind только собственных Preview/ImageAnalysis/shutdown executor; unbindAll не применяется. Pending provider listener после close ничего не связывает. Ошибка hardware/bind даёт честную camera failure и оставляет manual, без ложного Invalid.

CameraX 1.6.2 (core/camera2/lifecycle/view) закреплён в catalog, manifest CAMERA + optional camera.any. Media/storage permissions не добавлялись. PreviewView приносит транзитивные camera-video/media3 artifacts; видеозапись приложение не выполняет. Версия проверена [официальными release notes](https://developer.android.com/jetpack/androidx/releases/camera); стратегия/close — [официальный analyze guide](https://developer.android.com/media/camera/camerax/analyze). Проверенное сочетание ограничено текущим demoDebug, minSdk declaration 28 и AVD API 33; runtime других API не выводится из успешной компиляции.

## Проверки и ограничения

- ScannerUiWiringTest: manual без камеры, очистка credential, Back/result, camera error/manual, revoke без старого результата.
- VolunteersViewModelTest: state после grant/error/revoke, denied без PII, hidden/new entry/поздний finally.
- QrFrameDecoderTest: настоящий ZXing, padding/pixel stride/crop/90°/multiple/blank/truncated.
- CheckInUiIntegrationTest: адресные Android маршруты/manual/permission/hidden/background/Back/revoke/logout и bound/unbound CameraX, controlled decoder → controller; точные успешные/неуспешные прогоны в отчёте.

CameraX bind/unbind и контролируемый decoder не доказывают физическую камеру, фокус/экспозицию, чтение бумаги/слабый свет или два устройства с общим сервером. Нужна ручная camera/UI-приёмка T07; полная H3 после T08. Dashboard/attendees/export/remote не входят в этот срез.
