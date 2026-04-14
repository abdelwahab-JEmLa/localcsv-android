/**
 * LocalCsvDatabase.kt
 * ────────────────────────────────────────────────────────────────────────────
 * Bibliothèque légère d'import / export CSV pour n'importe quelle RoomDatabase.
 *
 * UTILISATION — export :
 *   val result = LocalCsvDatabase.export(appDatabase, outputDir)
 *   result.onSuccess { Log.d("CSV", "${it.totalRows} lignes exportées → ${it.outputDir}") }
 *
 * UTILISATION — import :
 *   val result = LocalCsvDatabase.import(appDatabase, csvDir)
 *   result.onSuccess { Log.d("CSV", "${it.totalRows} lignes importées") }
 *
 * UTILISATION — avec progression (pour une UI) :
 *   LocalCsvDatabase.export(
 *       db          = appDatabase,
 *       outputDir   = myDir,
 *       onProgress  = { fraction -> progressBar.progress = (fraction * 100).toInt() },
 *       onTable     = { name    -> label.text = name },
 *   )
 *
 * Dépendances  : Room (androidx.room), Kotlin Coroutines
 * Aucun DAO ni entité spécifique n'est requis — fonctionne sur le SQLite brut.
 * ────────────────────────────────────────────────────────────────────────────
 */

package EntreApps.Shared.Modules.Base.SQL

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
import android.util.Log
import androidx.room.RoomDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter

private const val TAG = "LocalCsvDatabase"

// ─── Public result types ──────────────────────────────────────────────────────

data class CsvExportResult(
    val exportedFiles : List<File>,
    val outputDir     : File,
    val totalRows     : Int,
)

data class CsvImportResult(
    val importedTables : List<String>,
    val totalRows      : Int,
    /** Rows skipped because of a constraint violation (NOT NULL, FK, UNIQUE…). */
    val skippedRows    : Int = 0,
)

// ─── Library object ───────────────────────────────────────────────────────────

object LocalCsvDatabase {

    // ── EXPORT ────────────────────────────────────────────────────────────────

    /**
     * Exporte chaque table Room dans son propre fichier "<NomTable>.csv"
     * à l'intérieur de [outputDir].
     */
    suspend fun export(
        db         : RoomDatabase,
        outputDir  : File,
        onProgress : (Float)  -> Unit = {},
        onTable    : (String) -> Unit = {},
    ): Result<CsvExportResult> = withContext(Dispatchers.IO) {
        runCatching {
            outputDir.mkdirs()

            val raw        = db.openHelper.readableDatabase
            val tableNames = raw.userTableNames()

            var totalRows    = 0
            val writtenFiles = mutableListOf<File>()

            tableNames.forEachIndexed { i, name ->
                onTable(name)
                onProgress(i.toFloat() / tableNames.size)

                val file = File(outputDir, "$name.csv")
                raw.query("SELECT * FROM `$name`").use { cursor ->
                    FileWriter(file, false).use { writer ->
                        writer.writeCsvTable(cursor)
                        totalRows += cursor.count
                    }
                }
                writtenFiles += file
            }

            onProgress(1f)
            CsvExportResult(
                exportedFiles = writtenFiles,
                outputDir     = outputDir,
                totalRows     = totalRows,
            )
        }
    }

    // ── IMPORT ────────────────────────────────────────────────────────────────

    /**
     * Pour chaque "<NomTable>.csv" trouvé dans [csvDir] :
     *   1. Supprime toutes les lignes existantes de la table.
     *   2. Réinsère chaque ligne depuis le CSV (transaction atomique par table).
     *
     * ### Gestion des cellules vides / contraintes NOT NULL
     *
     * Room déclare souvent ses champs `String` sans `?`, ce qui génère une
     * contrainte SQLite `NOT NULL`.  Quand le CSV contient une cellule vide pour
     * une telle colonne, l'ancienne version envoyait `NULL` → crash.
     *
     * Désormais, pour chaque cellule vide :
     * - Colonne de type INTEGER → stocke `0`
     * - Colonne de type REAL / FLOAT / DOUBLE → stocke `0.0`
     * - Colonne de type TEXT / BLOB / inconnu → stocke `""` (chaîne vide)
     *
     * Si malgré cette substitution SQLite rejette quand même la ligne (contrainte
     * UNIQUE, FK, etc.), la ligne est **ignorée** (skip), un log détaillé est émis
     * dans Logcat (`LocalCsvDatabase` tag) et l'import continue avec la ligne
     * suivante.  Le nombre total de lignes ignorées est retourné dans
     * [CsvImportResult.skippedRows].
     */
    suspend fun import(
        db         : RoomDatabase,
        csvDir     : File,
        onProgress : (Float)  -> Unit = {},
        onTable    : (String) -> Unit = {},
    ): Result<CsvImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val csvFiles = csvDir
                .listFiles { f -> f.extension.equals("csv", ignoreCase = true) }
                ?.sortedBy { it.nameWithoutExtension }
                ?: emptyList()

            val raw            = db.openHelper.writableDatabase
            val existingTables = raw.userTableNames().toHashSet()

            var totalRows   = 0
            var skippedRows = 0
            val importedTables = mutableListOf<String>()

