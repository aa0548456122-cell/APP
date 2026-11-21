package il.co.shoppingoffline

/**
 * Greedy CTC decoder for TrOCR's [batch, time, classes] log-probability output.
 *
 * CTC rule: take the most likely class at each time step, collapse adjacent
 * repeats, then remove blank class 0. This is a baseline decoder; the Hebrew
 * 6-gram language-model beam search is a later accuracy improvement.
 */
internal object TrocrCtcDecoder {
    fun decode(
        logProbabilities: FloatArray,
        timeSteps: Int,
        classCount: Int,
        charset: List<String>,
        validTimeSteps: Int = timeSteps
    ): String {
        require(timeSteps > 0) { "timeSteps must be positive" }
        require(classCount > 1) { "classCount must include blank and characters" }
        require(charset.size == classCount) {
            "Charset size ${charset.size} does not match model classes $classCount"
        }
        require(logProbabilities.size == timeSteps * classCount) {
            "Output tensor size does not match [timeSteps, classCount]"
        }
        require(validTimeSteps in 1..timeSteps) { "Invalid validTimeSteps: $validTimeSteps" }

        val out = StringBuilder()
        var previous = -1
        for (t in 0 until validTimeSteps) {
            val offset = t * classCount
            var best = 0
            var bestValue = logProbabilities[offset]
            for (c in 1 until classCount) {
                val value = logProbabilities[offset + c]
                if (value > bestValue) {
                    bestValue = value
                    best = c
                }
            }
            if (best != 0 && best != previous) out.append(charset[best])
            previous = best
        }
        return out.toString()
    }
}
