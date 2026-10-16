package dev.sadking.folio.data

/**
 * Records that came from an e-reader through the Librillo plugin are keyed `link:<e-reader id>`,
 * apart from SSH devices and the phone: annotations synced from it, and the downloads the first
 * plugin confirmed (nothing writes those any more; old rows still count as "on the e-reader").
 */
const val LINK_DEVICE_PREFIX = "link:"
