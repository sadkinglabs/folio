package dev.sadking.folio.core.sync

import dev.sadking.folio.core.sync.Fx.A
import dev.sadking.folio.core.sync.Fx.B
import dev.sadking.folio.core.sync.Fx.C
import dev.sadking.folio.core.sync.Fx.D
import dev.sadking.folio.core.sync.Fx.ann
import dev.sadking.folio.core.sync.Fx.base
import dev.sadking.folio.core.sync.Fx.book
import dev.sadking.folio.core.sync.Fx.file
import dev.sadking.folio.core.sync.Fx.plan
import dev.sadking.folio.core.sync.KindleAction.AddAnnotations
import dev.sadking.folio.core.sync.KindleAction.Delete
import dev.sadking.folio.core.sync.KindleAction.Download
import dev.sadking.folio.core.sync.KindleAction.Move
import dev.sadking.folio.core.sync.KindleAction.SetCollections
import dev.sadking.folio.core.sync.KindleAction.SetStatus
import dev.sadking.folio.core.sync.KindleAction.Upload
import org.junit.Assert.*
import org.junit.Test

class SyncPlannerTest {
    private val home = "Librillo/Ann Lee/Home.epub"

    // ---- Rule 1: matching ----

    @Test
    fun aFileIsMatchedByContentWhereverItIs() {
        val p = plan(listOf(file(home, A)), listOf(book("1", A)), listOf(base("1", home, A)))
        assertEquals(mapOf(home to "1"), p.matched)
        assertTrue(p.isEmpty)
    }

    @Test
    fun aFileEditedOnOneSideIsMatchedByItsBaselinePath() {
        // The Kindle file changed (B), the phone still has the agreed content (A).
        val p = plan(listOf(file(home, B)), listOf(book("1", A)), listOf(base("1", home, A)))
        assertEquals(mapOf(home to "1"), p.matched)
        assertEquals(listOf(Upload(home)), p.kindle)
        assertEquals(listOf(PhoneAction.Revise("1", home)), p.phone)
    }

    @Test
    fun aFileMovedOnTheKindleAndEditedOnThePhoneIsMatchedByItsBaselineContent() {
        val moved = "Documents/home.epub"
        val p =
            plan(
                listOf(file(moved, A)),
                listOf(book("1", B)),
                listOf(base("1", home, A)),
            )
        assertEquals(mapOf(moved to "1"), p.matched)
        // Moved back to its place, then replaced with the phone's edit, keeping its .sdr.
        assertEquals(
            listOf(Move(moved, home), Download("1", B, 100, home, replaces = A)),
            p.kindle,
        )
    }

    @Test
    fun anActiveBookWinsAFileOverARemovedCopyOfIt() {
        val p =
            plan(
                listOf(file(home, A)),
                listOf(book("0-old", A, removed = true), book("1", A)),
                listOf(base("1", home, A)),
            )
        assertEquals(mapOf(home to "1"), p.matched)
        assertTrue(p.isEmpty)
    }

    // ---- Rule 2: books only on the phone ----

    @Test
    fun aBookOnlyOnThePhoneIsDownloadedWithItsReadingData() {
        val p =
            plan(
                emptyList(),
                listOf(
                    book(
                        "1",
                        A,
                        status = "Reading",
                        annotations = listOf(ann("n1")),
                        lists = listOf("Sundays"),
                    )
                ),
            )
        assertEquals(
            listOf(
                Download("1", A, 100, home),
                SetStatus(home, "reading"),
                AddAnnotations(home, listOf(ann("n1"))),
                SetCollections(home, listOf("Sundays")),
            ),
            p.kindle,
        )
        assertEquals(emptyList<PhoneAction>(), p.phone)
    }

    @Test
    fun anUnreadBookWithoutNotesIsOnlyDownloaded() {
        val p = plan(emptyList(), listOf(book("1", A)))
        assertEquals(listOf(Download("1", A, 100, home)), p.kindle)
    }

