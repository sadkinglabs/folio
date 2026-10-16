package dev.sadking.folio.core.sync

import dev.sadking.folio.core.sync.Fx.A
import dev.sadking.folio.core.sync.Fx.B
import dev.sadking.folio.core.sync.Fx.C
import dev.sadking.folio.core.sync.Fx.D
import dev.sadking.folio.core.sync.Fx.E
import dev.sadking.folio.core.sync.Fx.State
import dev.sadking.folio.core.sync.Fx.ann
import dev.sadking.folio.core.sync.Fx.base
import dev.sadking.folio.core.sync.Fx.book
import dev.sadking.folio.core.sync.Fx.file
import dev.sadking.folio.core.sync.Fx.sync
import org.junit.Assert.*
import org.junit.Test

/** Whole syncs: plan, run both sides, record baselines, and plan again. */
class SyncRoundTripTest {
    private val home = "Librillo/Ann Lee/Home.epub"

    /** Syncs once, then checks a second sync finds nothing to do. */
    private fun settles(state: State): State {
        val (_, after) = sync(state)
        val (again, _) = sync(after)
        assertTrue("Second plan should be empty: $again", again.isEmpty)
        return after
    }

    @Test
    fun aFirstSyncMirrorsBothLibrariesAndTheNextFindsNothingToDo() {
        val start =
            State(
                kindle =
                    listOf(
                        file(
                            "Documents/dune.epub",
                            A,
                            status = "finished",
                            statusAt = 10,
                            annotations = listOf(ann("k1")),
                            collections = listOf("Sci-fi"),
                            title = "Dune",
                            authors = listOf("Frank Herbert"),
                        ),
                        file("Documents/notes.pdf", B),
                        file("Librillo/Ann Lee/Home.epub", C, status = "reading", statusAt = 5),
                    ),
                phone =
                    listOf(
                        book("home", C, status = "Unread", statusAt = 100),
                        book(
                            "ursula",
                            D,
                            title = "The Dispossessed",
                            authors = listOf("Ursula K. Le Guin"),
                            status = "Reading",
                            lists = listOf("Sundays"),
                            annotations = listOf(ann("p1")),
                        ),
                    ),
                baselines = emptyList(),
            )
        val after = settles(start)
        assertEquals(
            listOf(
                "Librillo/Ann Lee/Home.epub",
                "Librillo/Frank Herbert/Dune.epub",
                "Librillo/Unknown author/notes.pdf",
                "Librillo/Ursula K. Le Guin/The Dispossessed.epub",
            ),
            after.kindle.map { it.path },
        )
        assertEquals(4, after.phone.size)
        assertEquals(4, after.baselines.size)
        val dune = after.phone.single { it.sha256 == A }
        assertEquals("Finished", dune.status)
        assertEquals(listOf("Sci-fi"), dune.lists)
        assertEquals(listOf(ann("k1")), dune.annotations)
        // Progress on the Kindle beats an unread phone without a baseline.
        assertEquals("Reading", after.phone.single { it.id == "home" }.status)
        assertEquals("reading", after.kindle.single { it.sha256 == D }.status)
    }

    @Test
    fun aSecondSyncWithoutChangesIsEmpty() {
        val state =
            State(
                listOf(
                    file(
                        home,
                        A,
                        status = "reading",
                        annotations = listOf(ann("x")),
                        collections = listOf("S"),
                    )
                ),
                listOf(
                    book(
                        "1",
                        A,
                        status = "Reading",
                        annotations = listOf(ann("x")),
                        lists = listOf("S"),
                    )
                ),
                listOf(
                    base(
                        "1",
                        home,
                        A,
                        status = "reading",
                        annotationIds = setOf("x"),
                        collections = setOf("S"),
                    )
                ),
            )
        assertTrue(Fx.plan(state.kindle, state.phone, state.baselines).isEmpty)
        assertEquals(
            state.baselines,
            SyncPlanner.baselines(state.kindle, state.phone, state.baselines),
        )
    }

    @Test
    fun everyKindOfChangeSettlesInOneSync() {
        val scenarios =
            listOf(
                // Renamed on the phone.
                State(
                    listOf(file(home, A)),
                    listOf(book("1", A, title = "New")),
                    listOf(base("1", home, A)),
                ),
                // Edited on the phone and renamed.
                State(
                    listOf(file(home, A)),
                    listOf(book("1", B, title = "New")),
                    listOf(base("1", home, A)),
                ),
                // Edited on the Kindle.
                State(listOf(file(home, B)), listOf(book("1", A)), listOf(base("1", home, A))),
                // Status, notes and lists on both sides.
                State(
                    listOf(
                        file(
                            home,
                            A,
                            status = "finished",
                            annotations = listOf(ann("k")),
                            collections = listOf("K"),
                        )
                    ),
                    listOf(
                        book(
                            "1",
                            A,
                            status = "Reading",
                            annotations = listOf(ann("p")),
                            lists = listOf("P", "Both"),
                        )
                    ),
                    listOf(base("1", home, A, status = "reading", collections = setOf("Both"))),
                ),
                // A clash between a download and a Kindle-only book.
                State(
                    listOf(
                        file("Documents/h.epub", B, title = "Home", authors = listOf("Ann Lee"))
                    ),
                    listOf(book("1", A)),
                    emptyList(),
                ),
                // A file missing on the phone.
                State(
                    listOf(file(home, A)),
                    listOf(book("1", A, hasFile = false)),
                    listOf(base("1", home, A)),
                ),
            )
        scenarios.forEach { settles(it) }
    }

