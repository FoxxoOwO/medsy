package cz.medsy.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cz.medsy.data.local.MedsyDatabase
import cz.medsy.data.local.entity.MedicationEntity
import cz.medsy.data.remote.TranslationService
import cz.medsy.domain.model.MedicationForm
import cz.medsy.domain.model.Medication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal

/**
 * The cache is the feature: a translation costs a network round trip per field and the free service
 * quota is finite, so the repository must serve the stored copy until the text it was made from
 * changes, and must forget the copy when the user deletes it.
 */
@RunWith(RobolectricTestRunner::class)
class TranslationRepositoryTest {

    private lateinit var db: MedsyDatabase
    private lateinit var service: CountingTranslationService
    private lateinit var repository: TranslationRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(context, MedsyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        service = CountingTranslationService()
        repository = TranslationRepositoryImpl(db.translationDao(), service)
        // `medication_translations.medicationId` is a real foreign key.
        runBlocking {
            db.medicationDao().insert(
                MedicationEntity(id = 1, name = "Estradiol valerate", form = MedicationForm.INJECTION_IM.name),
            )
        }
    }

    @After
    fun tearDown() = db.close()

    /** Translates by tagging the request, so the assertions can tell the fields apart. */
    private class CountingTranslationService : TranslationService {
        var calls = 0
        var fail = false
        var failNotes = false

        override suspend fun translate(text: String, target: String): String? {
            calls++
            if (fail) return null
            if (failNotes && text.startsWith("Poznámka")) return null
            return "[$target] $text"
        }
    }

    private fun medication(
        name: String = "Estradiol valerate",
        substance: String? = "Estradiolum valeras",
        notes: String? = null,
    ) = Medication(
        id = 1,
        name = name,
        activeSubstance = substance,
        atcCode = "G03CA03",
        form = MedicationForm.INJECTION_IM,
        strengthValue = BigDecimal("5"),
        strengthUnit = "mg",
        concentrationMgPerMl = BigDecimal("5"),
        notes = notes,
    )

    @Test
    fun `the first translation is fetched and stored`() = runBlocking {
        val result = repository.translate(medication(), "en").getOrThrow()

        assertEquals("[en] Estradiol valerate", result.name)
        assertEquals("[en] Estradiolum valeras", result.activeSubstance)
        assertEquals("mymemory", result.engine)
        assertEquals(2, service.calls)
        assertEquals(1, repository.observeFor(1).first().size)
    }

    @Test
    fun `a second look is served from disk without a request`() = runBlocking {
        repository.translate(medication(), "en").getOrThrow()
        val callsAfterFirst = service.calls

        val again = repository.translate(medication(), "en").getOrThrow()

        assertEquals("[en] Estradiol valerate", again.name)
        assertEquals("no request when the cached text still matches", callsAfterFirst, service.calls)
    }

    @Test
    fun `editing the name invalidates the cached translation`() = runBlocking {
        repository.translate(medication(), "en").getOrThrow()
        val callsAfterFirst = service.calls

        val updated = repository.translate(medication(name = "Estradiol valerate 2 mg"), "en").getOrThrow()

        assertEquals("[en] Estradiol valerate 2 mg", updated.name)
        assertTrue("the edited text must be translated again", service.calls > callsAfterFirst)
        assertEquals(1, repository.observeFor(1).first().size)
    }

    @Test
    fun `a forced translation ignores the cache`() = runBlocking {
        repository.translate(medication(), "en").getOrThrow()
        val callsAfterFirst = service.calls

        repository.translate(medication(), "en", force = true).getOrThrow()

        assertTrue(service.calls > callsAfterFirst)
    }

    @Test
    fun `languages are cached separately`() = runBlocking {
        repository.translate(medication(), "en").getOrThrow()
        repository.translate(medication(), "de").getOrThrow()

        val stored = repository.observeFor(1).first().sortedBy { it.language }
        assertEquals(listOf("de", "en"), stored.map { it.language })
        assertEquals("[de] Estradiol valerate", stored.first().name)
    }

    @Test
    fun `removing a translation forgets it`() = runBlocking {
        repository.translate(medication(), "en").getOrThrow()

        repository.remove(1, "en")

        assertTrue(repository.observeFor(1).first().isEmpty())
    }

    @Test
    fun `a failed name translation is a failure and stores nothing`() = runBlocking {
        service.fail = true

        val result = repository.translate(medication(), "en")

        assertTrue(result.isFailure)
        assertTrue(repository.observeFor(1).first().isEmpty())
    }

    @Test
    fun `a failed notes translation still keeps the name`() = runBlocking {
        service.failNotes = true

        val result = repository.translate(medication(notes = "Poznámka: aplikuj ráno"), "en").getOrThrow()

        assertEquals("[en] Estradiol valerate", result.name)
        assertNull("the untranslatable notes must not fall back to Czech", result.notes)
    }

    @Test
    fun `the same text twice has one fingerprint and an edit changes it`() {
        assertEquals(
            TranslationRepositoryImpl.sourceHash(medication()),
            TranslationRepositoryImpl.sourceHash(medication()),
        )
        assertTrue(
            TranslationRepositoryImpl.sourceHash(medication()) !=
                TranslationRepositoryImpl.sourceHash(medication(name = "Jiný název")),
        )
    }

    @Test
    fun `deleting the medication deletes its translations`() = runBlocking {
        repository.translate(medication(), "en").getOrThrow()
        assertEquals(1, repository.observeFor(1).first().size)

        db.medicationDao().delete(1)

        assertTrue(
            "the cache must follow the medication through ON DELETE CASCADE",
            repository.observeFor(1).first().isEmpty(),
        )
    }
}
