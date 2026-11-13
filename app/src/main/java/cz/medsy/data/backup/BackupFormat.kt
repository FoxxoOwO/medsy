package cz.medsy.data.backup

/**
 * What a backup file contains and how its rows line up with the rows already in the database.
 *
 * The list is ordered parents-first so a restore can satisfy the foreign keys while it works
 * through the file: `dose_logs` needs `medications`, `schedules` and `inventory_batches`, so it
 * comes last.
 *
 * `sukl_medications` is deliberately absent. It is a catalogue that can be downloaded again, it is
 * two orders of magnitude larger than the patient data, and restoring an old copy would silently
 * undo a later sync.
 */
internal object BackupFormat {

    const val HEADER = "cz.medsy.backup"
    const val VERSION = 1

    val TABLES: List<Table> = listOf(
        Table(name = "medications", keys = listOf("name", "form", "strengthValue")),
        Table(name = "blood_test_sessions", keys = listOf("timestamp", "labName")),
        Table(name = "prescriptions", keys = listOf("code")),
        Table(
            name = "schedules",
            keys = listOf("medicationId", "scheduleType", "timesOfDayJson", "doseAmount", "doseUnit"),
            parents = mapOf("medicationId" to "medications"),
        ),
        Table(
            name = "inventory_batches",
            keys = listOf("medicationId", "batchNumber", "expirationDateEpochDay", "initialQuantity"),
            parents = mapOf("medicationId" to "medications"),
        ),
        Table(
            name = "consumables",
            keys = listOf("name"),
            parents = mapOf("deductPerMedicationId" to "medications"),
        ),
        Table(
            name = "prescription_items",
            keys = listOf("prescriptionId", "name", "quantity"),
            parents = mapOf("prescriptionId" to "prescriptions"),
        ),
        Table(
            name = "biomarker_values",
            keys = listOf("sessionId", "biomarkerCode"),
            parents = mapOf("sessionId" to "blood_test_sessions"),
        ),
        Table(name = "reference_ranges", keys = listOf("biomarkerCode", "profileType")),
        Table(
            name = "medication_milestones",
            keys = listOf("medicationId", "timestamp", "changeType"),
            parents = mapOf("medicationId" to "medications"),
        ),
        Table(name = "symptom_logs", keys = listOf("timestamp", "symptomCode", "severity")),
        Table(
            name = "dose_logs",
            keys = listOf("medicationId", "scheduledTimestamp", "status"),
            parents = mapOf(
                "medicationId" to "medications",
                "scheduleId" to "schedules",
                "batchId" to "inventory_batches",
            ),
        ),
    )

    /**
     * A foreign key that may simply be dropped when its parent is missing from the file; every
     * other parent column is mandatory, so a row referring to an unknown parent is skipped rather
     * than inserted as an orphan.
     */
    val NULLABLE_PARENTS: Set<String> = setOf("batchId", "deductPerMedicationId")

    class Table(
        val name: String,
        /** Columns that make two rows "the same record" when merging. */
        val keys: List<String>,
        /** Column -> parent table whose restored id replaces the exported one. */
        val parents: Map<String, String> = emptyMap(),
    )
}
