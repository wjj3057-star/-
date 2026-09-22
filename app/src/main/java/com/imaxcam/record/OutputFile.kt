package com.imaxcam.record

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A pending recording in the shared Movies collection.
 *
 * The entry is created up front and marked pending, so a clip interrupted by a crash or a
 * kill never shows up half-written in the gallery.
 */
class OutputFile private constructor(
    private val resolver: ContentResolver,
    val uri: Uri,
    val displayName: String,
    private val pfd: ParcelFileDescriptor
) {

    val fileDescriptor: java.io.FileDescriptor get() = pfd.fileDescriptor

    /** Publishes the finished clip so it becomes visible to the gallery. */
    fun publish() {
        runCatching { pfd.close() }
        val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        runCatching { resolver.update(uri, values, null, null) }
    }

    /** Drops a recording that never produced usable samples. */
    fun discard() {
        runCatching { pfd.close() }
        runCatching { resolver.delete(uri, null, null) }
    }

    companion object {
        private const val RELATIVE_PATH = "Movies/IMAXCam"

        fun create(
            context: Context,
            ratioLabel: String,
            width: Int,
            height: Int,
            hdrLabel: String
        ): OutputFile {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val safeRatio = ratioLabel.replace(':', '-')
            val name = "IMAX_${safeRatio}_${width}x${height}_${hdrLabel}_$stamp.mp4"

            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.Video.Media.WIDTH, width)
                put(MediaStore.Video.Media.HEIGHT, height)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val collection = MediaStore.Video.Media.getContentUri(
                MediaStore.VOLUME_EXTERNAL_PRIMARY
            )
            val uri = resolver.insert(collection, values)
                ?: error("Could not create output file in $RELATIVE_PATH")
            val pfd = resolver.openFileDescriptor(uri, "rw")
                ?: error("Could not open $uri for writing")
            return OutputFile(resolver, uri, name, pfd)
        }
    }
}