    @Test
    fun aBookDeletedOnTheKindleGoesToRecentlyRemoved() {
        val p = plan(emptyList(), listOf(book("1", A)), listOf(base("1", home, A)))
        assertEquals(emptyList<KindleAction>(), p.kindle)
        assertEquals(listOf(PhoneAction.Remove("1")), p.phone)
    }

    @Test
    fun aBookDeletedOnTheKindleButChangedOnThePhoneIsAsked() {
        val phone = listOf(book("1", B))
        val baselines = listOf(base("1", home, A))
        val p = plan(emptyList(), phone, baselines)
        assertTrue(p.kindle.isEmpty() && p.phone.isEmpty())
        val q = p.questions.single()
        assertEquals("delete-vs-edit", q.kind)
        assertEquals("1", q.bookId)
        assertEquals(listOf("Remove it", "Send it back"), q.options)
        assertEquals(
            "Home was deleted on the Kindle but changed on the phone. " +
                "Remove it from the phone, or send it back?",
            q.text,
        )
        val removed = plan(emptyList(), phone, baselines, mapOf(q.id to "Remove it"))
        assertEquals(listOf(PhoneAction.Remove("1")), removed.phone)
        assertTrue(removed.questions.isEmpty())
        val back = plan(emptyList(), phone, baselines, mapOf(q.id to "Send it back"))
        assertEquals(listOf(Download("1", B, 100, home)), back.kindle)
        assertTrue(back.phone.isEmpty() && back.questions.isEmpty())
    }

    @Test
    fun removedBooksAndRecordsWithoutAFileAreNotSent() {
        val p =
            plan(
                emptyList(),
                listOf(book("1", A, removed = true), book("2", B, hasFile = false), book("3", "")),
            )
        assertTrue(p.isEmpty)
    }

    // ---- Rule 3: files only on the Kindle ----

    @Test
    fun aKindleBookOutsideTheLibraryFolderIsImportedThenTidied() {
        val outside = "Documents/home_v2.epub"
        val p =
            plan(
                listOf(
                    file(
                        outside,
                        A,
                        status = "finished",
                        statusAt = 5,
                        annotations = listOf(ann("k1")),
                        collections = listOf("Sundays", " "),
                        title = "Home",
                        authors = listOf("Ann Lee"),
                    )
                ),
                emptyList(),
            )
        assertEquals(listOf(Upload(outside), Move(outside, home)), p.kindle)
        assertEquals(
            listOf(
                PhoneAction.Import(outside, "Finished", 5, listOf(ann("k1")), listOf("Sundays"))
            ),
            p.phone,
        )
        assertTrue(p.matched.isEmpty())
    }

    @Test
    fun aKindleBookAlreadyInPlaceIsImportedWithoutMoving() {
        val p =
            plan(listOf(file(home, A, title = "Home", authors = listOf("Ann Lee"))), emptyList())
        assertEquals(listOf(Upload(home)), p.kindle)
        assertEquals(listOf(PhoneAction.Import(home)), p.phone)
    }

    @Test
    fun aKindleBookWithoutMetadataIsNamedAfterItsFile() {
        val p = plan(listOf(file("old/my_book.pdf", A)), emptyList())
        assertEquals(
            listOf(
                Upload("old/my_book.pdf"),
                Move("old/my_book.pdf", "Librillo/Unknown author/my book.pdf"),
            ),
            p.kindle,
        )
    }

    @Test
    fun aBookPurgedOnThePhoneIsDeletedFromTheKindle() {
        val p = plan(listOf(file(home, A)), emptyList(), listOf(base("gone", home, A)))
        assertEquals(listOf(Delete(home)), p.kindle)
        assertTrue(p.phone.isEmpty() && p.questions.isEmpty())
        // Also when the Kindle moved it since.
        val moved = plan(listOf(file("x/y.epub", A)), emptyList(), listOf(base("gone", home, A)))
        assertEquals(listOf(Delete("x/y.epub")), moved.kindle)
    }

