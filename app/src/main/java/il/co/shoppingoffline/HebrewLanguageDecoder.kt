package il.co.shoppingoffline

import org.json.JSONArray
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/** Mishkefet MKL1/MKW1 language models and CTC prefix beam decoder. */
internal class HebrewLanguageDecoder(dir: File, charsetJson: JSONArray) {
    private val order: Int
    private val logBackoff: Double
    private val logFloor: Double
    private val charScale: Double
    private val counts: IntArray
    private val children: Array<IntArray>
    private val q: Array<IntArray>
    private val symbols: Array<IntArray>
    private val wordCount: Int
    private val oov: Double
    private val discount: Double
    private val wordScale: Double
    private val wordOffsets: IntArray
    private val wordQ: IntArray
    private val wordBlob: ByteArray
    private val prefixes: Set<Int>
    private val cache = HashMap<String, Double>()
    private val wordCache = HashMap<String, Double>()
    private val chars: List<String> = (0 until charsetJson.length()).map { charsetJson.getString(it) }
    private val space = 38

    init {
        val cb = ByteBuffer.wrap(File(dir, "charlm.bin").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        require(String(cb.array(), 0, 4, Charsets.US_ASCII) == "MKL1") { "charlm.bin header" }
        order = cb.getInt(4); logBackoff = cb.getFloat(8).toDouble(); logFloor = cb.getFloat(12).toDouble(); charScale = cb.getFloat(16).toDouble()
        counts = IntArray(order) { cb.getInt(24 + it * 4) }
        var off = 24 + order * 4
        children = Array(order - 1) { k -> IntArray(counts[k] + 1) { i -> cb.getInt(off + i * 4) }.also { off += (counts[k] + 1) * 4 } }
        q = Array(order) { k -> IntArray(counts[k]) { i -> cb.getShort(off + i * 2).toInt() and 65535 }.also { off += counts[k] * 2 } }
        symbols = Array(order) { k -> IntArray(counts[k]) { i -> cb.get(off + i).toInt() and 255 }.also { off += counts[k] } }
        require(off <= cb.capacity()) { "charlm.bin truncated" }
        val wb = ByteBuffer.wrap(File(dir, "words.bin").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        require(String(wb.array(), 0, 4, Charsets.US_ASCII) == "MKW1") { "words.bin header" }
        wordCount = wb.getInt(4); oov = wb.getFloat(8).toDouble(); discount = wb.getFloat(12).toDouble(); wordScale = wb.getFloat(16).toDouble()
        off = 24
        wordOffsets = IntArray(wordCount + 1) { wb.getInt(off + it * 4) }; off += (wordCount + 1) * 4
        wordQ = IntArray(wordCount) { wb.getShort(off + it * 2).toInt() and 65535 }; off += wordCount * 2
        wordBlob = ByteArray(wb.capacity() - off) { wb.get(off + it) }
        val p = mutableSetOf<Int>()
        for (ch in "בהוכלמש") chars.indexOf(ch.toString()).takeIf { it >= 0 }?.let { p.add(it) }
        prefixes = p
    }

    private fun charFind(context: IntArray, from: Int, to: Int, c: Int): Int {
        val len = to - from + 1; var lo = 0; var hi = counts[0]; var node = -1
        for (depth in 0 until len) {
            val sym = if (depth < len - 1) context[from + depth] else c
            if (depth > 0) { lo = children[depth - 1][node]; hi = children[depth - 1][node + 1] }
            val a = symbols[depth]; var l = lo; var r = hi
            while (l < r) { val m = (l + r) ushr 1; if (a[m] < sym) l = m + 1 else r = m }
            if (l >= hi || a[l] != sym) return -1
            node = l
        }
        return q[len - 1][node]
    }

    private fun charLogProb(ctx: IntArray, c: Int): Double {
        val start = max(0, ctx.size - (order - 1))
        val key = buildString { append(c.toChar()); for (i in start until ctx.size) append(ctx[i].toChar()) }
        cache[key]?.let { return it }
        var penalty = 0.0; var result = Double.NaN
        for (length in ctx.size - start downTo 0) {
            val v = charFind(ctx, ctx.size - length, ctx.size, c)
            if (v >= 0 && v != 65535) { result = penalty - v / charScale; break }
            penalty += logBackoff
        }
        if (result.isNaN()) result = penalty + logFloor
        if (cache.size > 200000) cache.clear(); cache[key] = result
        return result
    }

    private fun wordLogProb(word: IntArray): Double {
        if (word.isEmpty()) return 0.0
        fun find(from: Int): Double? {
            var lo = 0; var hi = wordCount
            fun cmp(i: Int): Int {
                val a = wordOffsets[i]; val la = wordOffsets[i + 1] - a; val lb = word.size - from; val m = minOf(la, lb)
                for (k in 0 until m) { val d = (wordBlob[a + k].toInt() and 255) - word[from + k]; if (d != 0) return d }
                return la - lb
            }
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (cmp(mid) < 0) lo = mid + 1 else hi = mid }
            return if (lo < wordCount && cmp(lo) == 0) -wordQ[lo] / wordScale else null
        }
        find(0)?.let { return it }
        if (word.size > 2 && prefixes.contains(word[0])) find(1)?.let { return it + discount }
        return oov
    }

    private fun wordBonus(word: IntArray): Double {
        val key = word.joinToString(",")
        wordCache[key]?.let { return it }
        val b = max(0.0, wordLogProb(word) - oov)
        if (wordCache.size > 100000) wordCache.clear(); wordCache[key] = b
        return b
    }

    private data class Beam(var blank: Double = Double.NEGATIVE_INFINITY, var nonblank: Double = Double.NEGATIVE_INFINITY)
    private fun lse(a: Double, b: Double): Double {
        if (a == Double.NEGATIVE_INFINITY) return b
        if (b == Double.NEGATIVE_INFINITY) return a
        val m = max(a, b); return m + ln(exp(a - m) + exp(b - m))
    }
    private fun codes(s: String) = IntArray(s.length) { s[it].code }
    private fun lastWord(s: String): String = s.substringAfterLast(space.toChar())

    fun decode(logits: Array<FloatArray>): String {
        var beams = linkedMapOf("" to Beam(0.0, Double.NEGATIVE_INFINITY))
        for (row in logits) {
            val maxLogit = row.maxOrNull() ?: continue
            var sum = 0.0; for (v in row) sum += exp((v - maxLogit).toDouble())
            val logZ = maxLogit + ln(sum)
            val lp = DoubleArray(row.size) { row[it] - logZ }
            val candidates = lp.indices.sortedWith(compareByDescending<Int> { lp[it] }.thenBy { it }).take(8)
            val next = linkedMapOf<String, Beam>()
            fun entry(key: String) = next.getOrPut(key) { Beam() }
            for ((prefix, beam) in beams) {
                val total = lse(beam.blank, beam.nonblank)
                for (c in candidates) {
                    val p = lp[c]
                    if (c == 0) { val e = entry(prefix); e.blank = lse(e.blank, total + p); continue }
                    val repeated = prefix.isNotEmpty() && prefix.last().code == c
                    val src: Double
                    if (repeated) { entry(prefix).also { it.nonblank = lse(it.nonblank, beam.nonblank + p) }; src = beam.blank } else src = total
                    if (src == Double.NEGATIVE_INFINITY) continue
                    val ctx = codes(prefix)
                    var bonus = 0.4 * charLogProb(ctx, c) + 0.6
                    if (c == space) { val w = codes(lastWord(prefix)); if (w.isNotEmpty()) bonus += 0.2 * wordBonus(w) }
                    val e = entry(prefix + c.toChar()); e.nonblank = lse(e.nonblank, src + p + bonus)
                }
            }
            beams = next.entries.sortedByDescending { lse(it.value.blank, it.value.nonblank) }.take(12).associateTo(linkedMapOf()) { it.key to it.value }
        }
        val best = beams.entries.maxByOrNull { (s, b) -> lse(b.blank, b.nonblank) + if (s.isNotEmpty()) 0.2 * wordBonus(codes(lastWord(s))) else 0.0 }?.key.orEmpty()
        return buildString { for (c in best) if (c.code in chars.indices) append(chars[c.code]) }.trim()
    }
}