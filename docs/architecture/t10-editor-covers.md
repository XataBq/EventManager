# M04–M05: редактор, MyEvents и обложки

M04 online owner slice принят пользователем на `8da678a` при запуске M05. Его [исторический отчёт](../validation/t10-m04-implementation.md) фиксирует исходный transient draft cache и upload-release протокол. В текущем remote DI draft-путь заменён durable Room coordinator M05; актуальные владельцы/состояния/операции/retention/cover lifecycle — [M05](t10-room-drafts.md). Прежние описание marker без тела и finally-release source больше не являются спецификацией remote draft-пути.

MyEventsTab адресует отдельную server projection: summary/items/roles/counters сохраняют состав сервера, details не вставляются в список. M05 localDrafts рисуются отдельно с дедупликацией по известному remote ID, не изменяют counters. Incomplete fields остаются nullable, typed questions/options/sections сохраняют IDs; remote revision null, не optimistic lock.

RemoteOrganizerRepository продолжает server MyEvents, owner read, online published update/finish; RemoteEventRepository сохраняет M02 public GET. AuthHttp/RemoteAuthRepository — общий bearer/epoch/401 gateway. Domain и UI не получают DTO/token/DAO. Published terminal authority — server status/finishedAt, не часы телефона. Publish выполняется по известному server ID после explicit sync без pending/conflict. Unknown PATCH требует read reconciliation, GET→PATCH не атомарен без backend precondition.

Editor VM сохраняет raw/dirty при живой configuration recreation; hidden/background отменяет reads/foreground operations с generation guards. Back save/discard/stay сохранён, pending Room отличается от несохранённого raw ввода. Nav различает local DraftId, internal EventId и public slug; public copy/share/open — HTTPS `/e/{slug}`. Scanner/Volunteers/Dashboard в remote ожидают M06/M07.

Platform importer копирует content в durable managed source, remote upload возвращает отдельный server asset descriptor. Для draft source сохраняется до DB transition/attach; для published save upload производится перед online update. Preview выполняет bounded GET по assetId через API origin без bearer на произвольный coverUrl, MIME/size проверяются, bitmap decode вне main. Exactly-once upload и server DELETE asset не обещаются.

Fake codec6/legacy fixtures не мигрируются в Room. Текущий M05 проверен только в объёме, перечисленном в [отчёте](../validation/t10-m05-implementation.md): remote compilation и ранние DAO/merge unit tests. Сценарии/lint/demo/device/UI после поздней команды пользователя не запускались; прежние M04 suites не выдаются за повторно пройденные.

## M09: нормализация server asset

Server descriptor описывает нормализованный asset: byteCount и mimeType могут отличаться от local source. RemoteCoverTransfer проверяет supported mime/положительный размер≤5MiB/dimensions и canonical assetId, сохраняя local EventCover metadata для managed source/Room. Сравнение stored bytes с исходными удалено после реального PNG1753→1480. Live upload/attach/detach и regression для uploadPending/importCover пройдены; [текущие доказательства](../validation/t10-m09-final-validation.md).
