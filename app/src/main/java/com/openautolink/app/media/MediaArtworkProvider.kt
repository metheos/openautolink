package com.openautolink.app.media

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.provider.OpenableColumns
import com.openautolink.app.diagnostics.OalLog
import java.io.FileNotFoundException
import java.io.File
import java.io.IOException
import java.util.UUID

/** Read-only, opaque artwork URIs for AAOS consumers outside our process. */
class MediaArtworkProvider : ContentProvider() {
    companion object {
        private const val TAG = "MediaArtwork"
        private const val MAX_IMAGES = 8
        private class Image(val bitmap: Bitmap, val file: File) {
            var reads = 0
        }
        private val images = LinkedHashMap<String, Image>()
        private var directory: File? = null

        internal fun publish(context: Context, bitmap: Bitmap): Uri = synchronized(images) {
            val root = directory ?: File(context.cacheDir, "media-artwork").also { root ->
                if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create artwork cache")
                // Remove only this provider's opaque snapshots left by a previous process.
                root.listFiles()?.filter { it.name.matches(Regex("[a-f0-9-]{36}\\.png")) }?.forEach {
                    if (!it.delete()) OalLog.w(TAG, "artwork cache stale-delete failed")
                }
                directory = root
            }
            val existing = images.entries.firstOrNull { it.value.bitmap === bitmap }
            val id = existing?.key
                ?: UUID.randomUUID().toString().also {
                    images[it] = Image(bitmap, File(root, "$it.png"))
                    while (images.size > MAX_IMAGES) {
                        val oldest = images.keys.first()
                        val removed = images.remove(oldest)!!
                        deleteFile(removed)
                        OalLog.w(TAG, "artwork evict id=$oldest reads=${removed.reads}")
                    }
                }
            val uri = Uri.Builder().scheme("content").authority("${context.packageName}.media.artwork")
                .appendPath("art").appendPath(id).build()
            OalLog.w(TAG, "artwork publish id=$id reused=${existing != null} dimensions=${bitmap.width}x${bitmap.height} " +
                "config=${bitmap.config} allocationBytes=${bitmap.allocationByteCount} cache=${images.size}/$MAX_IMAGES " +
                "reads=${images[id]?.reads} sdk=${Build.VERSION.SDK_INT} appUid=${Process.myUid()} uri=$uri")
            uri
        }

        private fun deleteFile(image: Image) {
            if (image.file.exists() && !image.file.delete()) OalLog.w(TAG, "artwork cache delete failed")
        }

        internal fun clear() = synchronized(images) {
            OalLog.w(TAG, "artwork clear snapshots=${images.size} reads=${images.values.sumOf { it.reads }}")
            images.values.forEach(::deleteFile)
            images.clear()
            directory = null
        }
    }

    override fun onCreate(): Boolean {
        OalLog.w(TAG, "artwork provider created sdk=${Build.VERSION.SDK_INT} appUid=${Process.myUid()} " +
            "appUser=${Process.myUserHandle()} authority=${context?.packageName}.media.artwork")
        return true
    }

    private fun caller(): String {
        val uid = Binder.getCallingUid()
        val packages = context?.packageManager?.getPackagesForUid(uid)?.joinToString(",") ?: "unresolved"
        return "callerUid=$uid callerPid=${Binder.getCallingPid()} callerUser=${UserHandle.getUserHandleForUid(uid)} " +
            "appUid=${Process.myUid()} packages=$packages"
    }

    private fun image(uri: Uri): Image {
        if (uri.scheme != "content" || uri.authority != "${context?.packageName}.media.artwork" ||
            uri.pathSegments.size != 2 || uri.pathSegments[0] != "art" ||
            uri.query != null || uri.fragment != null
        ) {
            OalLog.w(TAG, "artwork lookup invalid-uri ${caller()}")
            throw FileNotFoundException("Invalid artwork URI")
        }
        return synchronized(images) { images[uri.pathSegments[1]] }
            ?: run {
                OalLog.w(TAG, "artwork lookup miss id=${uri.lastPathSegment} cache=${synchronized(images) { images.size }} ${caller()}")
                throw FileNotFoundException("Artwork expired")
            }
    }

    /** Called only by provider readers, never by the metadata publication thread. */
    private fun prepare(image: Image): File {
        if (!image.file.exists()) {
            try {
                image.file.outputStream().use { stream ->
                    if (!image.bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                        throw IOException("Artwork compression failed")
                    }
                }
            } catch (failure: IOException) {
                deleteFile(image)
                OalLog.w(TAG, "artwork encode failed", failure)
                throw failure
            }
        }
        return image.file
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = synchronized(images) {
        val start = SystemClock.elapsedRealtime()
        OalLog.w(TAG, "artwork open request id=${uri.lastPathSegment} mode=$mode ${caller()}")
        if (mode != "r") throw FileNotFoundException("Artwork is read-only")
        val image = image(uri)
        try {
            val file = prepare(image)
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            image.reads++
            OalLog.w(TAG, "artwork open success id=${uri.lastPathSegment} transport=seekable-file bytes=${descriptor.statSize} " +
                "reads=${image.reads} elapsedMs=${SystemClock.elapsedRealtime() - start} ${caller()}")
            descriptor
        } catch (failure: IOException) {
            OalLog.w(TAG, "artwork open failed id=${uri.lastPathSegment} ${caller()}", failure)
            throw failure
        }
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        val descriptor = openFile(uri, mode)
        return AssetFileDescriptor(descriptor, 0, descriptor.statSize)
    }

    override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String, opts: Bundle?): AssetFileDescriptor? {
        OalLog.w(TAG, "artwork typed-open id=${uri.lastPathSegment} requestedMime=$mimeTypeFilter ${caller()}")
        return super.openTypedAssetFile(uri, mimeTypeFilter, opts)
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor = synchronized(images) {
        val image = image(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val size = if (OpenableColumns.SIZE in columns) prepare(image).length() else null
        OalLog.w(TAG, "artwork query id=${uri.lastPathSegment} columns=${columns.joinToString(",")} " +
            "bytes=$size selectionPresent=${selection != null} ${caller()}")
        MatrixCursor(columns).apply {
            addRow(columns.map {
                when (it) {
                    OpenableColumns.DISPLAY_NAME -> "art.png"
                    OpenableColumns.SIZE -> size
                    else -> null
                }
            }.toTypedArray())
        }
    }

    override fun getType(uri: Uri): String {
        image(uri)
        OalLog.w(TAG, "artwork type id=${uri.lastPathSegment} mime=image/png ${caller()}")
        return "image/png"
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri =
        throw UnsupportedOperationException("Artwork is read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Artwork is read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Artwork is read-only")
}
