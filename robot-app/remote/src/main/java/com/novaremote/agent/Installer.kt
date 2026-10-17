package com.novaremote.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.InputStream

// takes an uploaded apk and hands it to Android's own package installer. the system still shows
// the "Install?" prompt - this never installs silently (that needs ADB, which we do not have).
object Installer {

    private fun incomingDir(context: Context): File =
        File(context.filesDir, "incoming").apply { mkdirs() }

    // save the uploaded bytes to a private file we can expose through the FileProvider
    fun save(context: Context, name: String, input: InputStream): File {
        val safe = name.substringAfterLast('/').substringAfterLast('\\')
            .ifBlank { "upload.apk" }
            .let { if (it.endsWith(".apk", true)) it else "$it.apk" }
        val file = File(incomingDir(context), safe)
        file.outputStream().use { out -> input.copyTo(out) }
        return file
    }

    fun saveBytes(context: Context, name: String, bytes: ByteArray): File =
        save(context, name, bytes.inputStream())

    // open the system installer for a saved apk
    fun install(context: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
