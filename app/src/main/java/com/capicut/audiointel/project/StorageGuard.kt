package com.capicut.audiointel.project

import android.content.Context
import android.os.StatFs
import java.io.File

object StorageGuard {
    fun freeBytes(dir: File): Long {
        return try {
            val fs = StatFs(dir.absolutePath)
            fs.availableBlocksLong * fs.blockSizeLong
        } catch (_: Exception) {
            0L
        }
    }

    fun hasRoom(context: Context, requiredBytes: Long): Boolean {
        val free = freeBytes(context.filesDir)
        return free > requiredBytes + 50L * 1024L * 1024L
    }
}
