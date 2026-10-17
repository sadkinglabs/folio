package dev.sadking.folio.reader

import android.content.Context
import android.util.Log
import dev.sadking.folio.BuildConfig
import dev.sadking.folio.reader.narration.BoundaryRecord
import dev.sadking.folio.reader.narration.NarrationMetrics
import dev.sadking.folio.reader.narration.SessionSummary
import dev.sadking.folio.reader.narration.UnitRecord
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Writes one listening session's measurements when "Record read-aloud measurements" is on:
 * `units.csv`, `boundaries.csv` and `summary.json` under `files/narration/sessions/<start-ms>/`.
 * Lengths and timings only, never text. Writes run on [narrationIo].
 */
internal class NarrationRecorder(val dir: File) {
    private val scope = CoroutineScope(SupervisorJob() + narrationIo)

    init {
        scope.launch {
            dir.mkdirs()
            File(dir, UNITS).writeText(NarrationMetrics.unitsCsvHeader() + "\n")
            File(dir, BOUNDARIES).writeText(NarrationMetrics.boundariesCsvHeader() + "\n")
        }
    }

    fun unit(record: UnitRecord) {
        val row = NarrationMetrics.unitsCsvRow(record)
        scope.launch { File(dir, UNITS).appendText(row + "\n") }
    }

    fun boundary(record: BoundaryRecord) {
        val row = NarrationMetrics.boundariesCsvRow(record)
        scope.launch { File(dir, BOUNDARIES).appendText(row + "\n") }
    }

    /** Ends the session: its summary marks the folder as complete. */
    fun finish(summary: SessionSummary) {
        val json = summary.toJson()
        scope.launch { File(dir, SUMMARY).writeText(json) }
    }

    companion object {
        private const val UNITS = "units.csv"
        private const val BOUNDARIES = "boundaries.csv"
        private const val SUMMARY = "summary.json"

        private fun sessions(context: Context) =
            File(context.applicationContext.filesDir, "narration/sessions")

        /** A new recording, named by its start time. */
        fun start(context: Context): NarrationRecorder =
            NarrationRecorder(File(sessions(context), System.currentTimeMillis().toString()))

        /** The newest finished recording, for Share; null when none. */
        fun last(context: Context): File? =
            sessions(context)
                .listFiles()
                ?.filter { File(it, SUMMARY).isFile }
                ?.maxByOrNull { it.name.toLongOrNull() ?: 0L }

        /** Debug builds only, and numbers only: never book text or anything personal. */
        fun log(message: () -> String) {
            if (BuildConfig.DEBUG) Log.d("Narration", message())
        }
    }
}
