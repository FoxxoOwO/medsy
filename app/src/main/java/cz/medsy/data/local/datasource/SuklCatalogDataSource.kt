package cz.medsy.data.local.datasource

import cz.medsy.data.local.dao.SuklDao
import cz.medsy.data.local.entity.SuklMedicationEntity
import cz.medsy.domain.model.ParsedBarcode
import cz.medsy.domain.model.SuklMedication
import cz.medsy.domain.scanner.Gtin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stage 1 of the lookup waterfall: the SÚKL register that `SuklRepository.sync()` keeps in the
 * local database.
 *
 * This deliberately reuses the existing `sukl_medications` table and its 70 000 synced rows
 * instead of introducing a second catalog copy — a parallel table would double the sync cost
 * and could disagree with the one the settings screen reports.
 *
 * Caveat that must not be papered over: the `EAN` column in the SÚKL open data has been empty
 * since the 2026-07 release (see `SuklRepository.findByGtin`), so barcode lookups normally fall
 * through to the next stage. Name/code/substance search is the part that actually hits.
 *
 * `LIKE` is ASCII-case-insensitive only; a query typed without diacritics ("zelezo") will not
 * match "železo". Folding would need a separate normalized column, which is not worth a schema
 * migration for a search box that the user can retype.
 */
@Singleton
class SuklCatalogDataSource @Inject constructor(
    private val dao: SuklDao,
) {

    suspend fun findByBarcode(parsed: ParsedBarcode): SuklMedication? = withContext(Dispatchers.IO) {
        val candidates = buildList {
            parsed.gtin14?.let { addAll(Gtin.variantsOf(it)) }
            parsed.ean13?.let { addAll(Gtin.variantsOf(it)) }
        }.distinct()
        if (candidates.isEmpty()) return@withContext null
        dao.findByGtin(candidates.first())?.toDomain()
            ?: dao.findByAnyGtin(candidates)?.toDomain()
    }

    suspend fun findByCode(code: String): SuklMedication? = withContext(Dispatchers.IO) {
        val padded = code.padStart(7, '0')
        dao.findByCode(padded)?.toDomain()
    }

    suspend fun search(query: String, limit: Int = 15): List<SuklMedication> =
        withContext(Dispatchers.IO) {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return@withContext emptyList()
            dao.searchCatalog(
                like = "%$trimmed%",
                prefix = "$trimmed%",
                exact = trimmed.padStart(7, '0'),
                limit = limit,
            ).map { it.toDomain() }
        }

    suspend fun count(): Int = withContext(Dispatchers.IO) { dao.count() }

    private fun SuklMedicationEntity.toDomain() = SuklMedication(
        suklCode = suklCode,
        name = name,
        gtin = gtin,
        activeSubstance = activeSubstance,
        atcCode = atcCode,
        strength = strength,
        formName = formName,
        holder = holder,
    )
}
