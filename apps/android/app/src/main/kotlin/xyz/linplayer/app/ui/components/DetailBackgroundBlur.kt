package xyz.linplayer.app.ui.components

import android.graphics.Bitmap
import coil3.size.Size
import coil3.transform.Transformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 详情背景的轻模糊：有界小图在后台处理，API24也生效，不修改共享原图。 */
internal object DetailBackgroundBlur : Transformation() {
    override val cacheKey = "detail-background-blur-1280-r4-mix15-v3"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap = withContext(Dispatchers.Default) {
        val ratio = (1280f / maxOf(input.width, input.height)).coerceAtMost(1f)
        val w = (input.width * ratio).toInt().coerceAtLeast(1)
        val h = (input.height * ratio).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(input, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        val original = pixels.copyOf()
        val scratch = IntArray(pixels.size)
        repeat(2) {
            pass(pixels, scratch, w, h, true)
            pass(scratch, pixels, w, h, false)
        }
        // 保留85%原图、混入15%模糊图，避免降采样与滤波把背景细节抹掉。
        for (i in pixels.indices) {
            val sharpAlpha = original[i] ushr 24
            val softAlpha = pixels[i] ushr 24
            val weight = sharpAlpha * 85 + softAlpha * 15
            var mixed = (weight / 100) shl 24
            for (channel in 0..2) {
                val shift = channel * 8
                val sharp = original[i] ushr shift and 255
                val soft = pixels[i] ushr shift and 255
                val color = if (weight == 0) 0 else (sharp * sharpAlpha * 85 + soft * softAlpha * 15) / weight
                mixed = mixed or (color shl shift)
            }
            pixels[i] = mixed
        }
        Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /** 两轴盒式滤波，边缘夹到图内，避免背景四周出现透明黑边。 */
    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, horizontal: Boolean) {
        for (y in 0 until h) for (x in 0 until w) {
            var a = 0; var r = 0; var g = 0; var b = 0
            for (k in -4..4) {
                val xx = if (horizontal) (x + k).coerceIn(0, w - 1) else x
                val yy = if (horizontal) y else (y + k).coerceIn(0, h - 1)
                val c = src[yy * w + xx]
                val alpha = c ushr 24
                a += alpha
                r += (c ushr 16 and 255) * alpha
                g += (c ushr 8 and 255) * alpha
                b += (c and 255) * alpha
            }
            dst[y * w + x] = if (a == 0) 0 else (a / 9 shl 24) or (r / a shl 16) or (g / a shl 8) or (b / a)
        }
    }
}
