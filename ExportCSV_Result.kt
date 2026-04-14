package EntreApps.Shared.Modules.Base.SQL

import EntreApps.Shared.Models.M00CentralParametresOfAllApps.Companion.central_Local_storageLink
import EntreApps.Shared.Modules.Base.AppDatabase
import java.io.File

// ---------------------------------------------------------------------------
// Result type returned to the caller
// ---------------------------------------------------------------------------
data class ExportCSV_Result(
    val exportedFiles: List<File>,
    val outputDir: File,
    val totalRows: Int,
)

// ---------------------------------------------------------------------------
// Main export function — call as: appDatabase.exportAllTablesToCSV(...)
// ---------------------------------------------------------------------------
/**
 * Exports every Room table to its own "<TableName>.csv" file inside
 *   [central_Local_storageLink]/CSV_Export/
 *
 * Delegates to [LocalCsvDatabase.export] — no logic is duplicated here.
 *
 * @param onProgress        0f → 1f as tables are processed
 * @param onCurrentTable    current table name, useful for a live label
 */
suspend fun AppDatabase.exportAllTablesToCSV(
    onProgress:     (Float)  -> Unit = {},
    onCurrentTable: (String) -> Unit = {},
): Result<ExportCSV_Result> {
    val outputDir = File(central_Local_storageLink, "CSV_Export")

    return LocalCsvDatabase.export(
        db         = this,
        outputDir  = outputDir,
        onProgress = onProgress,
        onTable    = onCurrentTable,
    ).map { result ->
        ExportCSV_Result(
            exportedFiles = result.exportedFiles,
            outputDir     = result.outputDir,
            totalRows     = result.totalRows,
        )
    }
}
