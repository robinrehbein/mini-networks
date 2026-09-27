package com.mininetworks.game.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import com.mininetworks.game.R
import java.io.File

/**
 * Hands a [ShareCard] to the Android share sheet (docs/TOP100.md D2). The PNG goes to the app's cache
 * (`cache/shared/`, res/xml/file_paths.xml) and leaves the app only as a `content://` URI of the [FileProvider] with a
 * read grant for the chosen app: no storage permission, nothing in the gallery, and each share overwrites the last one.
 */
object ShareSheet {
    const val DIR = "shared"
    const val FILE = "mini-networks.png"
    const val MIME = "image/png"

    fun authority(context: Context) = "${context.packageName}.fileprovider"

    /** Writes [card] as PNG into [cacheDir]; disk work, so on GameIo. Returns the file. */
    fun write(cacheDir: File, card: Bitmap): File {
        val dir = File(cacheDir, DIR).apply { mkdirs() }
        val file = File(dir, FILE)
        file.outputStream().use { card.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    /** The content URI of [file] that other apps may read once granted. */
    fun uriOf(context: Context, file: File): Uri = FileProvider.getUriForFile(context, authority(context), file)

    /** The share intent for [file] with [text]; the chooser around it is [chooser]. */
    fun intent(context: Context, file: File, text: String): Intent {
        val uri = uriOf(context, file)
        return Intent(Intent.ACTION_SEND).apply {
            type = MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text)
            // The clip carries the grant through the chooser to the app the player picks (and gives it a preview).
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** The system share sheet for [file] and [text]. */
    fun chooser(context: Context, file: File, text: String): Intent =
        Intent.createChooser(intent(context, file, text), context.getString(R.string.share_chooser)).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
}
