package cz.medsy.data.repository

import cz.medsy.data.local.dao.PrescriptionDao
import cz.medsy.data.local.dao.PrescriptionWithItems
import cz.medsy.data.local.entity.PrescriptionEntity
import cz.medsy.data.local.entity.PrescriptionItemEntity
import cz.medsy.domain.model.Prescription
import cz.medsy.domain.model.PrescriptionItem
import cz.medsy.domain.prescription.PrescriptionQueue
import cz.medsy.domain.prescription.QueuedPrescription
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The queue of scanned eRecepts. Scanning the same QR twice is a normal accident (the code
 * stays on screen, people rescan), so a duplicate is reported rather than thrown or
 * silently duplicated: [SaveResult.ALREADY_SAVED] lets the scanner say "already saved"
 * instead of pretending it did something.
 */
@Singleton
class PrescriptionRepository @Inject constructor(
    private val dao: PrescriptionDao,
) {

    /** Overridable in tests; production always reads the wall clock. */
    internal var now: () -> Instant = Instant::now

    enum class SaveResult { SAVED, ALREADY_SAVED }

    fun observeAll(): Flow<List<QueuedPrescription>> =
        dao.observeWithItems().map { rows -> rows.map(::toDomain) }

    fun observeActive(): Flow<List<QueuedPrescription>> =
        dao.observeWithItems().map { rows ->
            PrescriptionQueue.active(rows.map(::toDomain), now())
        }

    suspend fun save(prescription: Prescription): SaveResult {
        val id = dao.insertPrescription(
            header = PrescriptionEntity(
                code = prescription.code,
                issuedAt = prescription.issuedAt?.toEpochMilli(),
                validUntil = prescription.validUntil?.toEpochMilli(),
                dispensed = false,
            ),
            items = prescription.medications.map { it.toEntity() },
        )
        return if (id == null) SaveResult.ALREADY_SAVED else SaveResult.SAVED
    }

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun markDispensed(id: Long) = dao.markDispensed(id)

    suspend fun active(): List<QueuedPrescription> =
        PrescriptionQueue.active(dao.allWithItems().map(::toDomain), now())

    private fun toDomain(row: PrescriptionWithItems) = QueuedPrescription(
        id = row.prescription.id,
        code = row.prescription.code,
        issuedAt = row.prescription.issuedAt?.let(Instant::ofEpochMilli),
        validUntil = row.prescription.validUntil?.let(Instant::ofEpochMilli),
        dispensed = row.prescription.dispensed,
        items = row.items.map { item ->
            PrescriptionItem(
                suklCode = item.suklCode,
                name = item.name,
                quantity = item.quantity,
                dosageInstruction = item.dosageInstruction,
            )
        },
    )

    private fun PrescriptionItem.toEntity() = PrescriptionItemEntity(
        prescriptionId = 0,
        suklCode = suklCode,
        name = name,
        quantity = quantity,
        dosageInstruction = dosageInstruction,
    )
}
