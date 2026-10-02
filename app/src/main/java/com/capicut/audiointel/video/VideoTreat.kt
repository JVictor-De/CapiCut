package com.capicut.audiointel.video

import com.tharunbirla.librecuts.commands.AddOperationCommand
import com.tharunbirla.librecuts.commands.EditCommand
import com.tharunbirla.librecuts.commands.ReplaceUniqueOperationCommand
import com.tharunbirla.librecuts.models.EditOperation

/**
 * Face-aware 9:16 reframe. Without MediaPipe, applies a centered 9:16 crop.
 */
class FaceReframeEngine {
    fun crop916(): EditCommand {
        return ReplaceUniqueOperationCommand(
            EditOperation.Crop(aspectRatio = "9:16"),
            description = "CapiCut 9:16 crop"
        )
    }
}

class AutoVideoTreat {
    fun exposureLift(): EditCommand {
        return AddOperationCommand(
            EditOperation.Adjust(index = 0, exposure = 12, contrast = 8),
            description = "CapiCut exposure"
        )
    }
}
