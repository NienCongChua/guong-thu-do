package com.example.myapplication

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID

data class PhotoAsset(val id: String, val bitmap: Bitmap, val imageBytes: ByteArray)

object Photos {
    suspend fun load(context: Context, uri: Uri): PhotoAsset = withContext(Dispatchers.IO) {
        // ImageDecoder applies EXIF orientation. Decode into sRGB before lossless PNG encoding;
        // never mirror a garment reference (print and button placement must stay intact).
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val scale = minOf(1.0, 1536.0 / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1),
                (info.size.height * scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        }
        val bytes = ByteArrayOutputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Không mã hóa được ảnh." }
            it.toByteArray()
        }
        check(bytes.size <= 12 * 1024 * 1024) { "Ảnh quá lớn. Hãy chọn ảnh nhỏ hơn." }
        PhotoAsset(UUID.randomUUID().toString(), bitmap, bytes)
    }

    suspend fun save(context: Context, png: ByteArray): Unit = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "GuongThuDo_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GuongThuDo")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Không tạo được ảnh trong thư viện.")
        try {
            context.contentResolver.openOutputStream(uri)?.use { it.write(png) }
                ?: error("Không mở được tệp ảnh.")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw e
        }
    }
}
