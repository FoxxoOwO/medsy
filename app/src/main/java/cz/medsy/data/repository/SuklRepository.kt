package cz.medsy.data.repository

import cz.medsy.data.local.dao.SuklDao
import cz.medsy.data.local.entity.SuklMedicationEntity
import cz.medsy.data.remote.SuklConfig
import cz.medsy.data.remote.SuklCsvParser
import cz.medsy.data.remote.SuklDlpDataset
import cz.medsy.domain.model.SuklMedication
import cz.medsy.domain.scanner.Gtin
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
     *
     * As of the 2026-07 and later DLP releases the `EAN` column exists but is blank on every
     * row; SÚKL's own data-interface sheet (DLP_datove_rozhrani, column 35) says "Pole je prázdné,
     * údaje již nejsou k dispozici" — the barcode data is no longer published. No other SÚKL open
     * dataset carries a GTIN either (`dlp_slozeni` has only substances, the packaging dataset is
     * a zip of PDFs), and no free Czech GTIN↔SÚKL registry exists. So this lookup normally misses
     * and the mapping is learned instead: [cz.medsy.ui.scanner.ScannerViewModel.save] stores the
     * scanned GTIN on the medication, and `DoseRepository.findByGtin` finds it on the next scan.
     */
    suspend fun findByGtin(gtin: String): SuklMedication? = withContext(Dispatchers.IO) {
        val candidates = Gtin.variantsOf(gtin)
        dao.findByGtin(candidates.first())?.toDomain()
            ?: dao.findByAnyGtin(candidates)?.toDomain()
    }

    /**
     * Downloads and ingests the SÚKL DLP dataset. Returns the number of ingested records.
     * A failed download leaves the existing local copy untouched.
     */
    suspend fun sync(): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val csv = downloadDataset()
                val parsed = SuklCsvParser.parse(csv)
                if (parsed.isEmpty()) error("SÚKL CSV contained no parsable rows")
                ingest(parsed)
                parsed.size
            }
        }

    /**
     * The catalog page is re-read on every sync because SÚKL publishes a new date-stamped zip
     * each month; the archive itself then has to be unpacked to reach the medicines table.
     */
    private fun downloadDataset(): String {
        val catalog = download(SuklConfig.DLP_CATALOG_URL)
        val archiveUrl = SuklDlpDataset.zipUrl(catalog)
            ?: error("SÚKL catalog listed no DLP archive")
        val archive = downloadBytes(archiveUrl)
        return SuklDlpDataset.medicinesCsv(archive)
            ?: error("SÚKL archive contained no ${SuklConfig.DLP_CSV_ENTRY}")
    }

    private suspend fun ingest(items: List<SuklMedication>) {
        val entities = items.map { it.toEntity() }
        entities.chunked(INSERT_CHUNK).forEach { chunk -> dao.upsertAll(chunk) }
    }

    private fun download(url: String): String =
        downloadBytes(url).toString(Charsets.UTF_8)

    private fun downloadBytes(url: String): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 120_000
            setRequestProperty("Accept", "*/*")
            setRequestProperty("User-Agent", "Medsy/1.0 (+offline-first medication tracker)")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) error("SÚKL sync failed with HTTP $status")
            return connection.inputStream.use { it.readBytes() }
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
