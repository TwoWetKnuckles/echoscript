package org.echoscript.app

import android.content.Intent
import android.net.Uri

internal fun audioPickerIntent(action: String): Intent {
    require(action == Intent.ACTION_OPEN_DOCUMENT || action == Intent.ACTION_GET_CONTENT)
    return Intent(action).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

/** Providers may return one URI in data, ClipData or an EXTRA_STREAM payload. */
@Suppress("DEPRECATION")
internal fun selectedAudioUri(data: Intent?): Uri? {
    val clips = data?.clipData
    require(clips == null || clips.itemCount <= 1) { "Choose one recording at a time." }
    val clipped = if (clips?.itemCount == 1) clips.getItemAt(0).uri else null
    val extra = data?.extras?.get(Intent.EXTRA_STREAM)
    val stream = when (extra) {
        is Uri -> extra
        is ArrayList<*> -> {
            require(extra.size <= 1) { "Choose one recording at a time." }
            require(extra.isEmpty() || extra[0] is Uri) { "The picker returned an unreadable recording. Choose the file again." }
            extra.firstOrNull() as? Uri
        }
        null -> null
        else -> throw IllegalArgumentException("The picker returned an unreadable recording. Choose the file again.")
    }
    val returned = listOfNotNull(data?.data, clipped, stream)
    require(returned.distinct().size <= 1) { "The picker returned conflicting recordings. Choose one file again." }
    val uri = returned.firstOrNull()
    require(uri == null || uri.scheme in setOf("content", "file")) { "The picker did not return a local readable file. Try the other file picker." }
    return uri
}
