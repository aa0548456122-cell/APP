package il.co.shoppingoffline

import android.graphics.Bitmap
import android.graphics.Color
import ai.onnxruntime.*
import org.json.JSONArray
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Offline Mishkefet-v1 inference. Applies the model's contrast flattening,
 * 64px line normalization and three-scale test-time augmentation.
 */
internal class MishkefetOcr(private val dir: File) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val chars: List<String>
    private val classes: Int
    private val dim: Int
    private val weights: FloatArray
    private val bias: FloatArray
    private val decoder: HebrewLanguageDecoder

    init {
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
        session = env.createSession(File(dir, "backbone.fp16.onnx").absolutePath, opts)
        opts.close()
        val charset = JSONArray(File(dir, "charset.json").readText())
        decoder = HebrewLanguageDecoder(dir, charset)
        chars = (0 until charset.length()).map { charset.getString(it) }
        val bytes = ByteBuffer.wrap(File(dir, "head.bin").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        require(String(bytes.array(), 0, 4, Charsets.US_ASCII) == "MKH1") { "קובץ מודל הראש פגום" }
        classes = bytes.getInt(4); dim = bytes.getInt(8)
        require(classes == chars.size && dim == 512 && bytes.capacity() >= 12 + 4 * (classes * dim + classes))
        weights = FloatArray(classes * dim); bias = FloatArray(classes)
        var p = 12
        for (i in weights.indices) { weights[i] = bytes.getFloat(p); p += 4 }
        for (i in bias.indices) { bias[i] = bytes.getFloat(p); p += 4 }
    }

    fun readPage(bitmap: Bitmap): List<String> {
        val lines = segment(bitmap)
        return try { lines.mapNotNull { try { readLine(it).takeIf(String::isNotBlank) } catch (_: Exception) { null } } }
        finally { lines.forEach { if (it !== bitmap && !it.isRecycled) it.recycle() } }
    }

    // Horizontal projection with a modest gap merge; keeps full line width.
    private fun segment(b: Bitmap): List<Bitmap> {
        val w = b.width; val h = b.height
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        val active = BooleanArray(h)
        val step = max(1, w / 1400)
        for (y in 0 until h) {
            var ink = 0; var count = 0; var x = 0
            while (x < w) {
                val c = px[y * w + x]
                val g = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
                if (g < 190) ink++
                count++; x += step
            }
            active[y] = count > 0 && ink * 100 / count >= 1
        }
        val bands = mutableListOf<Pair<Int, Int>>()
        var start = -1
        for (y in 0 until h) {
            if (active[y] && start < 0) start = y
            if ((!active[y] || y == h - 1) && start >= 0) {
                val end = if (active[y] && y == h - 1) y + 1 else y
                if (end - start >= max(8, h / 300)) bands.add(start to end)
                start = -1
            }
        }
        if (bands.isEmpty()) return listOf(Bitmap.createBitmap(b))
        val merged = mutableListOf<Pair<Int, Int>>()
        for (band in bands) {
            val prev = merged.lastOrNull()
            if (prev != null && band.first - prev.second < max(4, h / 140))
                merged[merged.lastIndex] = prev.first to band.second
            else merged.add(band)
        }
        return merged.map { (top, bottom) ->
            val y = max(0, top - 8); val end = min(h, bottom + 8)
            Bitmap.createBitmap(b, 0, y, w, end - y)
        }
    }

    private fun gray(bitmap: Bitmap): Pair<Int, IntArray> {
        val w = bitmap.width; val h = bitmap.height
        val colors = IntArray(w * h); bitmap.getPixels(colors, 0, w, 0, 0, w, h)
        return w to IntArray(colors.size) { i ->
            val c = colors[i]
            (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
        }
    }

    // Flatten uneven lighting, then stretch the 2nd–98th percentile.
    private fun normalizeGray(w: Int, h: Int, src: IntArray): IntArray {
        val radius = max(2, h / 8)
        val horizontal = FloatArray(src.size)
        val kernelRadius = max(1, radius / 2)
        for (y in 0 until h) {
            var sum = 0
            for (x in -kernelRadius..kernelRadius) sum += src[y * w + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                horizontal[y * w + x] = sum.toFloat() / (kernelRadius * 2 + 1)
                val remove = (x - kernelRadius).coerceIn(0, w - 1)
                val add = (x + kernelRadius + 1).coerceIn(0, w - 1)
                sum += src[y * w + add] - src[y * w + remove]
            }
        }
        val blur = FloatArray(src.size)
        for (x in 0 until w) {
            var sum = 0f
            for (y in -kernelRadius..kernelRadius) sum += horizontal[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                blur[y * w + x] = sum / (kernelRadius * 2 + 1)
                val remove = (y - kernelRadius).coerceIn(0, h - 1)
                val add = (y + kernelRadius + 1).coerceIn(0, h - 1)
                sum += horizontal[add * w + x] - horizontal[remove * w + x]
            }
        }
        val flat = FloatArray(src.size) { i -> src[i] / max(1f, blur[i]) }
        val sorted = flat.clone(); sorted.sort()
        val lo = sorted[(sorted.size * 0.02f).toInt().coerceIn(0, sorted.lastIndex)]
        val hi = sorted[(sorted.size * 0.98f).toInt().coerceIn(0, sorted.lastIndex)]
        if (hi - lo < 0.00001f) return IntArray(src.size) { 255 }
        return IntArray(src.size) { i -> (((flat[i] - lo) / (hi - lo)) * 255).toInt().coerceIn(0, 255) }
    }

    private fun readLine(line: Bitmap): String {
        val (sourceW, sourceGray) = gray(line)
        val sourceH = line.height
        val normalized = normalizeGray(sourceW, sourceH, sourceGray)
        var bestText = ""
        var bestConfidence = Float.NEGATIVE_INFINITY
        for (scale in floatArrayOf(1f, 0.82f, 1.22f)) {
            val baseW = max(16, min(2560, (sourceW * 64.0 / sourceH).roundToInt()))
            val width = if (scale == 1f) baseW else max(8, (baseW * scale).toInt())
            val inputBitmap = Bitmap.createBitmap(sourceW, sourceH, Bitmap.Config.ARGB_8888)
            val colors = IntArray(normalized.size) { i -> Color.rgb(normalized[i], normalized[i], normalized[i]) }
            inputBitmap.setPixels(colors, 0, sourceW, 0, 0, sourceW, sourceH)
            val scaled = Bitmap.createScaledBitmap(inputBitmap, width, 64, true)
            inputBitmap.recycle()
            val pixels = IntArray(width * 64); scaled.getPixels(pixels, 0, width, 0, 0, width, 64); scaled.recycle()
            val input = FloatArray(pixels.size) { i -> 1f - Color.red(pixels[i]) / 255f }
            val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 1, 64, width.toLong()))
            val result = session.run(mapOf(session.inputNames.first() to tensor))
            try {
                val batch = (result[0].value as Array<*>)[0] as Array<*>
                val logits = Array(batch.size) { t ->
                    val f = batch[t] as FloatArray
                    FloatArray(classes) { k ->
                        var score = bias[k]
                        val offset = k * dim
                        for (d in 0 until dim) score += f[d] * weights[offset + d]
                        score
                    }
                }
                // CTC prefix beam search with Hebrew character 6-gram and word unigram LMs.
                val text = decoder.decode(logits)
                var confidenceSum = 0.0
                var confidenceCount = 0
                for (row in logits) {
                    val maxScore = row.maxOrNull() ?: continue
                    var sum = 0.0
                    for (v in row) sum += kotlin.math.exp((v - maxScore).toDouble())
                    if (sum > 0.0) { confidenceSum += 1.0 / sum; confidenceCount++ }
                }
                val confidence = if (confidenceCount == 0) Float.NEGATIVE_INFINITY else (confidenceSum / confidenceCount).toFloat()
                if (confidence > bestConfidence) {
                    bestConfidence = confidence
                    bestText = text
                }
            } finally { result.close(); tensor.close() }
        }
        return bestText
    }

    override fun close() { session.close() }
}
