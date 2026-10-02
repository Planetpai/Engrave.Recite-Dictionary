package top.southfish.engrave.ui.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 自定义头像图片的落盘逻辑：从相册 Uri 解码、等比压到 512px 内、
 * 写进 filesDir/avatar.jpg。选图入口（应用内 AvatarPickerScreen 等）
 * 共用这一份，保证存储路径和压缩策略一致。
 */
object AvatarImage {

    const val FILE_NAME = "avatar.jpg"

    /** 解码 + 压缩 + 落盘。失败（图片损坏 / Uri 失效 / 磁盘问题）返回 false。 */
    suspend fun saveFromUri(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val sample = decodeScaled(context, uri, 512)
                ?: throw IllegalArgumentException("图片解码失败")
            context.openFileOutput(FILE_NAME, Context.MODE_PRIVATE).use { out ->
                sample.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
        }.onFailure { android.util.Log.e("AvatarImage", "保存头像失败: $uri", it) }.isSuccess
    }

    /** 先探测尺寸再等比缩放，避免大图直接占满内存。 */
    private fun decodeScaled(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // 注意：inJustDecodeBounds=true 时 decodeStream 按约定返回 null（只填 bounds），
        // 且 use{} 会把 lambda 结果透传出去，所以必须先接住流再解码；
        // 若把 decodeStream 的 null 串进 elvis，所有图片都会被误判成「解码失败」
        val boundsStream = resolver.openInputStream(uri) ?: return null
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSide / 2 && bounds.outHeight / (sample * 2) >= maxSide / 2) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null
        val w = decoded.width
        val h = decoded.height
        val scale = maxSide.toFloat() / maxOf(w, h)
        return if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), true)
        } else {
            decoded
        }
    }
}
