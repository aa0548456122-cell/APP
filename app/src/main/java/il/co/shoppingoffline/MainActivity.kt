package il.co.shoppingoffline

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.googlecode.tesseract.android.TessBaseAPI
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class MainActivity : Activity() {
    private data class Item(val name: String, var bought: Boolean = false)
    private data class Candidate(val text: String)

    private val items = mutableListOf<Item>()
    private lateinit var listBox: LinearLayout
    private lateinit var status: TextView
    private lateinit var remainingValue: TextView
    private lateinit var totalValue: TextView
    private lateinit var emptyState: TextView
    private val prefs by lazy { getSharedPreferences("shopping", MODE_PRIVATE) }
    private val requestGallery = 41
    private val requestCamera = 42
    private var pendingCameraFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF17324A.toInt()
        window.navigationBarColor = 0xFFF3F6FA.toInt()
        loadItems()
        buildUi()
        renderItems()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int = 18): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun actionButton(text: String, background: Int, foreground: Int): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(foreground)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            this.background = rounded(background, 16)
            isClickable = true
            isFocusable = true
            setPadding(dp(10), dp(12), dp(10), dp(12))
            minHeight = dp(54)
        }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            setFillViewport(true)
            clipToPadding = false
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(18), dp(18), dp(28))
            setBackgroundColor(0xFFF3F6FA.toInt())
        }

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF17324A.toInt(), 0xFF176B72.toInt())
            ).apply { cornerRadius = dp(26).toFloat() }
            setPadding(dp(22), dp(22), dp(22), dp(20))
        }
        hero.addView(label("🛒  קונים חכם", 27f, 0xFFFFFFFF.toInt(), true))
        hero.addView(label("רשימת קניות חכמה שעובדת גם בלי אינטרנט", 14f, 0xFFDCECEF.toInt()).apply {
            setPadding(0, dp(6), 0, dp(18))
        })
        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val remainingCard = statCard("נשאר לקנות", true)
        remainingValue = remainingCard.second
        stats.addView(remainingCard.first, LinearLayout.LayoutParams(0, dp(76), 1f).apply { marginEnd = dp(8) })
        val totalCard = statCard("ברשימה", true)
        totalValue = totalCard.second
        stats.addView(totalCard.first, LinearLayout.LayoutParams(0, dp(76), 1f).apply { marginStart = dp(8) })
        hero.addView(stats)
        page.addView(hero, LinearLayout.LayoutParams(-1, -2))

        val addCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xFFFFFFFF.toInt(), 22)
            setPadding(dp(17), dp(17), dp(17), dp(17))
        }
        addCard.addView(label("הוספת מוצר", 18f, 0xFF20394D.toInt(), true))
        addCard.addView(label("מה חסר בבית?", 13f, 0xFF718292.toInt()).apply {
            setPadding(0, dp(4), 0, dp(12))
        })
        val input = EditText(this).apply {
            hint = "לדוגמה: חלב, לחם, ביצים…"
            textSize = 16f
            setTextColor(0xFF20394D.toInt())
            setHintTextColor(0xFF9AA8B4.toInt())
            background = rounded(0xFFF2F5F8.toInt(), 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setSingleLine(true)
        }
        addCard.addView(input, LinearLayout.LayoutParams(-1, dp(52)))
        val add = actionButton("＋  הוסף לרשימה", 0xFF176B72.toInt(), 0xFFFFFFFF.toInt())
        add.setOnClickListener {
            val value = input.text.toString().trim()
            if (value.isNotEmpty()) {
                val existing = items.firstOrNull { normalize(it.name) == normalize(value) }
                if (existing == null) items.add(Item(value)) else existing.bought = false
                saveItems()
                renderItems()
                input.text.clear()
                Toast.makeText(this, "המוצר נוסף לרשימה", Toast.LENGTH_SHORT).show()
            }
        }
        val addParams = LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(10) }
        addCard.addView(add, addParams)
        page.addView(addCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        page.addView(label("סריקת קבלה", 19f, 0xFF20394D.toInt(), true), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(21)
        })
        page.addView(label("הצילום והזיהוי נשארים בטלפון — ללא העלאת תמונות לרשת", 12f, 0xFF718292.toInt()).apply {
            setPadding(0, dp(4), 0, dp(10))
        })
        val scanRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val camera = actionButton("📷  צילום קבלה", 0xFF176B72.toInt(), 0xFFFFFFFF.toInt())
        camera.setOnClickListener { openCamera() }
        val gallery = actionButton("▧  בחירה מהגלריה", 0xFFE1ECEE.toInt(), 0xFF17616A.toInt())
        gallery.setOnClickListener { openGallery() }
        scanRow.addView(camera, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginEnd = dp(5) })
        scanRow.addView(gallery, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginStart = dp(5) })
        page.addView(scanRow)

        status = label("מוכן לקניות. הרשימה נשמרת במכשיר.", 13f, 0xFF416172.toInt())
        status.background = rounded(0xFFE5EFF1.toInt(), 14)
        status.setPadding(dp(14), dp(13), dp(14), dp(13))
        page.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        val listHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        listHeader.addView(label("מה עוד צריך לקנות?", 19f, 0xFF20394D.toInt(), true), LinearLayout.LayoutParams(0, -2, 1f))
        val clearDone = actionButton("נקה שנקנו", 0xFFE8EDF2.toInt(), 0xFF526777.toInt())
        clearDone.textSize = 12f
        clearDone.minHeight = dp(38)
        clearDone.setPadding(dp(12), dp(8), dp(12), dp(8))
        clearDone.setOnClickListener {
            items.removeAll { it.bought }
            saveItems()
            renderItems()
        }
        listHeader.addView(clearDone)
        page.addView(listHeader, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22); bottomMargin = dp(10) })

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        emptyState = label("הרשימה שלך עדיין ריקה. הוסף מוצר או סרוק קבלה.", 14f, 0xFF82909C.toInt()).apply {
            gravity = Gravity.CENTER
            background = rounded(0xFFFFFFFF.toInt(), 18)
            setPadding(dp(18), dp(26), dp(18), dp(26))
        }
        page.addView(listBox, LinearLayout.LayoutParams(-1, -2))
        scroll.addView(page)
        setContentView(scroll)
    }

    private fun statCard(title: String, dark: Boolean): Pair<LinearLayout, TextView> {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(0x26FFFFFF, 16)
        }
        card.addView(label(title, 12f, 0xFFDCECEF.toInt()))
        val value = label("0", 23f, 0xFFFFFFFF.toInt(), true)
        card.addView(value)
        return Pair(card, value)
    }

    private fun renderItems() {
        listBox.removeAllViews()
        if (items.isEmpty()) {
            listBox.addView(emptyState)
        } else {
            items.forEach { item ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutDirection = View.LAYOUT_DIRECTION_RTL
                    background = rounded(0xFFFFFFFF.toInt(), 16)
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                }
                val check = CheckBox(this).apply {
                    isChecked = item.bought
                    buttonTintList = android.content.res.ColorStateList.valueOf(0xFF178078.toInt())
                    setOnCheckedChangeListener { _, checked ->
                        item.bought = checked
                        saveItems()
                        updateStats()
                        nameStyle(checked)
                    }
                }
                val name = label(item.name, 16f, if (item.bought) 0xFF93A0AA.toInt() else 0xFF20394D.toInt(), !item.bought)
                if (item.bought) name.paintFlags = name.paintFlags or 0x10
                row.addView(check)
                row.addView(name, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8); marginEnd = dp(6) })
                val remove = label("✕", 16f, 0xFF9AA7B2.toInt(), true).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    isClickable = true
                    setOnClickListener {
                        items.remove(item)
                        saveItems()
                        renderItems()
                    }
                }
                row.addView(remove)
                // Keep a reference to the label so checking an item can update its appearance.
                check.tag = name
                listBox.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
        }
        updateStats()
    }

    private fun nameStyle(checked: Boolean) {
        // The statistics are refreshed separately; item text is updated by the next render.
    }

    private fun updateStats() {
        remainingValue.text = items.count { !it.bought }.toString()
        totalValue.text = items.size.toString()
    }

    private fun openCamera() {
        try {
            val directory = File(cacheDir, "receipts")
            directory.mkdirs()
            val photo = File(directory, "receipt_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", photo)
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                clipData = ClipData.newRawUri("receipt photo", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            pendingCameraFile = photo
            startActivityForResult(intent, requestCamera)
        } catch (e: Exception) {
            status.text = "לא ניתן לפתוח מצלמה: ${e.localizedMessage ?: "בדוק שיש אפליקציית מצלמה"}"
        }
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(intent, requestGallery)
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) {
            if (requestCode == requestCamera) pendingCameraFile?.delete()
            return
        }
        when (requestCode) {
            requestGallery -> {
                val uri = data?.data
                if (uri != null) scanReceipt(uri) else status.text = "לא נבחרה תמונה."
            }
            requestCamera -> {
                val photo = pendingCameraFile
                if (photo != null && photo.exists() && photo.length() > 0L) {
                    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", photo)
                    scanReceipt(uri)
                } else {
                    status.text = "לא התקבלה תמונה מהמצלמה. נסה שוב."
                }
            }
        }
    }

    private fun scanReceipt(uri: Uri) {
        status.text = "מכין את התמונה ומנסה כמה שיטות זיהוי…"
        Thread {
            var original: Bitmap? = null
            var variants: List<Bitmap> = emptyList()
            var api: TessBaseAPI? = null
            try {
                original = decodeBitmap(uri)
                variants = prepareOcrVariants(original!!)

                val tessDir = File(filesDir, "tesseract")
                val dataDir = File(tessDir, "tessdata")
                dataDir.mkdirs()
                listOf("heb", "eng").forEach { lang ->
                    val target = File(dataDir, "$lang.traineddata")
                    if (!target.exists() || target.length() < 1_000_000L) {
                        assets.open("tessdata/$lang.traineddata").use { src ->
                            FileOutputStream(target).use { dst -> src.copyTo(dst) }
                        }
                    }
                    if (!target.exists() || target.length() < 1_000_000L) error("קובץ שפה חסר או פגום: $lang")
                }

                api = TessBaseAPI()
                if (!api!!.init(tessDir.absolutePath, "heb+eng", TessBaseAPI.OEM_LSTM_ONLY)) {
                    error("מנוע זיהוי הטקסט לא הצליח לטעון עברית ואנגלית")
                }
                api!!.setVariable("user_defined_dpi", "300")
                api!!.setVariable("preserve_interword_spaces", "1")

                var bestText = ""
                var bestScore = 0
                val modes = listOf(
                    TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT,
                    TessBaseAPI.PageSegMode.PSM_SINGLE_COLUMN,
                    TessBaseAPI.PageSegMode.PSM_AUTO,
                    TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
                )
                variants.forEach { bitmap ->
                    modes.forEach { mode ->
                        api!!.setPageSegMode(mode)
                        api!!.setImage(bitmap)
                        val candidate = api!!.getUTF8Text().orEmpty()
                        val score = candidate.count { it.isLetterOrDigit() }
                        if (score > bestScore) {
                            bestScore = score
                            bestText = candidate
                        }
                    }
                }

                val imageInfo = "\${original!!.width}x\${original!!.height}, \${variants.size} עיבודים"
                val candidates = extractCandidates(bestText)
                runOnUiThread {
                    status.text = if (candidates.isEmpty()) {
                        "לא הצלחנו לקרוא טקסט. תמונה: $imageInfo. נסה צילום ישר, קרוב ומואר."
                    } else {
                        "הסריקה זיהתה \${candidates.size} שורות אפשריות למוצרים."
                    }
                    showReceiptReview(candidates, bestText)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "שגיאת סריקה: \${e.localizedMessage ?: e.javaClass.simpleName}"
                }
            } finally {
                try { api?.recycle() } catch (_: Exception) {}
                variants.forEach { try { it.recycle() } catch (_: Exception) {} }
                try { original?.recycle() } catch (_: Exception) {}
            }
        }.start()
    }

    private fun decodeBitmap(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("קובץ התמונה אינו תקין")
        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > 3600) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: error("לא ניתן לפתוח את התמונה")
    }

    private fun prepareOcrVariants(source: Bitmap): List<Bitmap> {
        val longest = maxOf(source.width, source.height)
        val scale = if (longest > 3600) 3600f / longest.toFloat() else 1f
        val base = if (scale < 1f) {
            Bitmap.createScaledBitmap(source, (source.width * scale).toInt(), (source.height * scale).toInt(), true)
        } else Bitmap.createBitmap(source)

        val gray = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val grayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        Canvas(gray).drawBitmap(base, 0f, 0f, grayPaint)

        val binary = Bitmap.createBitmap(gray.width, gray.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(gray.width * gray.height)
        gray.getPixels(pixels, 0, gray.width, 0, 0, gray.width, gray.height)
        for (i in pixels.indices) {
            val color = pixels[i]
            val luminance = (android.graphics.Color.red(color) * 299 +
                android.graphics.Color.green(color) * 587 +
                android.graphics.Color.blue(color) * 114) / 1000
            pixels[i] = if (luminance < 175) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        binary.setPixels(pixels, 0, gray.width, 0, 0, gray.width, gray.height)
        base.recycle()
        return listOf(gray, binary)
    }

    private fun extractCandidates(text: String): List<Candidate> {
        val ignored = listOf(
            "סהכ", "סךהכל", "לתשלום", "מע״מ", "מעמ", "אשראי", "מזומן", "עודף",
            "תאריך", "חשבונית", "קבלה", "מספר", "קופה", "שעה", "עסקה", "אישור",
            "כרטיס", "סניף", "כתובת", "הנחה", "הנחות", "מחיר", "סכום", "מועדון",
            "נקודות", "שקלים", "total", "subtotal", "tax", "vat", "change",
            "cash", "credit", "receipt", "invoice", "date", "time", "discount",
            "price", "amount", "payment", "store", "tel", "phone"
        )
        val seen = HashSet<String>()
        return text.lines().map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { line ->
                val letters = line.count { it.isLetter() }
                val normalized = normalize(line)
                line.length in 3..100 && letters >= 3 && ignored.none { normalized.startsWith(normalize(it)) }
            }
            .filter { seen.add(normalize(it)) }
            .take(100)
            .map { Candidate(it) }
    }

    private fun showReceiptReview(found: List<Candidate>, rawText: String) {
        val candidates = if (found.isNotEmpty()) found else rawText.lines()
            .map { it.trim() }
            .filter { it.count(Char::isLetter) >= 3 }
            .distinctBy { normalize(it) }
            .take(100)
            .map { Candidate(it) }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        content.addView(label(
            "בדוק את הפריטים שזוהו. אפשר לערוך שם, להסיר סימון או לבחור רק חלק.",
            13f, 0xFF627584.toInt()
        ).apply { setPadding(dp(4), 0, dp(4), dp(10)) })

        val rowScroll = ScrollView(this)
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val editors = mutableListOf<Pair<CheckBox, EditText>>()
        candidates.forEachIndexed { index, candidate ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                background = rounded(0xFFF2F6F8.toInt(), 13)
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }
            val check = CheckBox(this).apply {
                isChecked = true
                buttonTintList = android.content.res.ColorStateList.valueOf(0xFF176B72.toInt())
            }
            val edit = EditText(this).apply {
                setText(candidate.text)
                textSize = 14f
                setTextColor(0xFF20394D.toInt())
                background = rounded(0xFFFFFFFF.toInt(), 10)
                setPadding(dp(10), dp(7), dp(10), dp(7))
                minHeight = dp(44)
                maxLines = 2
                hint = "שם המוצר"
            }
            row.addView(check)
            row.addView(edit, LinearLayout.LayoutParams(0, -2, 1f))
            rows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            editors.add(check to edit)
        }
        if (candidates.isEmpty()) {
            rows.addView(label("לא זוהו מילים בתמונה. נסה צילום קרוב, חד ומואר יותר.", 14f, 0xFF9B4F39.toInt()))
        }
        rowScroll.addView(rows)
        content.addView(rowScroll, LinearLayout.LayoutParams(-1, dp(300)))

        val rawToggle = Button(this).apply { text = "הצג את כל הטקסט שזוהה"; textSize = 12f }
        val rawView = label(rawText.ifBlank { "לא התקבל טקסט מהקבלה." }, 12f, 0xFF536575.toInt()).apply {
            visibility = View.GONE
            setTextIsSelectable(true)
            background = rounded(0xFFF5F7F9.toInt(), 10)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        rawToggle.setOnClickListener {
            rawView.visibility = if (rawView.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            rawToggle.text = if (rawView.visibility == View.VISIBLE) "הסתר את הטקסט שזוהה" else "הצג את כל הטקסט שזוהה"
        }
        content.addView(rawToggle)
        content.addView(rawView)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (candidates.isEmpty()) "תוצאות סריקת הקבלה" else "זוהו ${candidates.size} שורות בקבלה")
            .setView(content)
            .setNegativeButton("ביטול", null)
            .setPositiveButton("הוסף מסומנים לרשימה", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected = editors.filter { it.first.isChecked }
                    .map { it.second.text.toString().trim() }
                    .filter { it.isNotEmpty() }
                if (selected.isEmpty()) {
                    Toast.makeText(this, "לא נבחרו פריטים להוספה", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                var added = 0
                var matched = 0
                selected.forEach { line ->
                    val name = productNameFromReceipt(line)
                    val lineKey = normalize(name)
                    val existing = items.firstOrNull {
                        val existingKey = normalize(it.name)
                        existingKey.isNotEmpty() &&
                            (lineKey == existingKey || (existingKey.length >= 3 && lineKey.contains(existingKey)))
                    }
                    if (existing != null) {
                        existing.bought = true
                        matched++
                    } else {
                        items.add(Item(name, true))
                        added++
                    }
                }
                saveItems()
                renderItems()
                status.text = "עודכנה הרשימה: ${matched} מוצרים קיימים סומנו כנקנו, ונוספו ${added} מוצרים חדשים."
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun productNameFromReceipt(line: String): String {
        val withoutTrailingPrice = line
            .replace(Regex("""\s+\d+[.,]\d{2}\s*$"""), "")
            .replace(Regex("""\s+₪\s*\d+(?:[.,]\d{2})?\s*$"""), "")
            .trim()
        return withoutTrailingPrice.ifBlank { line.trim() }
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")

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
        } catch (_: Exception) {
            items.clear()
        }
    }
}
