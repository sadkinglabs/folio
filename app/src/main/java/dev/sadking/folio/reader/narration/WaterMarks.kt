package dev.sadking.folio.reader.narration

/**
 * Hysteresis for the reserve: synthesis stops at the high mark and resumes only below the low mark,
 * so the worker runs in long stretches instead of one unit at a time. While strained (the phone
 * barely keeps up, or is warm) the high mark is higher, to ride out slow patches.
 */
internal class WaterMarks(private val tuning: NarrationTuning = NarrationTuning.DEFAULT) {
    private var synthesizing = true

    /** Hysteresis: false at ≥ high until the reserve drops below low. */
    fun shouldSynthesize(reserveSeconds: Double, strained: Boolean): Boolean {
        val high = if (strained) tuning.highWaterStrainedSeconds else tuning.highWaterSeconds
        if (synthesizing && reserveSeconds >= high) {
            synthesizing = false
        } else if (!synthesizing && reserveSeconds < tuning.lowWaterSeconds) {
            synthesizing = true
        }
        return synthesizing
    }
}
