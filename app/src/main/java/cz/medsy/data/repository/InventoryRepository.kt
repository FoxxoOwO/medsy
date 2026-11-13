package cz.medsy.data.repository

import cz.medsy.data.local.dao.ConsumableDao
import cz.medsy.data.local.dao.InventoryDao
import cz.medsy.data.local.dao.MedicationDao
import cz.medsy.data.local.dao.ScheduleDao
import cz.medsy.data.mapper.toDomain
import cz.medsy.data.mapper.toEntity
import cz.medsy.domain.calc.InventoryCalculator
import cz.medsy.domain.calc.InventoryCalculator.isExpired
import cz.medsy.domain.calc.InventoryCalculator.isPastBeyondUse
import cz.medsy.domain.inventory.BatchDraft
import cz.medsy.domain.inventory.BatchDraftValidator
import cz.medsy.domain.inventory.BatchValidation
import cz.medsy.domain.inventory.toBatch
import cz.medsy.domain.model.Consumable
import cz.medsy.domain.model.InventoryBatch
import cz.medsy.domain.model.Medication
import cz.medsy.domain.model.MedicationForm
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InventoryRepository @Inject constructor(
    private val inventoryDao: InventoryDao,
    private val consumableDao: ConsumableDao,
    private val medicationDao: MedicationDao,
    private val scheduleDao: ScheduleDao,
) {

    fun observeBatches(medicationId: Long): Flow<List<InventoryBatch>> =
        inventoryDao.observeForMedication(medicationId).map { list -> list.map { it.toDomain() } }

    fun observeAllBatches(): Flow<List<InventoryBatch>> =
        inventoryDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeConsumables(): Flow<List<Consumable>> =
        consumableDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeMedications(): Flow<List<Medication>> =
        medicationDao.observeAll()
            .map { list -> list.map { it.toDomain() }.filterNot { it.isArchived } }

    /**
     * Validates a hand-entered batch and stores it. Invalid drafts are never written: a
     * quantity of zero would otherwise sit on the shelf forever as invisible stock.
     */
    suspend fun addBatch(
        draft: BatchDraft,
        form: MedicationForm,
        today: LocalDate = LocalDate.now(),
    ): BatchValidation {
        val validation = BatchDraftValidator.validate(draft, form, today)
        if (validation.isValid) {
            inventoryDao.insert(draft.toBatch().toEntity())
        }
        return validation
    }

    suspend fun saveBatch(batch: InventoryBatch): Long {
        val entity = batch.toEntity()
        return if (entity.id == 0L) inventoryDao.insert(entity) else {
            inventoryDao.update(entity)
            entity.id
        }
    }

    suspend fun deleteBatch(id: Long) = inventoryDao.delete(id)

    suspend fun saveConsumable(consumable: Consumable): Long {
        val entity = cz.medsy.data.local.entity.ConsumableEntity(
            id = consumable.id,
            name = consumable.name,
            remainingCount = consumable.remainingCount,
            lowStockAlertCount = consumable.lowStockAlertCount,
            deductPerMedicationId = consumable.deductPerMedicationId,
            deductPerDose = consumable.deductPerDose,
        )
        return if (entity.id == 0L) consumableDao.insert(entity) else {
            consumableDao.update(entity)
            entity.id
        }
    }

    suspend fun stockLines(today: LocalDate = LocalDate.now()): List<InventoryCalculator.StockLine> {
        val meds = medicationDao.observeAll().first().map { it.toDomain() }
        val allBatches = inventoryDao.observeAll().first().map { it.toDomain() }
        val schedules = scheduleDao.getActive().map { it.toDomain() }
        return meds.filter { !it.isArchived }.map { medication ->
            val own = allBatches.filter { it.medicationId == medication.id }
            val unit = own.firstOrNull()?.unit ?: medication.strengthUnit.orEmpty()
            InventoryCalculator.buildStockLine(
                medicationId = medication.id,
                medicationName = medication.name,
                unit = unit,
                batches = own,
                schedules = schedules,
                today = today,
            )
        }
    }

    suspend fun travelPlan(
        tripStart: LocalDate,
        tripEnd: LocalDate,
        bufferDays: Int,
    ): InventoryCalculator.TravelPlan {
        val meds = medicationDao.observeAll().first().map { it.toDomain() }
            .filter { !it.isArchived }
        val schedules = scheduleDao.getActive().map { it.toDomain() }
        val batches = inventoryDao.observeAll().first().map { it.toDomain() }
        val consumables = consumableDao.observeAll().first().map { it.toDomain() }
        val days = InventoryCalculator.daysBetween(tripStart, tripEnd)
        return InventoryCalculator.travelPlan(
            schedulesByMedication = schedules.groupBy { it.medicationId },
            names = meds.associate { it.id to it.name },
            units = meds.associate { it.id to (it.strengthUnit ?: "units") },
            // Only count stock that can legally travel: expired or past-BUD batches must
            // not make the plan look sufficient.
            availableByMedication = meds.associate { medication ->
                medication.id to batches
                    .filter { it.medicationId == medication.id }
                    .filterNot {
                        it.isExpired(tripStart) || it.isPastBeyondUse(tripStart)
                    }
                    .fold(BigDecimal.ZERO) { acc, batch -> acc.add(batch.remainingQuantity) }
            },
            tripDays = days,
            bufferDays = bufferDays,
            tripStart = tripStart,
            formsByMedication = meds.associate { it.id to it.form },
            // Same kind may be held as several rows ("29G syringe" and "1 ml syringe");
            // the plan speaks about the kind, so sum them. Rows whose name matches no kind
            // (gauze, gloves) are ignored rather than guessed at.
            consumableStock = consumables
                .groupBy { InventoryCalculator.consumableKind(it.name) }
                .mapNotNull { (kind, rows) ->
                    kind?.let { it to rows.sumOf { row -> row.remainingCount } }
                }
                .toMap(),
        )
    }

    fun observeStockSummary(): Flow<List<InventoryCalculator.StockLine>> =
        combine(
            medicationDao.observeAll(),
            inventoryDao.observeAll(),
            scheduleDao.observeActive(),
        ) { meds, batches, schedules ->
            val today = LocalDate.now()
            val scheduleList = schedules.map { it.toDomain() }
            meds.map { it.toDomain() }
                .filter { !it.isArchived }
                .map { medication ->
                    val own = batches.filter { it.medicationId == medication.id }.map { it.toDomain() }
                    InventoryCalculator.buildStockLine(
                        medicationId = medication.id,
                        medicationName = medication.name,
                        unit = own.firstOrNull()?.unit ?: medication.strengthUnit.orEmpty(),
                        batches = own,
                        schedules = scheduleList,
                        today = today,
                    )
                }
        }
}
