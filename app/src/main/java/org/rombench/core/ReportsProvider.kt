package org.rombench.core

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Read-only access to the latest report for `adb shell content read
 * --uri content://<package>.reports/latest.json`. Protected by android.permission.DUMP in the
 * manifest, which only the shell and the system hold: on some Android 11 builds the shell
 * cannot read Android/data directly.
 */
class ReportsProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        if (!name.startsWith("latest.") || name.contains('/')) throw FileNotFoundException(name)
        val dir = context?.getExternalFilesDir("reports") ?: throw FileNotFoundException()
        val f = File(dir, name)
        if (!f.exists()) throw FileNotFoundException(name)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = null
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
