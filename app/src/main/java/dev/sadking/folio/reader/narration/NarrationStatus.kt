package dev.sadking.folio.reader.narration

/** The choices offered when the phone cannot make the natural voice fast enough. */
internal enum class SlowChoice {
    PREPARE_CHAPTER,
    WITH_BREAKS,
    PHONE_VOICE,
    FASTER_MODEL,
}

/** What the natural voice is doing, for the reader's controls. */
internal sealed interface NarrationStatus {
    data object Idle : NarrationStatus

    /** The model is loading. */
    data object Loading : NarrationStatus

    /** Making speech before it can start (or restart after a stall). */
    data class Preparing(val reserveSeconds: Double, val etaSeconds: Int?) : NarrationStatus

    data class Playing(val reserveSeconds: Double, val pace: Double?) : NarrationStatus

    data class Paused(val reserveSeconds: Double) : NarrationStatus

    data class PreparingChapter(val fraction: Float, val etaSeconds: Int) : NarrationStatus

    data class TooSlow(
        /** 1 / rEff: speech seconds made per second. */
        val pace: Double,
        val prepareChapterSeconds: Int,
        val breakEverySeconds: Int,
        val breakSeconds: Int,
        val offerFasterModel: Boolean,
        val fasterModelMb: Int,
    ) : NarrationStatus

    data class Failed(val message: String) : NarrationStatus
}
