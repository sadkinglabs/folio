package dev.sadking.folio.core.sync

import dev.sadking.folio.core.sync.Fx.A
import dev.sadking.folio.core.sync.Fx.ann
import dev.sadking.folio.core.sync.Fx.base
import dev.sadking.folio.core.sync.Fx.book
import dev.sadking.folio.core.sync.Fx.file
import dev.sadking.folio.core.sync.Fx.plan
import dev.sadking.folio.core.sync.KindleAction.AddAnnotations
import dev.sadking.folio.core.sync.KindleAction.DeleteAnnotations
import dev.sadking.folio.core.sync.KindleAction.EditAnnotations
import org.junit.Assert.*
import org.junit.Test

/** Rule 7 (M11): deletions, notes, colours and styles both ways, and when they never cross. */
class AnnotationSyncTest {
    private val home = "Librillo/Ann Lee/Home.epub"

    private fun state(a: SyncAnnotation) = AnnotationMerge.state(a)

    /** A baseline that had [anns], agreed as they are, from a plugin that takes edits. */
    private fun agreed(vararg anns: SyncAnnotation) =
        base(
            "1",
            home,
            A,
            annotationIds = anns.map { it.id }.toSet(),
            annotationStates = anns.associate { it.id to state(it) },
        )

    // ---- Deleted on the Kindle ----

    @Test
    fun deletedOnTheKindleIsDeletedOnThePhone() {
        val x = ann("x")
        val p =
            plan(
                listOf(file(home, A, complete = true)),
                listOf(book("1", A, annotations = listOf(x))),
                listOf(agreed(x)),
            )
        assertEquals(listOf(PhoneAction.DeleteAnnotations("1", listOf("x"))), p.phone)
        assertTrue(p.kindle.isEmpty())
    }

    @Test
    fun withoutABaselineAMissingAnnotationIsSentNeverDeleted() {
        val p =
            plan(
                listOf(file(home, A, complete = true)),
                listOf(book("1", A, annotations = listOf(ann("x")))),
            )
        assertEquals(listOf(AddAnnotations(home, listOf(ann("x")))), p.kindle)
        assertTrue(p.phone.isEmpty())
    }

    @Test
    fun anIdTheBaselineDoesNotKnowIsNeverADeletion() {
        val p =
            plan(
                listOf(file(home, A, complete = true)),
                listOf(book("1", A, annotations = listOf(ann("x")))),
                listOf(agreed(ann("other"))),
            )
        assertEquals(listOf(AddAnnotations(home, listOf(ann("x")))), p.kindle)
        assertTrue(p.phone.isEmpty())
    }

    @Test
    fun anOldPluginKeepsTheOldRuleAndNothingIsDeleted() {
        // No annotationsComplete: an older plugin, or notes it couldn't read in full.
        val x = ann("x")
        val p =
            plan(
                listOf(file(home, A)),
                listOf(book("1", A, annotations = listOf(x))),
                listOf(agreed(x)),
            )
        assertEquals(listOf(AddAnnotations(home, listOf(x))), p.kindle)
        assertTrue(p.phone.isEmpty())
    }

    @Test
    fun editedOnThePhoneBeatsDeletedOnTheKindle() {
        val x = ann("x")
        val edited = x.copy(note = "kept", kind = "note", updated = "2026-03-01 08:00:00")
        val p =
            plan(
                listOf(file(home, A, complete = true)),
                listOf(book("1", A, annotations = listOf(edited))),
                listOf(agreed(x)),
            )
        assertEquals(listOf(AddAnnotations(home, listOf(edited))), p.kindle)
        assertTrue(p.phone.isEmpty())
    }

    @Test
    fun withoutAStateAnAnnotationEverEditedIsKept() {
        // A baseline from an older plugin knows the id but not how it looked: unsure is kept.
        val edited = ann("x").copy(note = "n", updated = "2026-03-01 08:00:00")
        val p =
            plan(
                listOf(file(home, A, complete = true)),
                listOf(book("1", A, annotations = listOf(edited))),
                listOf(base("1", home, A, annotationIds = setOf("x"))),
            )
        assertEquals(listOf(AddAnnotations(home, listOf(edited))), p.kindle)
        // Never edited: the e-reader's deletion holds.
        val plain =
            plan(
                listOf(file(home, A, complete = true)),
                listOf(book("1", A, annotations = listOf(ann("x")))),
                listOf(base("1", home, A, annotationIds = setOf("x"))),
            )
        assertEquals(listOf(PhoneAction.DeleteAnnotations("1", listOf("x"))), plain.phone)
    }

    // ---- Deleted on the phone ----

    @Test
    fun deletedOnThePhoneIsDeletedOnTheKindle() {
        val x = ann("x")
        val p =
            plan(
                listOf(file(home, A, annotations = listOf(x), complete = true)),
                listOf(book("1", A, annotations = listOf(x.copy(deleted = true)))),
                listOf(agreed(x)),
            )
        assertEquals(listOf(DeleteAnnotations(home, listOf("x"))), p.kindle)
        assertTrue(p.phone.isEmpty())
    }