    @Test
    fun aBookPurgedOnThePhoneButChangedOnTheKindleIsAsked() {
        val kindle =
            listOf(
                file(
                    home,
                    A,
                    annotations = listOf(ann("new")),
                    title = "Home",
                    authors = listOf("Ann Lee"),
                )
            )
        val baselines = listOf(base("gone", home, A))
        val p = plan(kindle, emptyList(), baselines)
        assertTrue(p.kindle.isEmpty())
        val q = p.questions.single()
        assertEquals("delete-vs-edit", q.kind)
        assertEquals("gone", q.bookId)
        assertEquals(listOf("Keep it", "Delete it"), q.options)
        assertEquals(
            listOf(Delete(home)),
            plan(kindle, emptyList(), baselines, mapOf(q.id to "Delete it")).kindle,
        )
        val kept = plan(kindle, emptyList(), baselines, mapOf(q.id to "Keep it"))
        assertEquals(listOf(Upload(home)), kept.kindle)
        assertEquals(
            PhoneAction.Import(home, annotations = listOf(ann("new"))),
            kept.phone.single(),
        )
    }

    @Test
    fun aKindleCopyOfABookInRecentlyRemovedIsDeletedAndItsNotesKept() {
        val p =
            plan(
                listOf(file("Documents/home.epub", A, annotations = listOf(ann("k1")))),
                listOf(book("1", A, removed = true)),
            )
        assertEquals(listOf(Delete("Documents/home.epub")), p.kindle)
        assertEquals(listOf(PhoneAction.AddAnnotations("1", listOf(ann("k1")))), p.phone)
    }

    @Test
    fun aSecondCopyOfALibraryBookIsLeftAlone() {
        val p =
            plan(
                listOf(file(home, A), file("Documents/copy.epub", A)),
                listOf(book("1", A)),
                listOf(base("1", home, A)),
            )
        assertTrue(p.isEmpty)
        assertEquals(mapOf(home to "1"), p.matched)
    }

    // ---- Rule 4: books removed on the phone ----

    @Test
    fun aBookRemovedOnThePhoneIsDeletedFromTheKindle() {
        val p =
            plan(
                listOf(file(home, A, annotations = listOf(ann("k1"), ann("p1")))),
                listOf(book("1", A, removed = true, annotations = listOf(ann("p1")))),
                listOf(base("1", home, A)),
            )
        assertEquals(listOf(Delete(home)), p.kindle)
        assertEquals(listOf(PhoneAction.AddAnnotations("1", listOf(ann("k1")))), p.phone)
        assertEquals(mapOf(home to "1"), p.matched)
    }

    @Test
    fun aBookRemovedOnThePhoneBeforeAnySyncIsDeletedFromTheKindle() {
        val p =
            plan(
                listOf(file("Documents/home.epub", A, annotations = listOf(ann("k1")))),
                listOf(book("1", A, removed = true)),
            )
        assertEquals(listOf(Delete("Documents/home.epub")), p.kindle)
        assertEquals(listOf(PhoneAction.AddAnnotations("1", listOf(ann("k1")))), p.phone)
        assertTrue(p.questions.isEmpty())
    }

    @Test
    fun aBookRemovedOnThePhoneWithOnlyNewNotesOnTheKindleIsDeletedAndTheNotesKept() {
        // New notes alone are not a change to the file: they are copied, not asked about.
        val p =
            plan(
                listOf(file(home, A, annotations = listOf(ann("k1")))),
                listOf(book("1", A, removed = true)),
                listOf(base("1", home, A)),
            )
        assertEquals(listOf(Delete(home)), p.kindle)
        assertEquals(listOf(PhoneAction.AddAnnotations("1", listOf(ann("k1")))), p.phone)
        assertTrue(p.questions.isEmpty())
    }

