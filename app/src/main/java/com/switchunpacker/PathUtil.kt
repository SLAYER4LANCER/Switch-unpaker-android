package com.switchunpacker

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File

object PathUtil {
    private const val EXTERNAL_STORAGE_AUTH = "com.android.externalstorage.documents"

    private fun docIdToPath(docId: String): String? {
        val i = docId.indexOf(':')
        if (i < 0) return null
        val volume = docId.substring(0, i)
        val rel = docId.substring(i + 1)
        val base = if (volume == "primary") {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            "/storage/$volume"
        }
        return if (rel.isEmpty()) base else "$base/$rel"
    }

    /** Real file path for a picked file on phone/SD storage, or null (then copy it instead). */
    fun fileFromUri(uri: Uri): File? {
        if (uri.authority != EXTERNAL_STORAGE_AUTH) return null
        val path = docIdToPath(DocumentsContract.getDocumentId(uri)) ?: return null
        val f = File(path)
        return if (f.isFile && f.canRead()) f else null
    }

    /** Real folder path for a picked folder on phone/SD storage, or null. */
    fun dirFromTree(uri: Uri): File? {
        if (uri.authority != EXTERNAL_STORAGE_AUTH) return null
        val path = docIdToPath(DocumentsContract.getTreeDocumentId(uri)) ?: return null
        return File(path)
    }

    fun displayName(ctx: Context, uri: Uri): String {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getString(0)
                if (!n.isNullOrEmpty()) return n
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "game.nsp"
    }

    fun copyToCache(ctx: Context, uri: Uri, name: String): File {
        val f = File(ctx.cacheDir, name)
        val input = ctx.contentResolver.openInputStream(uri) ?: throw Exception("Cannot open the selected file.")
        input.use { i -> f.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
        return f
    }
}
