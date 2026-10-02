package com.capicut.audiointel.vad

import android.net.Uri
import com.tharunbirla.librecuts.models.EditOperation

/**
 * Maps VAD keep-segments onto LibreCuts timeline operations.
 * One segment → [Trim]; several → [Trim first] + [Merge rest as same-source clips].
 */
object AutoCutApplier {

    fun toOperations(sourceUri: Uri, segments: List<SpeechSegment>): List<EditOperation> {
        val keep = segments.filter { it.endMs > it.startMs }
        if (keep.isEmpty()) return emptyList()
        if (keep.size == 1) {
            return listOf(EditOperation.Trim(keep[0].startMs, keep[0].endMs))
        }
        val first = keep.first()
        val rest = keep.drop(1).map { seg ->
            EditOperation.MergeItem(
                uri = sourceUri,
                durationMs = seg.endMs - seg.startMs,
                trimStartMs = seg.startMs,
                trimEndMs = seg.endMs
            )
        }
        return listOf(
            EditOperation.Trim(first.startMs, first.endMs),
            EditOperation.Merge(rest)
        )
    }
}
