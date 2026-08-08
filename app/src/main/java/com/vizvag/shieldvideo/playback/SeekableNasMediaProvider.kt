package com.vizvag.shieldvideo.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.util.Log
import com.vizvag.shieldvideo.ShieldVideoApp
import java.io.FileNotFoundException

/**
 * Exposes the active [LocalMediaProxy] file as a seekable `content://` URI via
 * [StorageManager.openProxyFileDescriptor]. MX / X Player only treat `file`/`content`
 * as local media with TV remote controls; `http` hits their WebDelegate and disables them.
 */
class SeekableNasMediaProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = proxyOrNull()?.activeMime()

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val proxy = proxyOrNull() ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols)
        val row = arrayOfNulls<Any>(cols.size)
        cols.forEachIndexed { i, col ->
            row[i] = when (col) {
                OpenableColumns.DISPLAY_NAME, "_display_name" -> proxy.activeFileName()
                OpenableColumns.SIZE, "_size" -> proxy.activeSize()
                else -> null
            }
        }
        cursor.addRow(row)
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (!mode.contains('r')) throw IllegalArgumentException("Read-only: $mode")
        val proxy = proxyOrNull()
            ?: throw FileNotFoundException("No active NAS media session")
        val size = proxy.activeSize()
        if (size <= 0L) throw FileNotFoundException("Unknown media size")
        val sm = context!!.getSystemService(StorageManager::class.java)
            ?: throw FileNotFoundException("StorageManager unavailable")
        return sm.openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY,
            object : ProxyFileDescriptorCallback() {
                override fun onGetSize(): Long = size

                override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                    return try {
                        proxy.readAt(offset, data, size)
                    } catch (e: Exception) {
                        Log.w(TAG, "onRead offset=$offset size=$size: ${e.message}")
                        throw e
                    }
                }

                override fun onRelease() {
                    Log.i(TAG, "PFD released")
                }
            },
            pfdHandler(),
        )
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        val pfd = openFile(uri, mode)
        val length = proxyOrNull()?.activeSize() ?: AssetFileDescriptor.UNKNOWN_LENGTH
        return AssetFileDescriptor(pfd, 0L, length)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun proxyOrNull(): LocalMediaProxy? =
        (context?.applicationContext as? ShieldVideoApp)?.localMediaProxy?.takeIf { it.hasActive() }

    companion object {
        private const val TAG = "SeekableNasMedia"
        private const val PATH = "item/current"

        @Volatile
        private var handlerThread: HandlerThread? = null

        @Volatile
        private var handler: Handler? = null

        fun contentUri(context: Context): Uri =
            Uri.Builder()
                .scheme("content")
                .authority("${context.packageName}.nasmedia")
                .appendEncodedPath(PATH)
                .build()

        private fun pfdHandler(): Handler {
            synchronized(this) {
                if (handler == null) {
                    val thread = HandlerThread("nas-media-pfd").also { it.start() }
                    handlerThread = thread
                    handler = Handler(thread.looper)
                }
                return handler!!
            }
        }
    }
}
