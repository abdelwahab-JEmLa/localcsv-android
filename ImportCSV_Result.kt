package EntreApps.Shared.Modules.Base.SQL

import EntreApps.Shared.Models.M00CentralParametresOfAllApps.Companion.central_Local_storageLink
import EntreApps.Shared.Modules.Base.AppDatabase
import java.io.File

// ---------------------------------------------------------------------------
// Result type returned to the caller
// ---------------------------------------------------------------------------
data class ImportCSV_Result(
    val importedTables: List<String>,
    val totalRows: Int,
)

// ---------------------------------------------------------------------------
// Main import function — call as: appDatabase.importAllTablesFromCSV(...)
// ---------------------------------------------------------------------------
/**
 * For every "<TableName>.csv" found in [central_Local_storageLink]/CSV_Export/,
 * deletes all existing rows in that table then re-inserts every row from the CSV.
 *
 * Delegates to [LocalCsvDatabase.import] — no logic is duplicated here.
 * NOT NULL constraint errors are logged with full table / row / column context
 * by [LocalCsvDatabase.import]; see its implementation for details.
 *
 * @param onProgress        0f → 1f as tables are processed
 * @param onCurrentTable    current table name, useful for a live label
 */
suspend fun AppDatabase.importAllTablesFromCSV(
    onProgress:     (Float)  -> Unit = {},
    onCurrentTable: (String) -> Unit = {},
): Result<ImportCSV_Result> {
    val csvDir = File(central_Local_storageLink, "CSV_Export")

    return LocalCsvDatabase.import(
        db         = this,
        csvDir     = csvDir,
        onProgress = onProgress,
        onTable    = onCurrentTable,
    ).map { result ->
        ImportCSV_Result(
            importedTables = result.importedTables,
            totalRows      = result.totalRows,
        )
    }
}