    @Test
    fun editedOnTheKindleBeatsDeletedOnThePhone() {
        val x = ann("x")
        val recoloured = x.copy(color = "green", updated = "2026-03-02 09:00:00")
        val p =
            plan(
                listOf(file(home, A, annotations = listOf(recoloured), complete = true)),
                listOf(book("1", A, annotations = listOf(x.copy(deleted = true)))),
                listOf(agreed(x)),
            )
        assertTrue(p.kindle.isEmpty())
        assertEquals(listOf(PhoneAction.EditAnnotations("1", listOf(recoloured))), p.phone)
    }

    @Test
    fun aPhoneDeletionWaitsForAPluginThatTakesIt() {
        val x = ann("x")
        val tomb = x.copy(deleted = true)
        // An older plugin: nothing deleted, and nothing comes back to the phone either.
        val old =
            plan(
                listOf(file(home, A, annotations = listOf(x))),
                listOf(book("1", A, annotations = listOf(tomb))),
                listOf(base("1", home, A, annotationIds = setOf("x"))),
            )
        assertTrue(old.isEmpty)
        // The baseline keeps the id, so the deletion goes through once the plugin is updated.
        val kept =
            SyncPlanner.baselines(
                listOf(file(home, A, annotations = listOf(x))),
                listOf(book("1", A, annotations = listOf(tomb))),
                listOf(base("1", home, A, annotationIds = setOf("x"))),
            )
        assertEquals(setOf("x"), kept.single().annotationIds)
        val later =
            plan(
                listOf(file(home, A, annotations = listOf(x), complete = true)),
                listOf(book("1", A, annotations = listOf(tomb))),
                kept,
            )
        assertEquals(listOf(DeleteAnnotations(home, listOf("x"))), later.kindle)
    }

    @Test
    fun aDeletionWithoutABaselineIsNeitherSentNorUndone() {
        val x = ann("x")
        val p =
            plan(
                listOf(file(home, A, annotations = listOf(x), complete = true)),
                listOf(book("1", A, annotations = listOf(x.copy(deleted = true)))),
            )
        assertTrue(p.isEmpty)
        // A tombstone is never sent to an e-reader that lacks it.
        val fresh =
            plan(emptyList(), listOf(book("1", A, annotations = listOf(x.copy(deleted = true)))))
        assertTrue(fresh.kindle.none { it is AddAnnotations })
    }

    // ---- Notes, colours and styles ----

    @Test
    fun aNoteEditedOnThePhoneGoesToTheKindle() {
        val x = ann("x")
        val edited = x.copy(note = "new note", kind = "note", updated = "2026-03-01 08:00:00")
        val p =
            plan(
                listOf(file(home, A, annotations = listOf(x), complete = true)),
                listOf(book("1", A, annotations = listOf(edited))),
                listOf(agreed(x)),
            )
        assertEquals(listOf(EditAnnotations(home, listOf(edited))), p.kindle)
        assertTrue(p.phone.isEmpty())
    }

    @Test
    fun aColourChangedOnTheKindleComesToThePhoneEvenWithoutAnEditTime() {
        // KOReader may not stamp a colour change: the baseline's state still tells who changed.
        val x = ann("x").copy(color = "yellow", drawer = "lighten")
        val kindle = x.copy(color = "blue")
        val p =
            plan(
                listOf(file(home, A, annotations = listOf(kindle), complete = true)),
                listOf(book("1", A, annotations = listOf(x))),
                listOf(agreed(x)),
            )
        assertTrue(p.kindle.isEmpty())
        assertEquals(listOf(PhoneAction.EditAnnotations("1", listOf(kindle))), p.phone)
    }

    @Test
    fun whenBothEditedTheNewestEditWins() {
        val x = ann("x")
        val phone = x.copy(note = "phone", kind = "note", updated = "2026-03-01 08:00:00")
        val kindle = x.copy(note = "kindle", kind = "note", updated = "2026-03-02 08:00:00")
        val p =
            plan(
                listOf(file(home, A, annotations = listOf(kindle), complete = true)),
                listOf(book("1", A, annotations = listOf(phone))),
                listOf(agreed(x)),
            )
        assertTrue(p.kindle.isEmpty())
        val edit = (p.phone.single() as PhoneAction.EditAnnotations).annotations.single()
        assertEquals("kindle", edit.note)
        assertEquals("2026-03-02 08:00:00", edit.updated)
    }

    @Test
    fun withoutAStateABlankNeverBeatsAValue() {
        // An old phone row (no colour) and the Kindle's copy with a colour and a note added
        // there: the phone takes both, and the Kindle keeps them.
        val phone = ann("x")
        val kindle = ann("x").copy(color = "gray", drawer = "lighten", note = "on kindle")
        val p =
            plan(
                listOf(file(home, A, annotations = listOf(kindle), complete = true)),
                listOf(book("1", A, annotations = listOf(phone))),
                listOf(base("1", home, A, annotationIds = setOf("x"))),
            )
        assertTrue(p.kindle.isEmpty())
        val edit = (p.phone.single() as PhoneAction.EditAnnotations).annotations.single()
        assertEquals("on kindle", edit.note)
        assertEquals("gray", edit.color)
        assertEquals("note", edit.kind)
    }

