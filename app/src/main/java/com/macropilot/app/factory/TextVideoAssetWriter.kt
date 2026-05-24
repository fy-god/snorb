package com.macropilot.app.factory

import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.util.Locale

class TextVideoAssetWriter(private val context: Context) {
    fun createTextVideo(
        text: String,
        durationMs: Long = 3_000L,
        displayName: String = "macropilot_text_video_${System.currentTimeMillis()}.mp4"
    ): TextVideoAssetResult {
        val safeText = text.ifBlank { "\u6211\u662fAI" }.take(60)
        val name = displayName.ensureMp4Name()
        val tmp = File(context.cacheDir, "macropilot_video_${System.currentTimeMillis()}.mp4")
        return try {
            encodeMp4(tmp, safeText, durationMs.coerceIn(1_000L, 10_000L))
            val uri = insertIntoMediaStore(tmp, name)
            TextVideoAssetResult(
                success = true,
                uri = uri.toString(),
                path = tmp.absolutePath,
                displayName = name,
                message = "Created text video asset: $name"
            )
        } catch (error: Throwable) {
            TextVideoAssetResult(
                success = false,
                uri = "",
                path = tmp.absolutePath,
                displayName = name,
                message = error.message ?: error.javaClass.simpleName
            )
        } finally {
            runCatching { tmp.delete() }
        }
    }

    private fun encodeMp4(file: File, text: String, durationMs: Long) {
        val width = 720
        val height = 1280
        val frameRate = 24
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(mime)
        var muxer: MediaMuxer? = null
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = encoder.createInputSurface()
            encoder.start()
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val bufferInfo = MediaCodec.BufferInfo()
            var trackIndex = -1
            var muxerStarted = false

            fun drain(endOfStream: Boolean) {
                if (endOfStream) encoder.signalEndOfInputStream()
                while (true) {
                    val outIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
                    when {
                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream) return
                        }
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (muxerStarted) throw IllegalStateException("Encoder format changed twice")
                            trackIndex = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        outIndex >= 0 -> {
                            val encodedData = encoder.getOutputBuffer(outIndex)
                                ?: throw IllegalStateException("Encoder output buffer $outIndex was null")
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                bufferInfo.size = 0
                            }
                            if (bufferInfo.size != 0) {
                                if (!muxerStarted) throw IllegalStateException("Muxer has not started")
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                            }
                            encoder.releaseOutputBuffer(outIndex, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                        }
                    }
                }
            }

            val frames = ((durationMs / 1000.0) * frameRate).toInt().coerceAtLeast(frameRate)
            val frameDelayMs = (1000L / frameRate).coerceAtLeast(16L)
            repeat(frames) { index ->
                val canvas = surface.lockCanvas(null)
                try {
                    drawFrame(canvas, text, index, frames)
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
                drain(false)
                Thread.sleep(frameDelayMs)
            }
            drain(true)
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private fun drawFrame(canvas: Canvas, text: String, index: Int, total: Int) {
        canvas.drawColor(Color.rgb(18, 18, 22))
        val progress = index.toFloat() / total.toFloat().coerceAtLeast(1f)
        val accent = Color.rgb(
            80 + (80 * progress).toInt().coerceIn(0, 80),
            180,
            255 - (50 * progress).toInt().coerceIn(0, 50)
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            strokeWidth = 10f
            style = Paint.Style.STROKE
        }
        canvas.drawRoundRect(54f, 120f, canvas.width - 54f, canvas.height - 120f, 42f, 42f, paint)
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = 92f
            isFakeBoldText = true
        }
        drawCenteredMultiline(canvas, text, titlePaint, canvas.height / 2f - 80f)
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(190, 198, 210)
            textAlign = Paint.Align.CENTER
            textSize = 34f
        }
        canvas.drawText("MacroPilot", canvas.width / 2f, canvas.height / 2f + 170f, subPaint)
    }

    private fun drawCenteredMultiline(canvas: Canvas, text: String, paint: Paint, centerY: Float) {
        val lines = if (text.length <= 8) {
            listOf(text)
        } else {
            text.chunked(8)
        }.take(4)
        val bounds = Rect()
        paint.getTextBounds("我", 0, 1, bounds)
        val lineHeight = bounds.height() * 1.45f
        val startY = centerY - (lineHeight * (lines.size - 1) / 2f)
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, canvas.width / 2f, startY + index * lineHeight, paint)
        }
    }

    private fun insertIntoMediaStore(file: File, displayName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MacroPilot")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert returned null")
        resolver.openOutputStream(uri, "w")?.use { output ->
            FileInputStream(file).use { input -> input.copyTo(output) }
        } ?: throw IllegalStateException("Could not open MediaStore output stream")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        }
        return uri
    }
}

data class TextVideoAssetResult(
    val success: Boolean,
    val uri: String,
    val path: String,
    val displayName: String,
    val message: String
)

private fun String.ensureMp4Name(): String {
    return if (lowercase(Locale.US).endsWith(".mp4")) this else "$this.mp4"
}