            csvFiles.forEachIndexed { i, file ->
                val tableName = file.nameWithoutExtension
                if (tableName !in existingTables) return@forEachIndexed

                onTable(tableName)
                onProgress(i.toFloat() / csvFiles.size)

                val rows = file.parseCsvRows()
                if (rows.size < 2) return@forEachIndexed   // en-tête seul ou vide

                val headers  = rows[0]
                val dataRows = rows.drop(1)

                // Read SQLite type affinity per column via PRAGMA so we can pick the
                // correct non-null fallback for empty CSV cells.
                val columnTypes: Map<String, String> = buildMap {
                    raw.query("PRAGMA table_info(`$tableName`)").use { c ->
                        // columns: cid | name | type | notnull | dflt_value | pk
                        val nameIdx = c.getColumnIndex("name")
                        val typeIdx = c.getColumnIndex("type")
                        while (c.moveToNext()) {
                            put(c.getString(nameIdx), c.getString(typeIdx).uppercase())
                        }
                    }
                }

                var tableSkipped = 0

                raw.beginTransaction()
                try {
                    raw.execSQL("DELETE FROM `$tableName`")

                    dataRows.forEachIndexed { rowIndex, cells ->
                        val cv = ContentValues(headers.size)

                        headers.forEachIndexed { col, colName ->
                            val value = cells.getOrNull(col) ?: ""
                            if (value.isNotEmpty()) {
                                // Normal case: store the CSV value as a string; Room's
                                // type converters will handle the actual conversion.
                                cv.put(colName, value)
                            } else {
                                // Empty cell: instead of NULL (which breaks NOT NULL columns),
                                // store a type-appropriate zero / empty-string default.
                                val affinity = columnTypes[colName] ?: ""
                                when {
                                    affinity.contains("INT")  -> cv.put(colName, 0L)
                                    affinity.contains("REAL") ||
                                            affinity.contains("FLOA") ||
                                            affinity.contains("DOUB") -> cv.put(colName, 0.0)
                                    // TEXT, BLOB, or unknown → empty string satisfies NOT NULL String fields
                                    else                      -> cv.put(colName, "")
                                }
                            }
                        }

                        try {
                            raw.insert(tableName, CONFLICT_REPLACE, cv)
                        } catch (e: SQLiteConstraintException) {
                            // A UNIQUE or FK constraint fired even after the NOT NULL substitution.
                            // Log full context and skip this single row — do not abort the table.
                            val emptyCols = headers.filterIndexed { col, _ ->
                                cells.getOrNull(col).isNullOrEmpty()
                            }
                            Log.e(
                                TAG,
                                """
                                |SQLiteConstraintException — ligne ignorée
                                |  Table         : $tableName
                                |  Ligne CSV     : ${rowIndex + 2}  (ligne 1 = en-tête)
                                |  Colonnes vides dans le CSV : $emptyCols
                                |  En-têtes      : $headers
                                |  Valeurs CSV   : $cells
                                |  ContentValues : $cv
                                """.trimMargin(),
                                e,
                            )
                            tableSkipped++
                        }
                    }

                    raw.setTransactionSuccessful()
                    val inserted = dataRows.size - tableSkipped
                    totalRows   += inserted
                    skippedRows += tableSkipped
                    importedTables += tableName

                    if (tableSkipped > 0) {
                        Log.w(TAG, "Table $tableName : $inserted lignes importées, $tableSkipped ignorées.")
                    }
                } finally {
                    raw.endTransaction()
                }
            }

            onProgress(1f)

            if (skippedRows > 0) {
                Log.w(TAG, "Import terminé — $skippedRows ligne(s) ignorée(s) au total sur contrainte SQLite.")
            }

            CsvImportResult(
                importedTables = importedTables,
                totalRows      = totalRows,
                skippedRows    = skippedRows,
            )
        }
    }
}

// ─── Extensions privées ───────────────────────────────────────────────────────

private fun androidx.sqlite.db.SupportSQLiteDatabase.userTableNames(): List<String> =
    buildList {
        query(
            "SELECT name FROM sqlite_master " +
                    "WHERE type='table' " +
                    "  AND name NOT LIKE 'sqlite_%' " +
                    "  AND name NOT LIKE 'room_%' " +
                    "  AND name != 'android_metadata'"
        ).use { c -> while (c.moveToNext()) add(c.getString(0)) }
    }

private fun FileWriter.writeCsvTable(cursor: Cursor) {
    val cols = cursor.columnCount
    writeCsvRow((0 until cols).map { cursor.getColumnName(it) })
    while (cursor.moveToNext()) {
        writeCsvRow((0 until cols).map { col ->
            when (cursor.getType(col)) {
                Cursor.FIELD_TYPE_NULL    -> ""
                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(col).toString()
                Cursor.FIELD_TYPE_FLOAT   -> cursor.getDouble(col).toString()
                else                      -> cursor.getString(col) ?: ""
            }
        })
    }
}

private fun FileWriter.writeCsvRow(cells: List<String>) {
    write(cells.joinToString(",") { it.escapeCsvCell() })
    write("\n")
}

private fun String.escapeCsvCell(): String =
    if (contains(',') || contains('"') || contains('\n') || contains('\r'))
        "\"${replace("\"", "\"\"")}\""
    else
        this

private fun File.parseCsvRows(): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    val text = readText()
    var pos  = 0

    fun parseCell(): String {
        if (pos >= text.length) return ""
        return if (text[pos] == '"') {
            pos++
            val sb = StringBuilder()
            while (pos < text.length) {
                val ch = text[pos++]
                if (ch == '"') {
                    if (pos < text.length && text[pos] == '"') { sb.append('"'); pos++ }
                    else break
                } else sb.append(ch)
            }
            sb.toString()
        } else {
            val start = pos
            while (pos < text.length && text[pos] != ',' && text[pos] != '\n' && text[pos] != '\r')
                pos++
            text.substring(start, pos)
        }
    }

    while (pos < text.length) {
        val row = mutableListOf(parseCell())
        while (pos < text.length && text[pos] == ',') { pos++; row += parseCell() }
        if (pos < text.length && text[pos] == '\r') pos++
        if (pos < text.length && text[pos] == '\n') pos++
        rows += row
    }

    return rows
}
