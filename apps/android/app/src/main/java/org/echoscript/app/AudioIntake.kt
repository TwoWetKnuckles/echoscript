package org.echoscript.app

import android.content.Intent
import android.net.Uri

internal fun audioPickerIntent(action: String): Intent {
    require(action == Intent.ACTION_OPEN_DOCUMENT || action == Intent.ACTION_GET_CONTENT)
    return Intent(action).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

/** Some device providers return a single ClipData item instead of Intent.data. */
internal fun selectedAudioUri(data: Intent?): Uri? {
    val clips = data?.clipData
    require(clips == null || clips.itemCount <= 1) { "Choose one recording at a time." }
    val uri = data?.data ?: if (clips?.itemCount == 1) clips.getItemAt(0).uri else null
    require(uri == null || uri.scheme in setOf("content", "file")) { "The picker did not return a local readable file. Try the other file picker." }
    return uri
}