    @Test
    fun aBookRemovedOnThePhoneButChangedOnTheKindleIsAsked() {
        val kindle = listOf(file(home, B))
        val phone = listOf(book("1", A, removed = true))
        val baselines = listOf(base("1", home, A))
        val p = plan(kindle, phone, baselines)
        assertTrue(p.kindle.isEmpty() && p.phone.isEmpty())
        val q = p.questions.single()
        assertEquals(
            Question(
                q.id,
                "1",
                home,
                "delete-vs-edit",
                "Delete it",
                "Keep it",
                q.text,
                listOf("Keep it", "Delete it"),
            ),
            q,
        )
        assertEquals(
            "Home was removed on the phone but changed on the Kindle. " +
                "Delete it from the Kindle, or keep it?",
            q.text,
        )
        val kept = plan(kindle, phone, baselines, mapOf(q.id to "Keep it"))
        assertEquals(listOf(Upload(home)), kept.kindle)
        assertEquals(listOf(PhoneAction.Restore("1"), PhoneAction.Revise("1", home)), kept.phone)
        val deleted = plan(kindle, phone, baselines, mapOf(q.id to "Delete it"))
        assertEquals(listOf(Delete(home)), deleted.kindle)
    }

    // ---- Rule 5: books on both sides ----

    @Test
    fun aTitleEditedOnThePhoneRenamesTheKindleFile() {
        val p =
            plan(
                listOf(file(home, A)),
                listOf(book("1", A, title = "Home Again")),
                listOf(base("1", home, A)),
            )
        assertEquals(listOf(Move(home, "Librillo/Ann Lee/Home Again.epub")), p.kindle)
    }

    @Test
    fun aFileInPlaceInAnotherCaseOrWithItsNumberStays() {
        val lower = "librillo/ann lee/home.epub"
        assertTrue(
            plan(listOf(file(lower, A)), listOf(book("1", A)), listOf(base("1", lower, A))).isEmpty
        )
        val numbered = "Librillo/Ann Lee/Home (2).epub"
        assertTrue(
            plan(listOf(file(numbered, A)), listOf(book("1", A)), listOf(base("1", numbered, A)))
                .isEmpty
        )
    }

    @Test
    fun aLibraryBookOutsideTheLibraryFolderIsMovedIn() {
        // Rule 11: matched by content on the first sync, then tidied with its reading data.
        val p =
            plan(
                listOf(file("Documents/home.epub", A, status = "reading", statusAt = 5)),
                listOf(book("1", A)),
            )
        assertEquals(listOf(Move("Documents/home.epub", home)), p.kindle)
        assertEquals(listOf(PhoneAction.SetStatus("1", "Reading", 5)), p.phone)
        assertEquals(mapOf("Documents/home.epub" to "1"), p.matched)
    }

    @Test
    fun aBookInTheLibraryFolderAtTheWrongPathIsMoved() {
        val wrong = "Librillo/misc/home.epub"
        val p = plan(listOf(file(wrong, A)), listOf(book("1", A)), listOf(base("1", wrong, A)))
        assertEquals(listOf(Move(wrong, home)), p.kindle)
    }

    @Test
    fun anEditOnThePhoneReplacesTheKindleFileInPlace() {
        val p =
            plan(listOf(file(home, A)), listOf(book("1", B, size = 7)), listOf(base("1", home, A)))
        assertEquals(listOf(Download("1", B, 7, home, replaces = A)), p.kindle)
        assertTrue(p.phone.isEmpty())
    }

    @Test
    fun aRenameAfterAnEditMovesFirstThenReplaces() {
        val p =
            plan(
                listOf(file(home, A)),
                listOf(book("1", B, title = "Home Again")),
                listOf(base("1", home, A)),
            )
        val to = "Librillo/Ann Lee/Home Again.epub"
        assertEquals(listOf(Move(home, to), Download("1", B, 100, to, replaces = A)), p.kindle)
    }

    @Test
    fun anEditOnTheKindleBecomesANewRevisionOnThePhone() {
        val p = plan(listOf(file(home, B)), listOf(book("1", A)), listOf(base("1", home, A)))
        assertEquals(listOf(Upload(home)), p.kindle)
        assertEquals(listOf(PhoneAction.Revise("1", home)), p.phone)
    }

