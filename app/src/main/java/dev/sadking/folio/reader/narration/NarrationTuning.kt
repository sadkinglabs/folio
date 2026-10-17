package dev.sadking.folio.reader.narration

/**
 * Every number the read-aloud pipeline uses, in one place so tests can vary them and the owner can
 * tune by ear. Seconds are listening seconds unless the name says otherwise. See
 * docs/READ_ALOUD_PIPELINE.md, section 4, for where each value comes from.
 */
internal data class NarrationTuning(
    val trackBufferSeconds: Double = 0.5,
    val trackStartThresholdSeconds: Double = 0.05,
    val writerTickMs: Long = 15,
    val speculativeUtterances: Int = 2,
    val speculativeSeconds: Double = 3.0,
    val lowWaterSeconds: Double = 30.0,
    val highWaterSeconds: Double = 60.0,
    val highWaterStrainedSeconds: Double = 120.0,
    val strainedRatio: Double = 0.8,
    val recentRingUnits: Int = 3,
    val recentRingSeconds: Double = 30.0,
    val maxUnitChars: Int = 300,
    val hardSplitChars: Int = 400,
    val minSplitChars: Int = 80,
    val firstUnitMaxChars: Int = 120,
    val firstUnitMinChars: Int = 40,
    val tinyUnitChars: Int = 40,
    val tinyMergeMaxChars: Int = 200,
    val startBudgetSeconds: Double = 20.0,
    val gateZ: Double = 2.0,
    val gateGuardSeconds: Double = 0.4,
    val gateDeltaNormal: Double = 0.05,
    val gateDeltaThermal: Double = 0.15,
    val tooSlowRatio: Double = 0.97,
    val priorCountForTooSlow: Int = 20,
    val horizonUnits: Int = 400,
    val horizonSeconds: Double = 1800.0,
    val starvationSeconds: Double = 0.02,
    val preparingDebounceMs: Long = 300,
    val stallsForTooSlow: Int = 3,
    val stallWindowSeconds: Double = 300.0,
    val holdSynthesisSeconds: Double = 30.0,
    val silenceScale: Float = 0.4f,
    val edgeSilenceThreshold: Double = 0.005,
    val edgeKeepSeconds: Double = 0.05,
    val gapSentenceSeconds: Double = 0.32,
    val gapParagraphSeconds: Double = 0.60,
    val gapClauseSeconds: Double = 0.12,
    val gapFragmentSeconds: Double = 0.0,
    val fadeSamples: Int = 120,
    val cacheBudgetBytes: Long = 256L * 1024 * 1024,
    val cacheEvictToFraction: Double = 0.9,
    val runtimeIdleReleaseMs: Long = 180_000,
    val plannerAheadSeconds: Double = 90.0,
    val plannerCapUtterances: Int = 600,
    val rebaseScanLimit: Int = 400,
    val alignWindow: Int = 3,
    val metricsRing: Int = 200,
    val thermalSampleMs: Long = 10_000,
    val wakeLockTimeoutMs: Long = 600_000,
    val audioSecondsPerCharPrior: Double = 0.051,
) {
    companion object {
        val DEFAULT = NarrationTuning()
    }
}
