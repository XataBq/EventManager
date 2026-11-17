package ru.eventpass.core.domain.event

import ru.eventpass.core.domain.registration.RegistrationQuestion.Option

/** A proposal only: nonempty conflicts require an explicit user decision before saving it. */
data class DraftMergeResult(val fields: EventFields, val conflicts: Set<String>)

object DraftMergePolicy {
    fun merge(base: EventFields, local: EventFields, remote: EventFields): DraftMergeResult {
        val merger = Merger()
        // Local file reference and server descriptor form one cover intent, never mix two images.
        val cover = merger.value("cover", Triple(base.cover, base.remoteCoverAssetId, base.remoteCoverUrl),
            Triple(local.cover, local.remoteCoverAssetId, local.remoteCoverUrl),
            Triple(remote.cover, remote.remoteCoverAssetId, remote.remoteCoverUrl))
        return DraftMergeResult(EventFields(
            title = merger.value("title", base.title, local.title, remote.title),
            startsAt = merger.value("startsAt", base.startsAt, local.startsAt, remote.startsAt),
            endsAt = merger.value("endsAt", base.endsAt, local.endsAt, remote.endsAt),
            zoneId = merger.value("zoneId", base.zoneId, local.zoneId, remote.zoneId),
            venue = merger.value("venue", base.venue, local.venue, remote.venue),
            capacity = merger.value("capacity", base.capacity, local.capacity, remote.capacity),
            questions = merger.items("questions", base.questions, local.questions, remote.questions, { it.id }, merger::question),
            description = merger.value("description", base.description, local.description, remote.description),
            sections = merger.items("sections", base.sections, local.sections, remote.sections, { it.id }, merger::section),
            cover = cover.first,
            priceType = merger.value("priceType", base.priceType, local.priceType, remote.priceType),
            remoteCoverAssetId = cover.second,
            remoteCoverUrl = cover.third,
        ), merger.conflicts.toSet())
    }

    private class Merger {
        val conflicts = linkedSetOf<String>()

        fun <T> value(path: String, base: T, local: T, remote: T): T = when {
            local == remote -> local
            local == base -> remote
            remote == base -> local
            else -> { conflicts += path; local }
        }

        fun question(path: String, base: DraftQuestion, local: DraftQuestion, remote: DraftQuestion): DraftQuestion {
            // A type change and the other side's option edit cannot be combined safely.
            if (local.kind != remote.kind &&
                ((local.kind != base.kind && remote.options != base.options) ||
                    (remote.kind != base.kind && local.options != base.options))) {
                conflicts += "$path.kind"
                return local
            }
            return DraftQuestion(base.id,
                value("$path.title", base.title, local.title, remote.title),
                value("$path.required", base.required, local.required, remote.required),
                value("$path.kind", base.kind, local.kind, remote.kind),
                items("$path.options", base.options, local.options, remote.options, { it.id }, ::option),
                value("$path.placeholder", base.placeholder, local.placeholder, remote.placeholder),
                value("$path.help", base.help, local.help, remote.help),
                value("$path.rawType", base.rawType, local.rawType, remote.rawType))
        }

        fun option(path: String, base: Option, local: Option, remote: Option) = Option(base.id,
            value("$path.title", base.title, local.title, remote.title),
            value("$path.hint", base.hint, local.hint, remote.hint))

        fun section(path: String, base: EventSection, local: EventSection, remote: EventSection) = EventSection(
            value("$path.title", base.title, local.title, remote.title),
            value("$path.time", base.time, local.time, remote.time), base.id)

        fun <T : Any> items(path: String, base: List<T>, local: List<T>, remote: List<T>,
            id: (T) -> String, mergeItem: (String, T, T, T) -> T): List<T> {
            if (listOf(base, local, remote).any { values -> values.map(id).distinct().size != values.size }) {
                conflicts += path
                return local.toList()
            }
            val b = base.associateBy(id); val l = local.associateBy(id); val r = remote.associateBy(id)
            val merged = linkedMapOf<String, T>()
            (b.keys + l.keys + r.keys).forEach { key ->
                val before = b[key]; val mine = l[key]; val theirs = r[key]
                val result = when {
                    mine == theirs -> mine
                    mine == before -> theirs
                    theirs == before -> mine
                    before != null && mine != null && theirs != null -> mergeItem("$path.$key", before, mine, theirs)
                    else -> { conflicts += "$path.$key"; mine }
                }
                if (result != null) merged[key] = result
            }
            val baseOrder = base.map(id); val localOrder = local.map(id); val remoteOrder = remote.map(id)
            val shared = b.keys.intersect(l.keys).intersect(r.keys).intersect(merged.keys)
            val bo = baseOrder.filter { it in shared }; val lo = localOrder.filter { it in shared }; val ro = remoteOrder.filter { it in shared }
            val primary = when {
                lo == bo -> remoteOrder
                ro == bo || lo == ro -> localOrder
                else -> { conflicts += "$path.order"; localOrder }
            }
            val order = primary.filter { it in merged }.toMutableList()
            val secondary = if (primary === remoteOrder) localOrder else remoteOrder
            // Keep new IDs adjacent to their nearest surviving following anchor; otherwise append.
            secondary.forEachIndexed { index, key -> if (key in merged && key !in order) {
                val following = secondary.drop(index + 1).firstOrNull { it in order }
                if (following == null) order += key else order.add(order.indexOf(following), key)
            } }
            merged.keys.filterNot { it in order }.forEach(order::add)
            return order.map { merged.getValue(it) }
        }
    }
}