    @Test
    fun aKindleDeletionRemovesOnThePhoneAndRestoringSendsItBack() {
        val start = State(emptyList(), listOf(book("1", A)), listOf(base("1", home, A)))
        val (first, removed) = sync(start)
        assertEquals(listOf(PhoneAction.Remove("1")), first.phone)
        assertTrue(removed.phone.single().removed)
        // Deleted on both sides: the baseline goes, so a restore is a fresh send.
        assertTrue(removed.baselines.isEmpty())
        val restored = removed.copy(phone = removed.phone.map { it.copy(removed = false) })
        val (second, back) = sync(restored)
        assertEquals(listOf(KindleAction.Download("1", A, 100, home)), second.kindle)
        assertEquals(listOf(home), back.kindle.map { it.path })
        assertTrue(sync(back).first.isEmpty)
    }

    @Test
    fun aPhoneRemovalDeletesOnTheKindleOnce() {
        val start =
            State(
                listOf(file(home, A)),
                listOf(book("1", A, removed = true)),
                listOf(base("1", home, A)),
            )
        val (first, after) = sync(start)
        assertEquals(listOf(KindleAction.Delete(home)), first.kindle)
        assertTrue(after.kindle.isEmpty())
        assertTrue(after.baselines.isEmpty())
        assertTrue(sync(after).first.isEmpty)
    }

    @Test
    fun aPurgedBookIsDeletedOnTheKindleOnce() {
        val start = State(listOf(file(home, A)), emptyList(), listOf(base("gone", home, A)))
        val (_, after) = sync(start)
        assertTrue(after.kindle.isEmpty() && after.baselines.isEmpty())
        assertTrue(sync(after).first.isEmpty)
    }

    @Test
    fun anAnsweredQuestionSettles() {
        val start =
            State(
                listOf(file(home, A, status = "finished")),
                listOf(book("1", A, status = "Abandoned")),
                listOf(base("1", home, A, status = "reading")),
            )
        val (asked, same) = sync(start)
        val q = asked.questions.single()
        // Unanswered: the baseline keeps the old status, so it is asked again.
        assertEquals("reading", same.baselines.single().status)
        assertEquals(listOf(q), sync(same).first.questions)
        val (_, answered) = sync(same, mapOf(q.id to "Finished"))
        assertEquals("Finished", answered.phone.single().status)
        assertEquals("finished", answered.baselines.single().status)
        assertTrue(sync(answered).first.isEmpty)
    }

    @Test
    fun aFailedActionKeepsTheOldBaselineForThatField() {
        val kindle = listOf(file(home, A, status = "reading", collections = listOf("S")))
        val phone = listOf(book("1", A, status = "Finished", lists = emptyList()))
        val previous = listOf(base("1", home, A, status = "reading", collections = setOf("S")))
        val plan = Fx.plan(kindle, phone, previous)
        assertEquals(
            listOf(
                KindleAction.SetStatus(home, "finished"),
                KindleAction.SetCollections(home, emptyList()),
            ),
            plan.kindle,
        )
        // Nothing reported done.
        val after = SyncPlanner.kindleAfter(kindle, plan.kindle, phone, emptyList())
        assertEquals(kindle, after)
        val baseline = SyncPlanner.baselines(after, phone, previous).single()
        assertEquals("reading", baseline.status)
        assertEquals(setOf("S"), baseline.collections)
        // So the next sync tries again.
        assertEquals(plan, Fx.plan(after, phone, listOf(baseline)))
    }

    @Test
    fun aFailedUploadSkipsItsImport() {
        val kindle = listOf(file("Documents/x.epub", A))
        val plan = Fx.plan(kindle, emptyList())
        assertEquals(1, plan.phone.size)
        val failed =
            plan.kindle.indices.map {
                KindleResult(it, ok = plan.kindle[it] !is KindleAction.Upload)
            }
        assertTrue(SyncPlanner.phoneActionsAfter(plan, failed).isEmpty())
        assertEquals(plan.phone, SyncPlanner.phoneActionsAfter(plan, Fx.allDone(plan)))
    }

    @Test
    fun theKindlesReportedPathIsWhereTheFileIs() {
        val kindle = listOf(file("Documents/x.epub", A, title = "X", authors = listOf("Zed")))
        val phone = listOf(book("1", A, title = "X", authors = listOf("Zed"), status = "Reading"))
        val plan = Fx.plan(kindle, phone)
        assertEquals(
            listOf(
                KindleAction.Move("Documents/x.epub", "Librillo/Zed/X.epub"),
                KindleAction.SetStatus("Librillo/Zed/X.epub", "reading"),
            ),
            plan.kindle,
        )
        val results =
            listOf(KindleResult(0, true, "Librillo/Zed/X (3).epub"), KindleResult(1, true))
        val after = SyncPlanner.kindleAfter(kindle, plan.kindle, phone, results)
        assertEquals(
            listOf(
                file(
                    "Librillo/Zed/X (3).epub",
                    A,
                    title = "X",
                    authors = listOf("Zed"),
                    status = "reading",
                )
            ),
            after,
        )
    }

    @Test
    fun baselinesAreKeptWhileEitherSideStillHasTheBook() {
        val previous =
            listOf(
                base("active", "a.epub", A),
                base("removed-still-on-kindle", "b.epub", B),
                base("removed-gone", "c.epub", C),
                base("purged-still-on-kindle", "d.epub", D),
                base("purged-gone", "e.epub", E),
            )
        val phone =
            listOf(
                book("active", A),
                book("removed-still-on-kindle", B, removed = true),
                book("removed-gone", C, removed = true),
            )
        val kindle = listOf(file("b.epub", B), file("d.epub", D))
        assertEquals(
            listOf("active", "purged-still-on-kindle", "removed-still-on-kindle"),
            SyncPlanner.baselines(kindle, phone, previous).map { it.bookId },
        )
    }
}
