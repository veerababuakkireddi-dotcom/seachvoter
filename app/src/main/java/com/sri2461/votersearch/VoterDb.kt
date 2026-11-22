package com.sri2461.votersearch

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.text.Normalizer

const val UNASSIGNED_WARD = "Unassigned"

/** Unicode-normalise text so Telugu typed on a keyboard matches Telugu read by OCR. */
fun normalizeText(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFC)
        .replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "")

data class VoterRow(
    val serial: Int,
    val pdfPage: Int,
    val ward: String,
    val header: String,
    val voterId: String,
    val ocrText: String,
    val imagePath: String
)

class VoterDb(context: Context) : SQLiteOpenHelper(context, "voters.db", null, 2) {
    private val appFilesDir: File = context.filesDir

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE voters (serial INTEGER PRIMARY KEY, page INTEGER NOT NULL, " +
                "ward TEXT NOT NULL, header TEXT, voter_id TEXT, ocr TEXT, image TEXT NOT NULL)"
        )
        database.execSQL("CREATE INDEX voter_ward_idx ON voters(ward)")
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        database.execSQL("DROP TABLE IF EXISTS voters")
        onCreate(database)
    }

    fun clear() {
        writableDatabase.delete("voters", null, null)
        File(appFilesDir, "cards").deleteRecursively()
    }

    fun count(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM voters", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun emptyCount(): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM voters WHERE ocr IS NULL OR LENGTH(TRIM(ocr)) < 5", null
        ).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    fun sample(serial: Int): String {
        readableDatabase.rawQuery(
            "SELECT ocr FROM voters WHERE serial = ?", arrayOf(serial.toString())
        ).use { c -> return if (c.moveToFirst()) c.getString(0).orEmpty() else "" }
    }

    /** Ward name + number of voters, in the order the wards appear in the PDF. */
    fun wards(): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        readableDatabase.rawQuery(
            "SELECT ward, COUNT(*) FROM voters GROUP BY ward ORDER BY MIN(serial)", null
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0) to c.getInt(1))
        }
        return out
    }

    fun insert(
        serial: Int, pdfPage: Int, ward: String, header: String,
        voterId: String, text: String, imagePath: String
    ) {
        val values = ContentValues().apply {
            put("serial", serial)
            put("page", pdfPage)
            put("ward", ward)
            put("header", header)
            put("voter_id", voterId)
            put("ocr", normalizeText(text))
            put("image", imagePath)
        }
        val result = writableDatabase.insert("voters", null, values)
        check(result != -1L) { "Could not save voter card $serial to the database" }
    }

    /**
     * Every word typed must appear somewhere on the card (name, voter ID, age, house no...).
     * An empty query returns all voters. [ward] empty = all wards.
     */
    fun search(query: String, ward: String, limit: Int = 5000): List<VoterRow> {
        val words = normalizeText(query).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val conditions = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (ward.isNotEmpty()) {
            conditions.add("ward = ?")
            args.add(ward)
        }
        for (w in words) {
            conditions.add("(ocr LIKE ? OR voter_id LIKE ? OR CAST(serial AS TEXT) = ?)")
            args.add("%$w%")
            args.add("%$w%")
            args.add(w)
        }
        val where = if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")
        val out = mutableListOf<VoterRow>()
        readableDatabase.rawQuery(
            "SELECT serial, page, ward, header, voter_id, ocr, image FROM voters $where " +
                "ORDER BY serial LIMIT $limit",
            args.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    VoterRow(
                        serial = c.getInt(0),
                        pdfPage = c.getInt(1),
                        ward = c.getString(2).orEmpty(),
                        header = c.getString(3).orEmpty(),
                        voterId = c.getString(4).orEmpty(),
                        ocrText = c.getString(5).orEmpty(),
                        imagePath = c.getString(6).orEmpty()
                    )
                )
            }
        }
        return out
    }
}
