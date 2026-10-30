package com.inkreader.parse

import android.graphics.Matrix
import android.media.ExifInterface
import java.io.InputStream

/** Platform JPEG orientation, without another decoder dependency. Stored image bytes remain unchanged. */
@Suppress("DEPRECATION")
object DocumentImageOrientation {
    fun read(input: InputStream): Int = runCatching {
        ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    fun swapsAxes(value: Int) = value in 5..8
    fun matrix(value: Int): Matrix = Matrix().apply {
        when (value) {
            2 -> setScale(-1f, 1f)
            3 -> setRotate(180f)
            4 -> setScale(1f, -1f)
            5 -> { setRotate(90f); postScale(-1f, 1f) }
            6 -> setRotate(90f)
            7 -> { setRotate(270f); postScale(-1f, 1f) }
            8 -> setRotate(270f)
        }
    }
}