    @Test
    fun aFileChangedOnBothSidesIsAskedAndNothingElseHappensToTheBook() {
        val kindle = listOf(file(home, B, status = "finished", annotations = listOf(ann("k1"))))
        val phone = listOf(book("1", C, title = "Home Again", status = "Reading"))
        val baselines = listOf(base("1", home, A, status = "reading"))
        val p = plan(kindle, phone, baselines)
        assertTrue(p.kindle.isEmpty() && p.phone.isEmpty())
        val q = p.questions.single()
        assertEquals("file", q.kind)
        assertEquals(listOf("Kindle copy", "Phone copy"), q.options)
        assertEquals(
            "Home Again was changed on both the phone and the Kindle. Which copy should both keep?",
            q.text,
        )
        val phoneWins = plan(kindle, phone, baselines, mapOf(q.id to "Phone copy"))
        val to = "Librillo/Ann Lee/Home Again.epub"
        assertEquals(
            listOf(Move(home, to), Download("1", C, 100, to, replaces = B)),
            phoneWins.kindle.take(2),
        )
        val kindleWins = plan(kindle, phone, baselines, mapOf(q.id to "Kindle copy"))
        assertEquals(listOf(Upload(home), Move(home, to)), kindleWins.kindle.take(2))
        assertTrue(PhoneAction.Revise("1", home) in kindleWins.phone)
    }

    @Test
    fun aMissingFileOnThePhoneIsTakenBackFromTheKindle() {
        val p =
            plan(
                listOf(file(home, A)),
                listOf(book("1", A, hasFile = false)),
                listOf(base("1", home, A)),
            )
        assertEquals(listOf(Upload(home)), p.kindle)
        assertEquals(listOf(PhoneAction.Revise("1", home)), p.phone)
    }

    // ---- Rule 6: status ----

    private fun statusPlan(
        phone: String,
        kindle: String?,
        baseline: String?,
        phoneAt: Long = 10,
        kindleAt: Long? = 20,
        answers: Map<String, String> = emptyMap(),
    ) =
        plan(
            listOf(file(home, A, status = kindle, statusAt = kindleAt)),
            listOf(book("1", A, status = phone, statusAt = phoneAt)),
            listOfNotNull(baseline?.let { base("1", home, A, status = it) }),
            answers,
        )

    @Test
    fun aStatusChangedOnlyOnTheKindleIsCopiedToThePhone() {
        val p = statusPlan(phone = "Reading", kindle = "finished", baseline = "reading")
        assertEquals(listOf(PhoneAction.SetStatus("1", "Finished", 20)), p.phone)
        assertTrue(p.kindle.isEmpty())
    }

    @Test
    fun aStatusChangedOnlyOnThePhoneIsCopiedToTheKindle() {
        val p = statusPlan(phone = "Finished", kindle = "reading", baseline = "reading")
        assertEquals(listOf(SetStatus(home, "finished")), p.kindle)
        assertTrue(p.phone.isEmpty())
    }

    @Test
    fun aStatusChangedDifferentlyOnBothSidesIsAsked() {
        val p = statusPlan(phone = "Abandoned", kindle = "finished", baseline = "reading")
        assertTrue(p.kindle.isEmpty() && p.phone.isEmpty())
        val q = p.questions.single()
        assertEquals("status", q.kind)
        assertEquals("Finished", q.kindleSide)
        assertEquals("Abandoned", q.phoneSide)
        assertEquals(listOf("Finished", "Abandoned"), q.options)
        assertEquals("Home: Kindle says Finished, phone says Abandoned. Which is right?", q.text)
        val kindle =
            statusPlan("Abandoned", "finished", "reading", answers = mapOf(q.id to "Finished"))
        assertEquals(listOf(PhoneAction.SetStatus("1", "Finished", 20)), kindle.phone)
        assertTrue(kindle.questions.isEmpty())
        val phone =
            statusPlan("Abandoned", "finished", "reading", answers = mapOf(q.id to "Abandoned"))
        assertEquals(listOf(SetStatus(home, "abandoned")), phone.kindle)
    }

