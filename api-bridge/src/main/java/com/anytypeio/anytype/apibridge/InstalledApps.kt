package com.anytypeio.anytype.apibridge

import android.content.pm.PackageManager
import android.text.TextUtils
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/**
 * The name of an installed app, as the screens of the bridge show it. The app chooses its own
 * label, so it is made safe first: only its first line, trimmed, at most [MAX_LABEL_CHARS]
 * characters, ellipsized when wider than [MAX_LABEL_WIDTH_DIP]. The package name stands in when
 * the app is not installed or its label is blank.
 *
 * Blocking: call it off the main thread.
 */
@RequiresApi(29)
internal fun PackageManager.safeAppLabel(pkg: String): String {
    val raw = try {
        getApplicationLabel(getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        return pkg
    }
    return TextUtils.makeSafeForPresentation(
        raw,
        MAX_LABEL_CHARS,
        MAX_LABEL_WIDTH_DIP,
        TextUtils.SAFE_STRING_FLAG_TRIM or TextUtils.SAFE_STRING_FLAG_FIRST_LINE
    ).toString().ifBlank { pkg }
}

/** The icon of an installed app at [px] square, or null when the package or its icon is unavailable. Blocking. */
internal fun PackageManager.appIcon(pkg: String, px: Int): ImageBitmap? = try {
    getApplicationIcon(pkg).toBitmap(px, px).asImageBitmap()
} catch (e: Exception) {
    null
}

/** Only this many characters of the label are considered: far more than any real app name. */
internal const val MAX_LABEL_CHARS = 100

/**
 * Wider labels end in an ellipsis. TextUtils measures at its own fixed text size, so this is
 * only a bound on length, not on the space the label takes in the layout (which wraps it).
 */
private const val MAX_LABEL_WIDTH_DIP = 500f
