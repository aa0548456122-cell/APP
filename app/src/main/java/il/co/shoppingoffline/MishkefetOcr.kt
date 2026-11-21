package il.co.shoppingoffline

import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONArray
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Small Android inference adapter for Mishkefet-v1.
 * It performs page-line projection, the published 64px line resize, ONNX backbone,
 * MKH1 CTC head and greedy CTC decoding. Everything runs locally on the device.
 */
internal class MishkefetOcr(private val assetDir: File) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val charset: List<String>
    private val weights: FloatArray
    private val bias: FloatArray
    private val classes: Int
    private val dim: Int

    init {
        val options = OrtSession.SessionOptions()
        options.setIntraOpNumThreads(2)
        session = env.createSession(File(assetDir, "backbone.fp16.onnx").absolutePath, options)
        options.close()

        charset = JSONArray(File(assetDir, "charset.json").readText(Charsets.UTF_8)).let { a ->
            (0 until a.length()).map { a.getString(it) }
        }
        val bytes = File(assetDir, "head.bin").readBytes()
        require(bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "MKH1") {
            "קובץ ראש המודל אינו תקין"
        }
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        classes = header.getInt(4)
        dim = header.getInt(8)
        require(classes == charset.size && dim == 512 && bytes.size >= 12 + 4 * (classes * dim + classes)) {
            "מבנה מודל כתב היד אינו תואם"
        }
        weights = FloatArray(classes * dim)
        bias = FloatArray(classes)
        var offset = 12
        for (i in weights.indices) {
            weights[i] = header.getFloat(offset)
            offset += 4
        }
        for (i in bias.indices) {
            bias[i] = header.getFloat(offset)
            offset += 4
        }
    }

    fun readPage(bitmap: Bitmap): List<String> {
        val crops = findLines(bitmap)
        return crops.mapNotNull { crop ->
            try { readLine(crop) } catch (_: Exception) { null }
        }.filter { it.isNotBlank() }
    }

    private fun findLines(bitmap: Bitmap): List<Bitmap> {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val rowInk = IntArray(h)
        val step = max(1, w / 1400)
        for (y in 0 until h) {
            var ink = 0
            var count = 0
            var x = 0
            while (x < w) {
                val c = pixels[y * w + x]
                val gray = (android.graphics.Color.red(c) * 299 +
                    android.graphics.Color.green(c) * 587 +
                    android.graphics.Color.blue(c) * 114) / 1000
                if (gray < 170) ink++
                count++
                x += step
            }
            rowInk[y] = if (count == 0) 0 else ink * 100 / count
        }
        val active = BooleanArray(h) { rowInk[it] >= 1 }
        val bands = mutableListOf<Pair<Int, Int>>()
        var start = -1
        for (y in 0 until h) {
            if (active[y] && start < 0) start = y
            if ((!active[y] || y == h - 1) && start >= 0) {
                val end = if (active[y] && y == h - 1) y + 1 else y
                if (end - start >= max(8, h / 250)) bands.add(start to end)
                start = -1
            }
        }
        if (bands.isEmpty()) return listOf(Bitmap.createBitmap(bitmap))
        // Merge bands separated by only a small gap; dots and detached marks belong to their line.
        val merged = mutableListOf<Pair<Int, Int>>()
        for (band in bands) {
            val last = merged.lastOrNull()
            if (last != null && band.first - last.second < max(5, h / 100)) {
                merged[merged.lastIndex] = last.first to band.second
            } else merged.add(band)
        }
        return merged.map { (top, bottom) ->
            Bitmap.createBitmap(bitmap, 0, max(0, top - 5), w, min(h, bottom + 5) - max(0, top - 5))
        }
    }

    private fun readLine(bitmap: Bitmap): String {
        val srcW = bitmap.width
        val srcH = bitmap.height
        val outW = (srcW.toDouble() * 64.0 / max(1, srcH)).roundToInt().coerceIn(16, 2560)
        val gray = Bitmap.createScaledBitmap(bitmap, outW, 64, true)
        val px = IntArray(outW * 64)
        gray.getPixels(px, 0, outW, 0, 0, outW, 64)
        if (gray !== bitmap) gray.recycle()

        // Mishkefet input: ink near 1, paper near 0.
        val input = FloatArray(64 * outW)
        for (i in px.indices) {
            val c = px[i]
            val g = (android.graphics.Color.red(c) * 19595 +
                android.graphics.Color.green(c) * 38470 +
                android.graphics.Color.blue(c) * 7471 + 32768) shr 16
            input[i] = 1f - g.coerceIn(0, 255) / 255f
        }
        val inputName = session.inputNames.iterator().next()
        val shape = longArrayOf(1, 1, 64, outW.toLong())
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape)
        val result = session.run(mapOf(inputName to tensor))
        try {
            val output = result[0].value as Array<*>
            val batch = output[0] as Array<*>
            val frames = batch.size
            val features = FloatArray(frames * dim)
            for (t in 0 until frames) {
                val row = batch[t] as FloatArray
                System.arraycopy(row, 0, features, t * dim, dim)
            }
            val decoded = StringBuilder()
            var previous = -1
            for (t in 0 until frames) {
                var best = 0
                var bestScore = Float.NEGATIVE_INFINITY
                for (k in 0 until classes) {
                    var score = bias[k]
                    val base = k * dim
                    val frame = t * dim
                    for (d in 0 until dim) score += features[frame + d] * weights[base + d]
                    if (score > bestScore) { bestScore = score; best = k }
                }
                if (best != previous && best != 0) decoded.append(charset[best])
                previous = best
            }
            return decoded.toString().trim()
        } finally {
            result.close()
            tensor.close()
        }
    }

    override fun close() {
        session.close()
    }
}