    @Test
    fun aStatusQuestionDoesNotHoldBackNotesOrLists() {
        val p =
            plan(
                listOf(file(home, A, status = "finished", annotations = listOf(ann("k1")))),
                listOf(book("1", A, status = "Abandoned", lists = listOf("Sundays"))),
                listOf(base("1", home, A, status = "reading")),
            )
        assertEquals("status", p.questions.single().kind)
        assertEquals(
            listOf(PhoneAction.AddAnnotations("1", listOf(ann("k1")))),
            p.phone,
        )
        assertEquals(listOf(SetCollections(home, listOf("Sundays"))), p.kindle)
    }

    @Test
    fun theSameChangeOnBothSidesNeedsNothing() {
        assertTrue(
            statusPlan(phone = "Finished", kindle = "finished", baseline = "reading").isEmpty
        )
        // Paused on the phone is "On hold" (abandoned) on the Kindle.
        assertTrue(statusPlan(phone = "Paused", kindle = "abandoned", baseline = "reading").isEmpty)
        assertTrue(statusPlan(phone = "Want to Read", kindle = "unread", baseline = null).isEmpty)
    }

    @Test
    fun withoutABaselineTheNewerStatusWins() {
        val kindleNewer = statusPlan("Reading", "finished", null, phoneAt = 10, kindleAt = 20)
        assertEquals(listOf(PhoneAction.SetStatus("1", "Finished", 20)), kindleNewer.phone)
        val phoneNewer = statusPlan("Reading", "finished", null, phoneAt = 30, kindleAt = 20)
        assertEquals(listOf(SetStatus(home, "reading")), phoneNewer.kindle)
        // A tie goes to the phone.
        val tie = statusPlan("Reading", "finished", null, phoneAt = 20, kindleAt = 20)
        assertEquals(listOf(SetStatus(home, "reading")), tie.kindle)
    }

    @Test
    fun withoutABaselineUnreadNeverOverwritesProgress() {
        val p = statusPlan("Unread", "finished", null, phoneAt = 99, kindleAt = 1)
        assertEquals(listOf(PhoneAction.SetStatus("1", "Finished", 1)), p.phone)
        val q = statusPlan("Finished", "unread", null, phoneAt = 1, kindleAt = 99)
        assertEquals(listOf(SetStatus(home, "finished")), q.kindle)
    }

    @Test
    fun aKindleWithoutAStatusNeverChangesThePhone() {
        val p = statusPlan("Reading", null, "finished", kindleAt = null)
        assertEquals(listOf(SetStatus(home, "reading")), p.kindle)
        assertTrue(p.phone.isEmpty())
        assertTrue(statusPlan("Unread", null, "finished", kindleAt = null).isEmpty)
    }

    @Test
    fun anUnknownPhoneStatusIsLeftAlone() {
        assertTrue(statusPlan("Did not finish", "finished", "reading").isEmpty)
    }

    // ---- Rule 7: annotations ----

    @Test
    fun annotationsAreMergedBothWays() {
        val p =
            plan(
                listOf(file(home, A, annotations = listOf(ann("k1"), ann("both")))),
                listOf(
                    book(
                        "1",
                        A,
                        annotations =
                            listOf(ann("both"), ann("p2", "2026-02-01"), ann("p1", "2026-01-15")),
                    )
                ),
                listOf(base("1", home, A, annotationIds = setOf("both"))),
            )
        assertEquals(
            listOf(AddAnnotations(home, listOf(ann("p1", "2026-01-15"), ann("p2", "2026-02-01")))),
            p.kindle,
        )
        assertEquals(listOf(PhoneAction.AddAnnotations("1", listOf(ann("k1")))), p.phone)
    }

    @Test
    fun anAnnotationMissingOnOneSideIsAddedBackNeverDeleted() {
        // Deleted on the Kindle since the last sync: the phone's copy goes back.
        val p =
            plan(
                listOf(file(home, A)),
                listOf(book("1", A, annotations = listOf(ann("x")))),
                listOf(base("1", home, A, annotationIds = setOf("x"))),
            )
        assertEquals(listOf(AddAnnotations(home, listOf(ann("x")))), p.kindle)
        assertTrue(p.phone.isEmpty())
    }

