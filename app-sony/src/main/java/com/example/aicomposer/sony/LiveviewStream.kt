package com.example.aicomposer.sony

import com.example.aicomposer.i18n.tr
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Parses the Sony liveview stream. Each packet:
 *   Common header, 8 bytes: [0]=0xFF, [1]=type (0x01 image / 0x02 frame info), [2-3]=sequence, [4-7]=timestamp
 *   Payload header, 128 bytes: [0-3]=0x24 0x35 0x68 0x79, [4-6]=data size (3-byte big endian), [7]=padding size
 *   Data + padding
 */
class LiveviewStream(client: OkHttpClient) {
    private val streamClient = client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    fun frames(url: String): Flow<Bitmap> = flow {
        val resp = streamClient.newCall(Request.Builder().url(url).build()).execute()
        resp.use {
            val body = it.body ?: throw IOException(tr("取景流为空", "ライブビューのストリームが空です", "Liveview stream is empty"))
            val input = DataInputStream(BufferedInputStream(body.byteStream(), 64 * 1024))
            val common = ByteArray(8)
            val header = ByteArray(128)
            val scratch = ByteArray(4096)
            while (currentCoroutineContext().isActive) {
                input.readFully(common)
                if (common[0] != 0xFF.toByte()) throw IOException(tr("取景流格式错误", "ライブビューの形式が不正です", "Invalid liveview stream format"))
                val type = common[1].toInt() and 0xFF
                input.readFully(header)
                if (header[0] != 0x24.toByte() || header[1] != 0x35.toByte() ||
                    header[2] != 0x68.toByte() || header[3] != 0x79.toByte()
                ) throw IOException(tr("取景流载荷头错误", "ライブビューのペイロードヘッダーが不正です", "Invalid liveview payload header"))
                val size = ((header[4].toInt() and 0xFF) shl 16) or
                    ((header[5].toInt() and 0xFF) shl 8) or
                    (header[6].toInt() and 0xFF)
                val padding = header[7].toInt() and 0xFF
                if (type == 0x01) {
                    val jpeg = ByteArray(size)
                    input.readFully(jpeg)
                    skipFully(input, padding, scratch)
                    BitmapFactory.decodeByteArray(jpeg, 0, size)?.let { bmp -> emit(bmp) }
                } else {
                    skipFully(input, size + padding, scratch)
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun skipFully(input: DataInputStream, n: Int, scratch: ByteArray) {
        var left = n
        while (left > 0) {
            val k = minOf(left, scratch.size)
            input.readFully(scratch, 0, k)
            left -= k
        }
    }
}
