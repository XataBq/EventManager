package ru.eventpass.core.domain.event

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.core.domain.registration.RegistrationQuestion.Option

class DraftMergePolicyTest {
    @Test fun disjointScalarsAndIdenticalEditsMergeWithoutChangingInputs() {
        val base = EventFields(title="Before",venue="Hall")
        val local = base.copy(title="After",description="Same")
        val remote = base.copy(venue="New hall",description="Same")
        val result = DraftMergePolicy.merge(base,local,remote)
        assertTrue(result.conflicts.isEmpty()); assertEquals("After",result.fields.title)
        assertEquals("New hall",result.fields.venue); assertEquals("Same",result.fields.description)
        assertEquals("Before",base.title); assertEquals("Hall",local.venue)
    }
    @Test fun explicitNullIsARemovalAndDivergentScalarRemainsAConflict() {
        val base=EventFields(title="Before",endsAt=Instant.parse("2026-11-17T12:00:00Z"),capacity=10)
        val result=DraftMergePolicy.merge(base,base.copy(title="Local",endsAt=null),base.copy(title="Remote",capacity=20))
        assertEquals(setOf("title"),result.conflicts);assertEquals("Local",result.fields.title)
        assertNull(result.fields.endsAt);assertEquals(20,result.fields.capacity)
    }
    @Test fun questionDeletionAgainstRenameRequiresDecisionWhileUnchangedDeletionIsApplied() {
        val q=DraftQuestion("q",title="Before")
        val base=EventFields(questions=listOf(q),sections=listOf(EventSection("Session","10:00","s")))
        val local=base.copy(questions=emptyList(),sections=emptyList())
        val remote=base.copy(questions=listOf(q.copy(title="Renamed")))
        val result=DraftMergePolicy.merge(base,local,remote)
        assertEquals(setOf("questions.q"),result.conflicts)
        assertTrue(result.fields.questions.isEmpty());assertTrue(result.fields.sections.isEmpty())
        val reverse=DraftMergePolicy.merge(base,remote,local)
        assertEquals(setOf("questions.q"),reverse.conflicts);assertEquals("Renamed",reverse.fields.questions.single().title)
    }
    @Test fun nestedOptionMetadataAndIndependentAdditionsKeepStableIds() {
        val q=DraftQuestion("q",title="Question",kind=QuestionKind.SELECT,options=listOf(Option("a","Yes","Before"),Option("b","No")),help="Help")
        val base=EventFields(questions=listOf(q),sections=listOf(EventSection("Session","10:00","s")))
        val local=base.copy(questions=listOf(q.copy(help=null,options=listOf(q.options[0].copy(title="Certainly"),q.options[1]))),
            sections=base.sections+EventSection("Local","11:00","local"))
        val remote=base.copy(questions=listOf(q.copy(placeholder="Choose",options=listOf(q.options[0].copy(hint="Remote hint"),q.options[1],Option("c","Maybe")))),
            sections=listOf(base.sections.single().copy(time="10:30"),EventSection("Remote","12:00","remote")))
        val result=DraftMergePolicy.merge(base,local,remote)
        assertTrue(result.conflicts.isEmpty())
        val merged=result.fields.questions.single();assertEquals("q",merged.id);assertNull(merged.help);assertEquals("Choose",merged.placeholder)
        assertEquals(listOf("a","b","c"),merged.options.map {it.id});assertEquals(Option("a","Certainly","Remote hint"),merged.options[0])
        assertEquals(setOf("s","local","remote"),result.fields.sections.map {it.id}.toSet())
        assertEquals("10:30",result.fields.sections.first {it.id=="s"}.time)
    }
    @Test fun kindChangeAgainstOptionEditDoesNotSilentlyDiscardTheRemoteEdit() {
        val q=DraftQuestion("q",kind=QuestionKind.SINGLE_CHOICE,options=listOf(Option("a","Yes"),Option("b","No")))
        val base=EventFields(questions=listOf(q))
        val local=base.copy(questions=listOf(q.copy(kind=QuestionKind.TEXT,options=emptyList())))
        val remote=base.copy(questions=listOf(q.copy(options=listOf(q.options[0].copy(title="Renamed"),q.options[1]))))
        val result=DraftMergePolicy.merge(base,local,remote)
        assertEquals(setOf("questions.q.kind"),result.conflicts);assertEquals(local.questions,result.fields.questions)
    }
    @Test fun incompatibleReordersAndDuplicateNewIdsAreNotSilentlyResolved() {
        val sections=listOf(EventSection("A","","a"),EventSection("B","","b"),EventSection("C","","c"))
        val base=EventFields(sections=sections)
        val result=DraftMergePolicy.merge(base,base.copy(sections=listOf(sections[1],sections[0],sections[2])),base.copy(sections=listOf(sections[0],sections[2],sections[1])))
        assertEquals(setOf("sections.order"),result.conflicts);assertEquals(listOf("b","a","c"),result.fields.sections.map {it.id})
        val added=DraftMergePolicy.merge(EventFields(),EventFields(questions=listOf(DraftQuestion("new",title="Local"))),EventFields(questions=listOf(DraftQuestion("new",title="Remote"))))
        assertEquals(setOf("questions.new"),added.conflicts)
    }
    @Test fun coverIntentIsAtomicInsteadOfMixingLocalFileWithAnotherServerAsset() {
        val base=EventFields(remoteCoverAssetId="old",remoteCoverUrl="old-url")
        val local=base.copy(cover=EventCover("local","image/png",1),remoteCoverAssetId=null,remoteCoverUrl=null)
        val remote=base.copy(remoteCoverAssetId="remote",remoteCoverUrl="remote-url")
        val result=DraftMergePolicy.merge(base,local,remote)
        assertEquals(setOf("cover"),result.conflicts);assertEquals(local.cover,result.fields.cover);assertNull(result.fields.remoteCoverAssetId)
    }
}
