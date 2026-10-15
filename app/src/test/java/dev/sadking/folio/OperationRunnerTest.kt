package dev.sadking.folio

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class OperationRunnerTest {
    private val text =
        FailureText(
            hostKey = "host key",
            passwordFreeRejected = "password-free rejected",
            signInRejected = "sign-in rejected",
            unsupportedAlgorithms = "algorithms",
            connection = "connection",
            fileTransfer = "file transfer",
            incomplete = "Something went wrong. Try again.",
            generic = "Something went wrong. Check the file, free space and connection.",
            transferBusy = "Finish or cancel the current transfer first.",
        )

    private fun runner(transfer: Boolean = false) =
        OperationRunner(transferActive = { transfer }, authentication = { "Key" }, text = { text })

    @Test
    fun deviceWorkWaitsForTheTransferButLibraryWorkDoesNot() = runBlocking {
        val runner = runner(transfer = true)
        runner.launch(this, "Sending", device = true) { "sent" }
        val refused = runner.notices.first()
        assertTrue(refused.failed)
        assertEquals(text.transferBusy, refused.message)

        runner.launch(this, "Saving", device = false) { "saved" }
        assertEquals(Operation(message = "saved"), runner.notices.first())
    }

    @Test
    fun undoableWorkOffersItsUndoAndPlainWorkDoesNot() = runBlocking {
        val runner = runner()
        var undone = false
        runner.launchUndoable(this, "Removing", device = false) {
            "Removed 1 book" to { undone = true }
        }
        val notice = runner.notices.first()
        assertEquals("Removed 1 book", notice.message)
        notice.undo!!.invoke()
        assertTrue(undone)

        runner.launch(this, "Saving", device = false) { "saved" }
        assertNull(runner.notices.first().undo)
    }

    @Test
    fun failuresAreExplainedWithoutRawDetails() = runBlocking {
        val runner = runner()
        runner.launch(this, "Exporting", device = false) {
            throw IOException("/data/user/0/dev.sadking.folio/files/private.epub")
        }
        val notice = runner.notices.first()
        assertTrue(notice.failed)
        assertFalse(notice.message.contains("/data"))
        assertEquals(text.generic, notice.message)
        assertEquals(notice, runner.state.value)
    }

    @Test
    fun onlyMessagesWrittenForTheUserAreShown() {
        assertEquals(
            "Enter a list name",
            safeError(IllegalArgumentException("Enter a list name"), text),
        )
        assertEquals(
            text.incomplete,
            safeError(runCatching { require(false) }.exceptionOrNull() as Exception, text),
        )
        assertEquals(
            text.incomplete,
            safeError(runCatching { check(false) }.exceptionOrNull() as Exception, text),
        )
        assertEquals(text.connection, safeError(com.jcraft.jsch.JSchException("timeout"), text))
        assertEquals(
            text.signInRejected,
            safeError(com.jcraft.jsch.JSchException("Auth fail"), text, "Key"),
        )
        assertEquals(
            text.passwordFreeRejected,
            safeError(com.jcraft.jsch.JSchException("Auth fail"), text, "KOReader password-free"),
        )
    }

    @Test
    fun workRunsOneAtATimeInOrder() = runBlocking {
        val runner = runner()
        val order = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()
        runner.launch(this, "First", device = false) {
            release.await()
            order += "first"
            "first"
        }
        runner.launch(this, "Second", device = false) {
            order += "second"
            "second"
        }
        yield()
        assertEquals(Operation(true, "First"), runner.state.value)
        release.complete(Unit)
        runner.notices.first { it.message == "second" }
        assertEquals(listOf("first", "second"), order)
    }

    @Test
    fun aClosedScreenDoesNotLeaveTheStatusBusy() = runBlocking {
        val runner = runner()
        val screen = CoroutineScope(Job())
        runner.launch(screen, "Waiting", device = false) { awaitCancellation() }
        runner.state.first { it.busy }
        screen.cancel()
        assertEquals(Operation(), runner.state.first { !it.busy })
    }
}