    // ---- Rule 8: lists and collections ----

    private fun listPlan(lists: List<String>, collections: List<String>, baseline: Set<String>?) =
        plan(
            listOf(file(home, A, collections = collections)),
            listOf(book("1", A, lists = lists)),
            listOfNotNull(baseline?.let { base("1", home, A, collections = it) }),
        )

    @Test
    fun aListAddedOnOneSideIsAddedOnTheOther() {
        assertEquals(
            listOf(SetCollections(home, listOf("Sundays"))),
            listPlan(listOf("Sundays"), emptyList(), emptySet()).kindle,
        )
        assertEquals(
            listOf(PhoneAction.SetLists("1", listOf("Sundays"))),
            listPlan(emptyList(), listOf("Sundays"), emptySet()).phone,
        )
    }

    @Test
    fun aListRemovedOnOneSideIsRemovedOnTheOther() {
        val phoneRemoved = listPlan(emptyList(), listOf("Sundays"), setOf("Sundays"))
        assertEquals(listOf(SetCollections(home, emptyList())), phoneRemoved.kindle)
        assertTrue(phoneRemoved.phone.isEmpty())
        val kindleRemoved = listPlan(listOf("Sundays"), emptyList(), setOf("Sundays"))
        assertEquals(listOf(PhoneAction.SetLists("1", emptyList())), kindleRemoved.phone)
        assertTrue(kindleRemoved.kindle.isEmpty())
    }

    @Test
    fun changesToDifferentListsMerge() {
        // The phone added Poetry; the Kindle removed Sundays.
        val p = listPlan(listOf("Sundays", "Poetry"), emptyList(), setOf("Sundays"))
        assertEquals(listOf(SetCollections(home, listOf("Poetry"))), p.kindle)
        assertEquals(listOf(PhoneAction.SetLists("1", listOf("Poetry"))), p.phone)
    }

    @Test
    fun withoutABaselineListsAreJoined() {
        val p = listPlan(listOf("Poetry"), listOf("Sundays"), null)
        assertEquals(listOf(SetCollections(home, listOf("Poetry", "Sundays"))), p.kindle)
        assertEquals(listOf(PhoneAction.SetLists("1", listOf("Poetry", "Sundays"))), p.phone)
    }

    // ---- Rule 9: questions and answers ----

    @Test
    fun aQuestionKeepsItsIdFromOneSyncToTheNext() {
        val first = statusPlan("Abandoned", "finished", "reading").questions.single()
        val again = statusPlan("Finished", "reading", "abandoned").questions.single()
        assertEquals(first.id, again.id)
        assertEquals(SyncPlanner.questionId("status", "1", home), first.id)
        assertNotEquals(first.id, SyncPlanner.questionId("file", "1", home))
        assertTrue(first.id.matches(Regex("q-[0-9a-f]{16}")))
    }

    @Test
    fun anAnswerThatIsNoLongerAnOptionIsIgnored() {
        val q = statusPlan("Abandoned", "finished", "reading").questions.single()
        val p = statusPlan("Abandoned", "finished", "reading", answers = mapOf(q.id to "Reading"))
        assertEquals(listOf(q), p.questions)
        assertTrue(p.kindle.isEmpty() && p.phone.isEmpty())
    }

    // ---- Rule 10: paths ----

    @Test
    fun twoDownloadsWithTheSameNameAreNumbered() {
        val p = plan(emptyList(), listOf(book("1", A), book("2", B, title = "HOME")))
        // The first book by id gets the plain name; actions are in path order, ignoring case.
        assertEquals(
            listOf(
                Download("2", B, 100, "Librillo/Ann Lee/HOME (2).epub"),
                Download("1", A, 100, home),
            ),
            p.kindle,
        )
    }

