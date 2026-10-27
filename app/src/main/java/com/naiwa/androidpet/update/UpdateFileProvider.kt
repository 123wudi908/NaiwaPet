package com.naiwa.androidpet.update

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException

/** Only the verified private update file can be granted to the Android system installer. */
class UpdateFileProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri): String? = if (uri.path == "/update.apk") "application/vnd.android.package-archive" else null
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (uri.path != "/update.apk" || mode != "r") throw FileNotFoundException("Invalid update file")
        val file = UpdateManager.get(requireNotNull(context)).validFileForProvider() ?: throw FileNotFoundException("Update is not verified")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        if (uri.path != "/update.apk") return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val file = UpdateManager.get(requireNotNull(context)).apkFile
        return MatrixCursor(columns).apply { addRow(columns.map { when (it) {
            OpenableColumns.DISPLAY_NAME -> "naiwa-update.apk"; OpenableColumns.SIZE -> file.length(); else -> null
        } }) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
