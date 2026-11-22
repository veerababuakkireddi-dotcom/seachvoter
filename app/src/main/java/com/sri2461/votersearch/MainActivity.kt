package com.sri2461.votersearch

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.roundToInt

private val PRIMARY = Color.parseColor("#3949AB")
private val PRIMARY_DARK = Color.parseColor("#1A237E")
private val ACCENT = Color.parseColor("#00897B")
private val BG = Color.parseColor("#F2F4FA")
private val TEXT_DARK = Color.parseColor("#263238")
private val TEXT_MUTED = Color.parseColor("#78909C")

class MainActivity : AppCompatActivity() {
    private lateinit var db: VoterDb
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var subtitle: TextView
    private lateinit var progress: ProgressBar
    private lateinit var chipRow: LinearLayout
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var adapter: VoterAdapter

    private val worker = Executors.newSingleThreadExecutor()
    private val queryExec = Executors.newSingleThreadExecutor()
    private val imageExec = Executors.newFixedThreadPool(2)
    private val ui = Handler(Looper.getMainLooper())
    private var selectedWard = ""          // "" = all wards
    private var searchRunnable: Runnable? = null
    private var querySeq = 0

    private val imageCache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmImport(uri)
    }

    // ---------------------------------------------------------------- UI helpers

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(color)
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = VoterDb(this)
        makeUi()
        loadWards()
        refresh()
    }

    private fun makeUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }

        // ---- Header with gradient, title, import button, search box and ward chips
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val r = dp(26).toFloat()
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR, intArrayOf(PRIMARY_DARK, PRIMARY)
            ).apply { cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r) }
            elevation = dp(6).toFloat()
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply {
            text = "Voter Search"
            textSize = 24f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        subtitle = TextView(this).apply {
            textSize = 12.5f
            setTextColor(Color.parseColor("#CCFFFFFF"))
        }
        titles.addView(subtitle)
        top.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(TextView(this).apply {
            text = "☰ Wards"
            textSize = 13f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = rounded(Color.parseColor("#33FFFFFF"), 20)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            setOnClickListener { showWardList() }
        }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        top.addView(TextView(this).apply {
            text = "⬆ Import"
            textSize = 13f
            setTextColor(PRIMARY_DARK)
            setTypeface(typeface, Typeface.BOLD)
            background = rounded(Color.WHITE, 20)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            setOnClickListener { picker.launch(arrayOf("application/pdf")) }
        })
        header.addView(top)

        val searchBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.WHITE, 28)
            setPadding(dp(16), dp(2), dp(8), dp(2))
        }
        searchBox.addView(TextView(this).apply { text = "🔍"; textSize = 16f })
        input = EditText(this).apply {
            hint = "Name / పేరు, voter ID, serial, age…"
            textSize = 15f
            setTextColor(TEXT_DARK)
            setHintTextColor(TEXT_MUTED)
            background = null
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(10), dp(12), dp(6), dp(12))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    searchRunnable?.let { ui.removeCallbacks(it) }
                    val r = Runnable { refresh() }
                    searchRunnable = r
                    ui.postDelayed(r, 300)
                }
            })
            setOnEditorActionListener { _, _, _ -> refresh(); true }
        }
        searchBox.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        searchBox.addView(TextView(this).apply {
            text = "✕"
            textSize = 16f
            setTextColor(TEXT_MUTED)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { input.setText("") }
        })
        header.addView(searchBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(chipRow)
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        )
        root.addView(header, LinearLayout.LayoutParams(-1, -2))

        // ---- Status + progress
        status = TextView(this).apply {
            textSize = 13f
            setTextColor(TEXT_MUTED)
            setPadding(dp(18), dp(12), dp(18), dp(4))
        }
        root.addView(status)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(ACCENT)
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(6)).apply {
            leftMargin = dp(18); rightMargin = dp(18)
        })

        // ---- Voter list
        adapter = VoterAdapter()
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
            clipToPadding = false
        }
        empty = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(TEXT_MUTED)
            setPadding(dp(32), dp(32), dp(32), dp(32))
            visibility = View.GONE
        }
        val frame = FrameLayout(this)
        frame.addView(list, FrameLayout.LayoutParams(-1, -1))
        frame.addView(empty, FrameLayout.LayoutParams(-1, -1))
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            header.setPadding(dp(16), dp(14) + bars.top, dp(16), dp(16))
            list.setPadding(0, dp(4), 0, bars.bottom + dp(8))
            insets
        }
        setContentView(root)
        WindowCompat.getInsetsController(window, root).isAppearanceLightStatusBars = false
    }

    // ---------------------------------------------------------------- Ward chips

    private fun makeChip(label: String, wardKey: String): TextView = TextView(this).apply {
        text = label
        textSize = 13.5f
        tag = wardKey
        setPadding(dp(14), dp(7), dp(14), dp(7))
        setOnClickListener {
            selectedWard = wardKey
            styleChips()
            refresh()
        }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) }
    }

    private fun styleChips() {
        for (i in 0 until chipRow.childCount) {
            val chip = chipRow.getChildAt(i) as TextView
            val selected = chip.tag == selectedWard
            chip.background = rounded(if (selected) Color.WHITE else Color.parseColor("#33FFFFFF"), 20)
            chip.setTextColor(if (selected) PRIMARY_DARK else Color.WHITE)
            chip.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    /** Reads wards + totals from the database and rebuilds the chip row. */
    private fun loadWards() {
        queryExec.execute {
            val wards = db.wards()
            val total = db.count()
            runOnUiThread {
                chipRow.removeAllViews()
                if (total > 0) {
                    chipRow.addView(makeChip("All voters  $total", ""))
                    wards.forEach { (w, c) -> chipRow.addView(makeChip("$w  $c", w)) }
                }
                if (wards.none { it.first == selectedWard }) selectedWard = ""
                styleChips()
                subtitle.text = if (total > 0) {
                    "$total voters • ${wards.size} ward${if (wards.size == 1) "" else "s"} • works offline"
                } else {
                    "Import your voter list PDF to begin"
                }
            }
        }
    }

    private fun showWardList() {
        queryExec.execute {
            val wards = db.wards()
            val total = db.count()
            runOnUiThread {
                if (total == 0) {
                    Toast.makeText(this, "Import a voter PDF first", Toast.LENGTH_SHORT).show()
                } else {
                    val labels = (listOf("All voters  ($total)") + wards.map { "${it.first}  (${it.second})" })
                        .toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("Show voters of")
                        .setItems(labels) { _, which ->
                            selectedWard = if (which == 0) "" else wards[which - 1].first
                            styleChips()
                            refresh()
                        }
                        .show()
                }
            }
        }
    }

    // ---------------------------------------------------------------- Search / list

    private fun refresh() {
        val query = input.text.toString()
        val ward = selectedWard
        val seq = ++querySeq
        queryExec.execute {
            val rows = db.search(query, ward)
            val total = db.count()
            val blank = if (rows.isEmpty() && total > 0) db.emptyCount() else 0
            val sample = if (rows.isEmpty() && total > 0) db.sample(5).take(120) else ""
            runOnUiThread {
                if (seq == querySeq) {
                    adapter.submit(rows)
                    list.scrollToPosition(0)
                    val scope = if (ward.isEmpty()) "all wards" else ward
                    status.text = when {
                        total == 0 -> ""
                        query.isBlank() -> "Showing all ${rows.size} voters • $scope"
                        else -> "${rows.size} match${if (rows.size == 1) "" else "es"} • $scope"
                    }
                    if (rows.isEmpty()) {
                        empty.visibility = View.VISIBLE
                        empty.text = if (total == 0) {
                            "No voters yet\n\nTap “Import PDF” to load your voter list."
                        } else {
                            "No matching voters\n\nTry fewer words, or a different spelling.\n" +
                                "($blank of $total cards had no readable text.\nSample of OCR on card 5: \"$sample\")"
                        }
                    } else {
                        empty.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun loadImage(path: String, view: ImageView) {
        view.tag = path
        val cached = imageCache.get(path)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageDrawable(null)
        imageExec.execute {
            val bmp = BitmapFactory.decodeFile(path)
            if (bmp != null) {
                imageCache.put(path, bmp)
                runOnUiThread { if (view.tag == path) view.setImageBitmap(bmp) }
            }
        }
    }

    private inner class Holder(
        val card: LinearLayout,
        val badge: TextView,
        val ward: TextView,
        val page: TextView,
        val image: ImageView,
        val voterId: TextView,
        val text: TextView,
        val note: TextView
    ) : RecyclerView.ViewHolder(card)

    private inner class VoterAdapter : RecyclerView.Adapter<Holder>() {
        private var items: List<VoterRow> = emptyList()

        fun submit(rows: List<VoterRow>) {
            items = rows
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val card = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(Color.WHITE, 16)
                elevation = dp(2).toFloat()
                setPadding(dp(12), dp(12), dp(12), dp(12))
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply {
                    setMargins(dp(14), dp(6), dp(14), dp(6))
                }
            }
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val badge = pill(PRIMARY, Color.WHITE)
            val ward = pill(Color.parseColor("#E0F2F1"), ACCENT)
            val page = TextView(this@MainActivity).apply {
                textSize = 12f
                setTextColor(TEXT_MUTED)
                gravity = Gravity.END
            }
            row.addView(badge)
            row.addView(ward, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
            row.addView(page, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(row)

            val image = ImageView(this@MainActivity).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = rounded(Color.parseColor("#F5F7FA"), 10)
            }
            card.addView(image, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

            val voterId = TextView(this@MainActivity).apply {
                textSize = 14f
                setTextColor(ACCENT)
                setTypeface(typeface, Typeface.BOLD)
            }
            card.addView(voterId, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            val text = TextView(this@MainActivity).apply {
                textSize = 14f
                setTextColor(TEXT_DARK)
                maxLines = 5
            }
            card.addView(text, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            val note = TextView(this@MainActivity).apply {
                textSize = 11f
                setTextColor(TEXT_MUTED)
                maxLines = 2
            }
            card.addView(note, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            return Holder(card, badge, ward, page, image, voterId, text, note)
        }

        private fun pill(bg: Int, fg: Int) = TextView(this@MainActivity).apply {
            textSize = 12.5f
            setTextColor(fg)
            setTypeface(typeface, Typeface.BOLD)
            background = rounded(bg, 12)
            setPadding(dp(10), dp(3), dp(10), dp(3))
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            val v = items[position]
            h.badge.text = "#${v.serial}"
            h.ward.text = v.ward
            h.page.text = "Page ${v.pdfPage}"
            val details = parseDetails(v.ocrText)
            val idLine = if (v.voterId.isBlank()) "" else "Voter ID  ${v.voterId}"
            h.voterId.text = listOf(idLine, details).filter { it.isNotEmpty() }.joinToString("\n")
            h.voterId.visibility = if (h.voterId.text.isEmpty()) View.GONE else View.VISIBLE
            h.text.text = v.ocrText.ifBlank { "(no text recognised — see the card image)" }
            if (v.ward == UNASSIGNED_WARD && v.header.isNotBlank()) {
                h.note.visibility = View.VISIBLE
                h.note.text = "Page header read as: ${v.header}"
            } else {
                h.note.visibility = View.GONE
            }
            loadImage(v.imagePath, h.image)
        }
    }

    // ---------------------------------------------------------------- Import

    private fun confirmImport(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("Import voter list?")
            .setMessage("The selected PDF will be copied into this app. Importing a new PDF replaces the currently saved voter list.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Import") { _, _ -> importPdf(uri) }
            .show()
    }

    private fun asciiDigits(s: String): String = buildString {
        for (c in s) append(if (c in '\u0C66'..'\u0C6F') '0' + (c - '\u0C66') else c)
    }

    private val wardRegex = Regex(
        "(?i)(?:ward|వార్డు)\\s*(?:no\\.?|number|నెం\\.?|నంబర్|సంఖ్య)?\\s*[:\\-–.]?\\s*(\\d{1,3})"
    )
    private val voterIdRegex = Regex("[A-Z]{2,3}\\s?\\d{7}")

    private val sectionRegex = Regex(
        "(?i)(?:section|సెక్షన్|విభాగం)\\s*(?:no\\.?|నెం\\.?)?\\s*(?:and name|&\\s*name)?\\s*[:\\-–.]?\\s*(\\d{1,3})"
    )

    private fun detectWard(rawHeader: String): String? {
        val text = asciiDigits(rawHeader)
        wardRegex.find(text)?.let {
            return "Ward " + it.groupValues[1].trimStart('0').ifEmpty { "0" }
        }
        sectionRegex.find(text)?.let {
            return "Section " + it.groupValues[1].trimStart('0').ifEmpty { "0" }
        }
        return null
    }

    /** Best-effort extraction of age / gender / house number from the OCR text of a card. */
    private fun parseDetails(raw: String): String {
        val t = asciiDigits(raw)
        val parts = mutableListOf<String>()
        Regex("(?i)(?:age|వయస్సు|వయసు)\\s*[:\\-]?\\s*(\\d{2,3})").find(t)
            ?.let { parts.add("Age " + it.groupValues[1]) }
        Regex("(?i)\\b(female|male)\\b|(స్త్రీ|మహిళ|పురుషుడు|పురుష)").find(t)
            ?.let { parts.add(it.value.replaceFirstChar { c -> c.uppercase() }) }
        Regex("(?i)(?:house\\s*no\\.?|ఇంటి\\s*నెం\\.?|ఇంటి\\s*నం\\.?)\\s*[:\\-]?\\s*([A-Za-z0-9\\-/]+)").find(t)
            ?.let { parts.add("House " + it.groupValues[1]) }
        return parts.joinToString("  •  ")
    }

    private fun importPdf(uri: Uri) {
        progress.visibility = View.VISIBLE
        progress.progress = 0
        status.text = "Copying PDF. Keep the app open…"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        worker.execute {
            val engines = mutableListOf<TessBaseAPI>()
            var pool: ExecutorService? = null
            var pfd: ParcelFileDescriptor? = null
            var renderer: PdfRenderer? = null
            var completed = false

            try {
                val pdf = File(filesDir, "voter_source.pdf")
                val source = contentResolver.openInputStream(uri)
                    ?: error("Unable to open the selected PDF")
                source.use { inputStream ->
                    FileOutputStream(pdf).use { outputStream -> inputStream.copyTo(outputStream) }
                }
                check(pdf.length() > 0L) { "The selected PDF is empty" }

                val tessRoot = File(filesDir, "tesseract")
                val tessData = File(tessRoot, "tessdata")
                check(tessData.mkdirs() || tessData.isDirectory) { "Cannot create OCR data folder" }

                updateStatus("Downloading / checking English OCR data…")
                downloadLanguageData(tessData, "eng")
                updateStatus("Downloading / checking Telugu OCR data…")
                downloadLanguageData(tessData, "tel")

                val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 4)
                updateStatus("Initializing English + Telugu OCR engine ($threads threads)…")
                val idle = LinkedBlockingQueue<TessBaseAPI>()
                for (i in 0 until threads) {
                    val engine = TessBaseAPI()
                    engines.add(engine)
                    // tessdata_fast files are LSTM-only, so the engine mode must be LSTM_ONLY.
                    val initialized = engine.init(tessRoot.absolutePath, "eng+tel", TessBaseAPI.OEM_LSTM_ONLY)
                    if (!initialized) {
                        File(tessData, "eng.traineddata").delete()
                        File(tessData, "tel.traineddata").delete()
                        error("Tesseract could not initialize eng+tel. The OCR data was reset; please try the import again.")
                    }
                    idle.add(engine)
                }
                pool = Executors.newFixedThreadPool(threads)

                pfd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
                renderer = PdfRenderer(pfd)
                val totalPages = renderer.pageCount
                check(totalPages > 2) { "This PDF has no voter pages after physical page 2" }

                // Clear only after the PDF and OCR engine have opened successfully.
                db.clear()
                var serial = 0
                var currentWard = UNASSIGNED_WARD
                val cardFolder = File(filesDir, "cards")
                check(cardFolder.mkdirs() || cardFolder.isDirectory) { "Cannot create card-image folder" }

                // Physical page 3 is PdfRenderer index 2 (zero-based).
                for (pageIndex in 2 until totalPages) {
                    val page = renderer.openPage(pageIndex)
                    var pageBitmap: Bitmap? = null
                    try {
                        val bitmap = Bitmap.createBitmap(page.width * 3, page.height * 3, Bitmap.Config.ARGB_8888)
                        pageBitmap = bitmap
                        // PdfRenderer draws on a transparent bitmap; without a white background the
                        // page turns black (in JPEG and for OCR). Fill white first.
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                        // 3 columns x 9 rows of voter cards.
                        val left = (bitmap.width * 0.095f).roundToInt()
                        val right = (bitmap.width * 0.91f).roundToInt()
                        val top = (bitmap.height * 0.115f).roundToInt()
                        val bottom = (bitmap.height * 0.915f).roundToInt()
                        val cellWidth = (right - left) / 3
                        val cellHeight = (bottom - top) / 9
                        check(cellWidth > 0 && cellHeight > 0) { "Invalid card grid on PDF page ${pageIndex + 1}" }

                        // Read the strip above the cards to find the ward / section of this page.
                        var headerText = ""
                        val headerBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, top.coerceIn(1, bitmap.height))
                        val headerEngine = idle.take()
                        try {
                            headerEngine.setImage(headerBitmap)
                            headerText = normalizeText(headerEngine.getUTF8Text().orEmpty())
                                .replace(Regex("\\s+"), " ").trim()
                            headerEngine.clear()
                        } finally {
                            idle.put(headerEngine)
                            headerBitmap.recycle()
                        }
                        detectWard(headerText)?.let { currentWard = it }

                        // Crop all cards of this page, then OCR them in parallel.
                        val cards = ArrayList<Bitmap>(27)
                        try {
                            for (row in 0 until 9) {
                                for (column in 0 until 3) {
                                    val x = left + column * cellWidth
                                    val y = top + row * cellHeight
                                    val rect = Rect(
                                        x.coerceAtLeast(0),
                                        y.coerceAtLeast(0),
                                        (x + cellWidth).coerceAtMost(bitmap.width),
                                        (y + cellHeight).coerceAtMost(bitmap.height)
                                    )
                                    if (rect.width() <= 0 || rect.height() <= 0) continue
                                    cards.add(Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height()))
                                }
                            }

                            val tasks = cards.map { card ->
                                Callable<String> {
                                    val engine = idle.take()
                                    try {
                                        engine.setImage(card)
                                        val t = engine.getUTF8Text().orEmpty()
                                            .replace(Regex("\\s+"), " ")
                                            .trim()
                                        engine.clear()
                                        t
                                    } finally {
                                        idle.put(engine)
                                    }
                                }
                            }
                            val texts = pool!!.invokeAll(tasks).map { it.get() }

                            val database = db.writableDatabase
                            database.beginTransaction()
                            try {
                                for (i in cards.indices) {
                                    serial++
                                    val imageFile = File(cardFolder, "card_$serial.jpg")
                                    FileOutputStream(imageFile).use { output ->
                                        check(cards[i].compress(Bitmap.CompressFormat.JPEG, 82, output)) {
                                            "Could not save card image $serial"
                                        }
                                    }
                                    val voterId = voterIdRegex.find(texts[i])?.value?.replace(" ", "").orEmpty()
                                    db.insert(
                                        serial, pageIndex + 1, currentWard, headerText.take(160),
                                        voterId, texts[i], imageFile.absolutePath
                                    )
                                }
                                database.setTransactionSuccessful()
                            } finally {
                                database.endTransaction()
                            }
                        } finally {
                            cards.forEach { it.recycle() }
                        }
                    } finally {
                        pageBitmap?.recycle()
                        page.close()
                    }

                    val percent = (((pageIndex - 1).toFloat() / (totalPages - 2).coerceAtLeast(1)) * 100)
                        .roundToInt().coerceIn(0, 100)
                    val done = serial
                    runOnUiThread {
                        progress.progress = percent
                        status.text = "Processing PDF page ${pageIndex + 1}/$totalPages • $done voters saved"
                    }
                }

                completed = serial > 0
                check(completed) { "No voter cards were extracted from the PDF" }
            } catch (e: OutOfMemoryError) {
                runOnUiThread {
                    status.text = "Import stopped because the device ran out of memory. Close other apps and try again."
                    progress.visibility = View.GONE
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Import failed: ${e.message ?: e.javaClass.simpleName}"
                    progress.visibility = View.GONE
                }
            } finally {
                try { renderer?.close() } catch (_: Exception) { }
                try { pfd?.close() } catch (_: Exception) { }
                pool?.shutdownNow()
                engines.forEach { try { it.recycle() } catch (_: Exception) { } }
                runOnUiThread { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }

            if (completed) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    imageCache.evictAll()
                    selectedWard = ""
                    loadWards()
                    refresh()
                    Toast.makeText(this, "Import complete", Toast.LENGTH_LONG).show()
                }
            } else {
                runOnUiThread { loadWards(); refresh() }
            }
        }
    }

    private fun downloadLanguageData(directory: File, language: String) {
        val target = File(directory, "$language.traineddata")
        if (target.exists() && target.length() > 100_000L) return

        val temporary = File(directory, "$language.traineddata.download")
        temporary.delete()
        val connection = URL(
            "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/$language.traineddata"
        ).openConnection() as HttpURLConnection
        connection.connectTimeout = 25_000
        connection.readTimeout = 90_000
        connection.instanceFollowRedirects = true

        try {
            connection.connect()
            check(connection.responseCode in 200..299) {
                "Could not download $language OCR data (HTTP ${connection.responseCode})"
            }
            connection.inputStream.use { inputStream ->
                FileOutputStream(temporary).use { outputStream -> inputStream.copyTo(outputStream) }
            }
            check(temporary.length() > 100_000L) { "Downloaded $language OCR data is incomplete" }
            if (target.exists()) target.delete()
            check(temporary.renameTo(target)) { "Could not install $language OCR data" }
        } finally {
            connection.disconnect()
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun updateStatus(message: String) {
        runOnUiThread { status.text = message }
    }

    override fun onDestroy() {
        worker.shutdown()
        queryExec.shutdown()
        imageExec.shutdown()
        super.onDestroy()
    }
}
