package dev.sadking.folio.core

/** Reading time cannot be inferred from sidecar retrieval time or the larger percentage. */
data class PositionBaseline(val phone: String, val kindle: String)

enum class PositionAction {
    TO_PHONE,
    TO_KINDLE,
    UNCHANGED,
    CONFLICT,
}

object PositionReconciliation {
    fun plan(phone: String, kindle: String, baseline: PositionBaseline?): PositionAction {
        if (phone == kindle) return PositionAction.UNCHANGED
        if (phone.isBlank()) return PositionAction.TO_PHONE
        if (kindle.isBlank()) return PositionAction.TO_KINDLE
        if (baseline == null) return PositionAction.CONFLICT
        val phoneChanged = phone != baseline.phone
        val kindleChanged = kindle != baseline.kindle
        return when {
            phoneChanged && kindleChanged -> PositionAction.CONFLICT
            phoneChanged -> PositionAction.TO_KINDLE
            kindleChanged -> PositionAction.TO_PHONE
            else -> PositionAction.UNCHANGED
        }
    }
}
