package com.anytypeio.anytype.core_models

/** A key for heart's local JSON API ("app" in heart's LocalLink RPCs). */
data class LocalApiApp(
    val appHash: String,
    val appName: String,
    val appKey: String,
    val createdAt: Long
)
