package il.co.shoppingoffline

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.graphics.BitmapFactory
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class MainActivity : Activity() {
    private data class Item(val name: String, var bought: Boolean = false)
    private val items = mutableListOf<Item>()
    private lateinit var listBox: LinearLayout
    private lateinit var status: TextView
    private val prefs by lazy { getSharedPreferences("shopping", MODE_PRIVATE) }
    private val pickReceipt = 41

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadItems()
        buildUi()
        renderItems()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(24, 28, 24, 20)
            setBackgroundColor(0xFFF7F8FA.toInt())
        }
        root.addView(TextView(this).apply {
            text = "🛒 רשימת הקניות שלי"; textSize = 26f
            setTextColor(0xFF17324D.toInt()); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(TextView(this).apply {
            text = "מוסיפים מוצרים, סורקים קבלה ומגלים מה נשאר"
            textSize = 14f; gravity = Gravity.CENTER; setPadding(0, 8, 0, 20)
        })
        val input = EditText(this).apply { hint = "איזה מוצר צריך לקנות?"; textSize = 17f }
        root.addView(input, LinearLayout.LayoutParams(-1, -2))
        root.addView(Button(this).apply {
            text = "הוסף לרשימה"
            setOnClickListener {
                val value = input.text.toString().trim()
                if (value.isNotEmpty() && items.none { it.name.equals(value, true) }) {
                    items.add(Item(value)); saveItems(); renderItems(); input.text.clear()
                }
            }
        })
        root.addView(Button(this).apply {
            text = "📷 סריקת קבלה (עובד אופליין)"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
                startActivityForResult(Intent.createChooser(intent, "בחירת צילום קבלה"), pickReceipt)
            }
        })
        status = TextView(this).apply {
            text = "הנתונים נשמרים בטלפון"; textSize = 14f
            setTextColor(0xFF42637D.toInt()); setPadding(0, 10, 0, 10)
        }
        root.addView(status)
        val scroll = ScrollView(this)
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listBox)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(Button(this).apply {
            text = "נקה מוצרים שסומנו כנקנו"
            setOnClickListener { items.removeAll { it.bought }; saveItems(); renderItems() }
        })
        setContentView(root)
    }

    private fun renderItems() {
        listBox.removeAllViews()
        status.text = "נשארו לקנות: " + items.count { !it.bought } + " מתוך " + items.size
        if (items.isEmpty()) listBox.addView(TextView(this).apply {
            text = "הרשימה ריקה. הוסף את המוצר הראשון למעלה."
            textSize = 16f; setPadding(8, 30, 8, 8)
        })
        items.forEach { item ->
            listBox.addView(CheckBox(this).apply {
                text = item.name; textSize = 18f; isChecked = item.bought
                setPadding(8, 10, 8, 10)
                setOnCheckedChangeListener { _, checked ->
                    item.bought = checked; saveItems()
                    status.text = "נשארו לקנות: " + items.count { !it.bought } + " מתוך " + items.size
                }
            })
        }
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickReceipt || resultCode != Activity.RESULT_OK || data?.data == null) return
        status.text = "קורא את הקבלה במכשיר…"
        val uri = data.data!!
        Thread {
            try {
                val bitmap = contentResolver.openInputStream(uri).use {
                    BitmapFactory.decodeStream(it)
                } ?: error("לא ניתן לקרוא את התמונה")
                val tessDir = File(filesDir, "tesseract")
                val dataDir = File(tessDir, "tessdata")
                dataDir.mkdirs()
                listOf("heb", "eng").forEach { lang ->
                    val target = File(dataDir, lang + ".traineddata")
                    if (!target.exists()) assets.open("tessdata/" + lang + ".traineddata").use { src ->
                        FileOutputStream(target).use { dst -> src.copyTo(dst) }
                    }
                }
                val api = TessBaseAPI()
                if (!api.init(tessDir.absolutePath, "heb+eng")) error("אתחול מנוע הזיהוי נכשל")
                api.setImage(bitmap)
                val receiptText = api.getUTF8Text() ?: ""
                api.end()
                val found = items.filter { !it.bought && containsReceiptItem(receiptText, it.name) }
                runOnUiThread {
                    found.forEach { it.bought = true }
                    saveItems(); renderItems()
                    status.text = if (found.isEmpty()) "לא זוהו מוצרים מהרשימה בקבלה. אפשר לסמן ידנית."
                    else "נמצאו וסומנו: " + found.joinToString { it.name }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "שגיאה בסריקה: " + (e.localizedMessage ?: "נסה תמונה ברורה יותר") }
            }
        }.start()
    }

    private fun containsReceiptItem(text: String, item: String): Boolean {
        fun clean(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")
        val receipt = text.lines().map(::clean).filter { it.length >= 2 }
        val target = clean(item)
        if (target.length < 2) return false
        return receipt.any { line -> line.contains(target) || (target.contains(line) && line.length >= 4) }
    }

    private fun saveItems() {
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("name", it.name).put("bought", it.bought)) }
        prefs.edit().putString("items", array.toString()).apply()
    }

    private fun loadItems() {
        try {
            val array = JSONArray(prefs.getString("items", "[]"))
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                items.add(Item(obj.getString("name"), obj.optBoolean("bought")))
            }
        } catch (_: Exception) { items.clear() }
    }
}