    @Test
    fun aDownloadAvoidsAKindleFileThatDiffersOnlyInCase() {
        val p =
            plan(
                listOf(
                    file(
                        "librillo/ann lee/home.epub",
                        B,
                        title = "home",
                        authors = listOf("ann lee"),
                    )
                ),
                listOf(book("1", A)),
            )
        assertTrue(Download("1", A, 100, "Librillo/Ann Lee/Home (2).epub") in p.kindle)
        // The Kindle's own file is in place: it is imported, not moved.
        assertTrue(p.kindle.none { it is Move })
    }

    @Test
    fun aMoveNeverTakesAPathInUse() {
        // Book 2 is at book 1's tidy path; book 1 gets the next free name.
        val p =
            plan(
                listOf(file("Documents/a.epub", A), file(home, B)),
                listOf(book("1", A), book("2", B)),
                listOf(base("1", "Documents/a.epub", A), base("2", home, B)),
            )
        assertEquals(listOf(Move("Documents/a.epub", "Librillo/Ann Lee/Home (2).epub")), p.kindle)
    }

    @Test
    fun aDownloadNeverTakesThePathOfAFileAboutToBeDeleted() {
        // Downloads run before deletes, so the purged book's path is still in use.
        val p =
            plan(
                listOf(file(home, A)),
                listOf(book("2", B)),
                listOf(base("gone", home, A)),
            )
        assertEquals(
            listOf(Download("2", B, 100, "Librillo/Ann Lee/Home (2).epub"), Delete(home)),
            p.kindle,
        )
    }

    @Test
    fun aDownloadAndAMoveNeverShareATarget() {
        val p =
            plan(
                listOf(file("Documents/a.epub", A)),
                listOf(book("1", A), book("2", B)),
                listOf(base("1", "Documents/a.epub", A)),
            )
        val targets = p.kindle.mapNotNull { (it as? Move)?.to ?: (it as? Download)?.to }
        assertEquals(2, targets.size)
        assertEquals(2, targets.map { it.lowercase() }.distinct().size)
    }

    // ---- Rule 12: order ----

    @Test
    fun thePlanDoesNotDependOnInputOrder() {
        val kindle =
            listOf(
                file("Documents/x.epub", C, title = "X", authors = listOf("Zed")),
                file(home, A, status = "finished", statusAt = 50),
                file("Documents/y.pdf", D),
            )
        val phone =
            listOf(
                book("1", A, lists = listOf("B", "A")),
                book("2", B, title = "Two", annotations = listOf(ann("z"), ann("y"))),
            )
        val p = plan(kindle, phone)
        assertEquals(p, plan(kindle.reversed(), phone.reversed()))
        // Phases: uploads, moves, downloads, then metadata.
        val kinds = p.kindle.map { it::class.simpleName }
        assertEquals(
            listOf(
                "Upload",
                "Upload",
                "Move",
                "Move",
                "Download",
                "AddAnnotations",
                "SetCollections",
            ),
            kinds,
        )
    }

    // ---- Input ----

    @Test
    fun badInputIsRefused() {
        for (path in listOf("", "/abs.epub", "a/../b.epub", "a//b.epub", "a\\b.epub")) {
            assertThrows(IllegalArgumentException::class.java) {
                plan(listOf(file(path, A, format = "epub")), emptyList())
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            plan(listOf(file(home, "XYZ")), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            plan(listOf(file(home, A), file(home, B)), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            SyncPlanner.plan(Fx.HOME, "../up", emptyList(), emptyList(), emptyList())
        }
    }

    @Test
    fun formatsLibrilloDoesNotKeepAreIgnored() {
        assertTrue(plan(listOf(file("Documents/a.mobi", A)), emptyList()).isEmpty)
    }

    @Test
    fun theLibraryFolderCanBeAnywhereUnderHome() {
        val p =
            SyncPlanner.plan(
                Fx.HOME,
                "/Books/Librillo/",
                emptyList(),
                listOf(book("1", A)),
                emptyList(),
            )
        assertEquals(listOf(Download("1", A, 100, "Books/Librillo/Ann Lee/Home.epub")), p.kindle)
    }
}
