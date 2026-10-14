package dev.sadking.folio

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class OperationRunnerTest {
    private fun runner(transfer: Boolean = false) =
        OperationRunner(transferActive = { transfer }, authentication = { "Key" })

    @Test
    fun deviceWorkWaitsForTheTransferButLibraryWorkDoesNot() = runBlocking {
        val runner = runner(transfer = true)
        runner.launch(this, "Sending", device = true) { "sent" }
        val refused = runner.notices.first()
        assertTrue(refused.failed)
        assertEquals("Finish or cancel the current transfer first", refused.message)

        runner.launch(this, "Saving", device = false) { "saved" }
        assertEquals(Operation(message = "saved"), runner.notices.first())
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
        assertEquals(notice, runner.state.value)
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