    @Test
    fun anOldPluginNeverEditsEitherSide() {
        val x = ann("x")
        val p =
            plan(
                listOf(file(home, A, annotations = listOf(x.copy(note = "k")))),
                listOf(book("1", A, annotations = listOf(x.copy(color = "green")))),
                listOf(agreed(x)),
            )
        assertTrue(p.isEmpty)
    }

    // ---- Over several syncs ----

    @Test
    fun deletionsAndEditsSettleInOneSync() {
        val x = ann("x")
        val y = ann("y", "2026-01-02 10:00:00")
        var s =
            Fx.State(
                listOf(file(home, A, complete = true)),
                listOf(book("1", A, annotations = listOf(x, y))),
                emptyList(),
            )
        s = Fx.sync(s).second
        assertEquals(setOf("x", "y"), s.kindle.single().annotations.map { it.id }.toSet())
        assertEquals(setOf("x", "y"), s.baselines.single().annotationIds)
        // The owner deletes x on the phone and recolours y.
        s =
            s.copy(
                phone =
                    listOf(
                        book(
                            "1",
                            A,
                            annotations =
                                listOf(
                                    x.copy(deleted = true),
                                    y.copy(color = "green", updated = "2026-03-01 08:00:00"),
                                ),
                        )
                    )
            )
        assertTrue(SyncPending.pending(s.phone, s.baselines))
        val (plan, after) = Fx.sync(s)
        assertEquals(
            listOf(
                EditAnnotations(
                    home,
                    listOf(y.copy(color = "green", updated = "2026-03-01 08:00:00")),
                ),
                DeleteAnnotations(home, listOf("x")),
            ),
            plan.kindle,
        )
        assertEquals(listOf("y"), after.kindle.single().annotations.map { it.id })
        assertEquals("green", after.kindle.single().annotations.single().color)
        assertEquals(setOf("y"), after.baselines.single().annotationIds)
        assertFalse(SyncPending.pending(after.phone, after.baselines))
        assertTrue(Fx.sync(after).first.isEmpty)
        // Then y is deleted on the Kindle: a tombstone on the phone, and quiet after that.
        val (plan2, after2) =
            Fx.sync(
                after.copy(kindle = listOf(after.kindle.single().copy(annotations = emptyList())))
            )
        assertEquals(listOf(PhoneAction.DeleteAnnotations("1", listOf("y"))), plan2.phone)
        assertTrue(after2.phone.single().annotations.all { it.deleted })
        assertTrue(after2.baselines.single().annotationIds.isEmpty())
        assertTrue(Fx.sync(after2).first.isEmpty)
    }

    @Test
    fun anOldPluginsSyncsAreNotPendingForATombstone() {
        val x = ann("x")
        val phone = listOf(book("1", A, annotations = listOf(x.copy(deleted = true))))
        assertFalse(
            SyncPending.pending(phone, listOf(base("1", home, A, annotationIds = setOf("x"))))
        )
        assertTrue(SyncPending.pending(phone, listOf(agreed(x))))
    }

    @Test
    fun theKindleAfterItsActionsHasTheEditsAndNotTheDeleted() {
        val x = ann("x")
        val y = ann("y")
        val kindle = listOf(file(home, A, annotations = listOf(x, y), complete = true))
        val after =
            SyncPlanner.kindleAfter(
                kindle,
                listOf(
                    EditAnnotations(home, listOf(x.copy(note = "n", kind = "note"))),
                    DeleteAnnotations(home, listOf("y")),
                ),
                emptyList(),
                listOf(KindleResult(0, true), KindleResult(1, true)),
            )
        assertEquals(listOf(x.copy(note = "n", kind = "note")), after.single().annotations)
        assertTrue(after.single().annotationsComplete)
    }

    @Test
    fun aFailedDeletionIsTriedAgain() {
        val x = ann("x")
        val s =
            Fx.State(
                listOf(file(home, A, annotations = listOf(x), complete = true)),
                listOf(book("1", A, annotations = listOf(x.copy(deleted = true)))),
                listOf(agreed(x)),
            )
        val plan = plan(s.kindle, s.phone, s.baselines)
        val failed = plan.kindle.indices.map { KindleResult(it, false) }
        val kindle = SyncPlanner.kindleAfter(s.kindle, plan.kindle, s.phone, failed)
        val baselines = SyncPlanner.baselines(kindle, s.phone, s.baselines)
        assertEquals(setOf("x"), baselines.single().annotationIds)
        assertEquals(plan.kindle, plan(kindle, s.phone, baselines).kindle)
    }
}
