package cz.medsy.data.repository

import cz.medsy.data.local.dao.SuklDao
import cz.medsy.data.local.entity.SuklMedicationEntity
import cz.medsy.data.remote.SuklConfig
import cz.medsy.data.remote.SuklCsvParser
import cz.medsy.domain.model.SuklMedication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SuklRepository @Inject constructor(
    private val dao: SuklDao,
) {

    suspend fun search(query: String, limit: Int = 40): List<SuklMedication> =
        withContext(Dispatchers.IO) {
            dao.search(query, limit).map { it.toDomain() }
        }

    suspend fun findByCode(code: String): SuklMedication? = withContext(Dispatchers.IO) {
        dao.findByCode(code.padStart(7, '0'))?.toDomain()
    }

    suspend fun localCount(): Int = withContext(Dispatchers.IO) { dao.count() }

    /**
     * Looks up a package by its GS1 GTIN-14.
     *
     * A GTIN cannot be converted into a SÚKL code: the GTIN is assigned by the
     * manufacturer and the SÚKL code is a Czech registry number. The previous
     * implementation simply dropped three digits and treated the remainder as a
     * SÚKL code, which resolved to a **random, unrelated medication** whenever the
     * dataset happened to contain that number.
     *
     * Only an exact GTIN recorded in the dataset is accepted. The packaging indicator
     * (first digit, 0 = single unit, 1–8 = multipack) is ignored, because the same
     * product legitimately carries several GTINs.
     */
    suspend fun findByGtin(gtin: String): SuklMedication? = withContext(Dispatchers.IO) {
        val digits = gtin.filter(Char::isDigit)
        if (digits.isEmpty()) return@withContext null
        val normalized = digits.padStart(14, '0').takeLast(14)
        val candidates = packagingVariants(normalized)
        dao.findByGtin(normalized)?.toDomain()
            ?: dao.findByAnyGtin(candidates)?.toDomain()
    }

    /** Same GTIN with every legal packaging indicator (0–8). */
    private fun packagingVariants(gtin14: String): List<String> =
        (0..8).map { indicator -> "$indicator${gtin14.drop(1)}" }

    /**
     * Downloads and ingests the SÚKL CSV. Returns the number of ingested records.
     * A failed download leaves the existing local copy untouched.
     */
    suspend fun sync(url: String = SuklConfig.MEDICINES_CSV_URL): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val csv = download(url)
                val parsed = SuklCsvParser.parse(csv)
                if (parsed.isEmpty()) error("SÚKL CSV contained no parsable rows")
                ingest(parsed)
                parsed.size
            }
        }

    private suspend fun ingest(items: List<SuklMedication>) {
        val entities = items.map { it.toEntity() }
        entities.chunked(INSERT_CHUNK).forEach { chunk -> dao.upsertAll(chunk) }
    }

    private fun download(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("Accept", "text/csv, */*")
            setRequestProperty("User-Agent", "Medsy/1.0 (+offline-first medication tracker)")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) error("SÚKL sync failed with HTTP $status")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

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

    private fun SuklMedication.toEntity() = SuklMedicationEntity(
        suklCode = suklCode,
        gtin = gtin,
        name = name,
        activeSubstance = activeSubstance,
        atcCode = atcCode,
        strength = strength,
        formName = formName,
        holder = holder,
    )

    private companion object {
        const val INSERT_CHUNK = 500
    }
}
