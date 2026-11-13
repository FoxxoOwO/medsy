package cz.medsy.domain.calc

import cz.medsy.domain.message.plain
import cz.medsy.domain.message.plainArgs

import cz.medsy.domain.model.Medication
import cz.medsy.domain.model.MedicationForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotosensitivityTest {

    private fun argsOf(message: cz.medsy.domain.message.Message): List<String> =
        message.plainArgs()

    private fun med(name: String, atc: String?) = Medication(
        id = name.hashCode().toLong(),
        name = name,
        atcCode = atc,
        form = MedicationForm.TABLET,
    )

    @Test
    fun `high UV with a photosensitising medication warns`() {
        val warning = Photosensitivity.uvWarning(7.0, listOf(med("Doxycycline", "J01AA02")))

        assertTrue(warning != null)
        assertEquals(7.0, warning!!.uvIndex, 0.001)
        assertEquals(listOf("Doxycycline"), warning.medications)
        val message = warning.message
        assertEquals(cz.medsy.domain.message.MessageKey.UV_EXPOSURE_ONE, message.key)
        assertEquals("7.0", argsOf(message).first())
    }

    @Test
    fun `UV 5 raises nothing even with a photosensitising medication`() {
        assertNull(Photosensitivity.uvWarning(5.0, listOf(med("Doxycycline", "J01AA02"))))
    }

    @Test
    fun `UV right at the threshold raises nothing`() {
        assertNull(Photosensitivity.uvWarning(Photosensitivity.UV_THRESHOLD, listOf(med("Doxycycline", "J01AA02"))))
    }

    @Test
    fun `high UV with an ordinary medication raises nothing`() {
        assertNull(Photosensitivity.uvWarning(9.0, listOf(med("Estradiol", "G03CA03"))))
    }

    @Test
    fun `no reading means no warning`() {
        assertNull(Photosensitivity.uvWarning(null, listOf(med("Doxycycline", "J01AA02"))))
    }

    @Test
    fun `a medication without an ATC code never triggers`() {
        assertNull(Photosensitivity.uvWarning(8.0, listOf(med("Unlabelled", null))))
    }

    @Test
    fun `atc matching ignores case and spaces`() {
        assertTrue(Photosensitivity.isPhotosensitising(" j01aa02 "))
        assertTrue(Photosensitivity.isPhotosensitising("d10ba01"))
        assertFalse(Photosensitivity.isPhotosensitising("G03CA03"))
    }

    @Test
    fun `every photosensitising group is recognised`() {
        listOf("J01AA02", "J01EE01", "J01MA02", "D10BA01", "D10AD01", "N05AA01", "C01BD01", "C03AA03", "M01AE01")
            .forEach { code ->
                assertTrue("$code should be photosensitising", Photosensitivity.isPhotosensitising(code))
            }
    }

    @Test
    fun `duplicate medications are named once`() {
        val warning = Photosensitivity.uvWarning(
            8.1,
            listOf(med("Doxycycline", "J01AA02"), med("Doxycycline", "J01AA02")),
        )

        assertEquals(listOf("Doxycycline"), warning!!.medications)
        val message = warning.message
        assertEquals(cz.medsy.domain.message.MessageKey.UV_EXPOSURE_ONE, message.key)
        assertEquals(listOf("8.1", "Doxycycline"), argsOf(message))
    }

    @Test
    fun `three or more medications are summarised by count`() {
        val warning = Photosensitivity.uvWarning(
            9.0,
            listOf(med("A", "J01AA02"), med("B", "M01AE01"), med("C", "C03AA03")),
        )

        val message = warning!!.message
        // Count-based, because three trade names would not fit on the card.
        assertEquals(cz.medsy.domain.message.MessageKey.UV_EXPOSURE_MANY, message.key)
        assertEquals(3, message.count)
        assertEquals("9.0", argsOf(message).first())
    }
}
