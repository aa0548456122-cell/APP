package il.co.shoppingoffline

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.io.File

/**
 * Runtime contract check for the experimental TrOCR export.
 *
 * This intentionally does not guess image preprocessing or the meaning of
 * the "cols" input. A successful probe only confirms that the downloaded
 * model and charset can be opened and that the exported tensor contract is
 * the one documented by the model publisher.
 */
internal object TrocrModelProbe {
    data class Report(
        val inputs: Map<String, String>,
        val outputs: Map<String, String>,
        val classCount: Int
    )

    fun inspect(modelDir: File): Report {
        val model = File(modelDir, "line_large_int8.onnx")
        val charsetFile = File(modelDir, "charset.json")
        require(model.isFile && model.length() > 0L) { "קובץ TrOCR חסר: line_large_int8.onnx" }
        require(charsetFile.isFile && charsetFile.length() > 0L) { "קובץ TrOCR חסר: charset.json" }

        val charset = JSONObject(charsetFile.readText()).getJSONArray("chars")
        require(charset.length() == 198) {
            "מספר המחלקות במילון TrOCR אינו 198: ${charset.length()}"
        }
        require(charset.getString(0).isEmpty()) {
            "המחלקה הראשונה במילון TrOCR אמורה להיות CTC blank"
        }

        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
        val session = try {
            env.createSession(model.absolutePath, options)
        } finally {
            options.close()
        }
        try {
            val inputs = session.inputInfo.mapValues { (_, node) ->
                node.info.toString()
            }
            val outputs = session.outputInfo.mapValues { (_, node) ->
                node.info.toString()
            }
            require(inputs.keys.containsAll(listOf("pixel_values", "cols"))) {
                "קלטי TrOCR לא תואמים למפרט: ${inputs.keys}"
            }
            require(outputs.containsKey("log_probs")) {
                "פלט TrOCR log_probs לא נמצא: ${outputs.keys}"
            }
            return Report(inputs, outputs, charset.length())
        } finally {
            session.close()
        }
    }
}
