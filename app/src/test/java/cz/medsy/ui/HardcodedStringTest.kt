package cz.medsy.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression guard for F8: a label written straight into a composable cannot be translated.
 * Preview literals are ignored on purpose — sample data is not UI copy.
 *
 * Templates count too. `"${'$'}{count} days left"` is UI copy that happens to contain a value,
 * so it belongs in a formatted resource (or a `<plurals>` entry). Pure formatting — `"$percent%"`,
 * `"${'$'}{count}"`, `"• $error"` — has no words to translate and may stay in Kotlin.
 */
class HardcodedStringTest {

    private val uiDir = listOf(
        File("src/main/java/cz/medsy/ui"),
        File("app/src/main/java/cz/medsy/ui"),
    ).first { it.exists() }

    private val hardcoded = listOf(
        Regex("Text\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\""),
        Regex("contentDescription = \"((?:[^\"\\\\]|\\\\.)*)\""),
        // This project writes most copy as `text = "…"`, not `Text("…")`, and an earlier
        // version of this guard missed every one of those.
        Regex("text = \"((?:[^\"\\\\]|\\\\.)*)\""),
        Regex("else \"((?:[^\"\\\\]|\\\\.)*)\""),
    )

    private val placeholder = Regex("\\$\\{[^}]*}|\\$[A-Za-z_]\\w*")

    /** Two or more letters outside the placeholders means the literal is UI copy. */
    private fun spellsWords(literal: String): Boolean =
        literal.replace(placeholder, "").count { it.isLetter() } >= 2

    /** Returns "file:line: literal" for every hardcoded label outside @Preview. */
    private fun violations(name: String, source: String): List<String> {
        val found = mutableListOf<String>()
        var inPreview = false
        var depth = 0
        source.lineSequence().forEachIndexed { index, line ->
            if (line.contains("@Preview")) inPreview = true
            if (inPreview) {
                depth += line.count { it == '{' } - line.count { it == '}' }
                if (depth <= 0 && line.contains('}')) inPreview = false
                return@forEachIndexed
            }
            hardcoded.forEach { pattern ->
                pattern.findAll(line).forEach { match ->
                    val literal = match.groupValues[1]
                    if (literal.length > 1 && spellsWords(literal)) {
                        found += "$name:${index + 1}: \"$literal\""
                    }
                }
            }
        }
        return found
    }

    private fun uiFiles(): List<File> = uiDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `no hardcoded labels outside previews`() {
        val offenders = uiFiles().flatMap { violations(it.name, it.readText()) }

        assertTrue(
            "hardcoded strings (wrap them in stringResource): \n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the scanner actually detects a hardcoded label`() {
        val sample = """
            @Composable
            fun Example() {
                Text("Still English")
            }
        """.trimIndent()

        assertEquals(listOf("Sample.kt:3: \"Still English\""), violations("Sample.kt", sample))
    }

    @Test
    fun `the scanner detects a template with words`() {
        val sample = """
            @Composable
            fun Example() {
                Text(
                    text = "PLACEHOLDER result(s)",
                )
            }
        """.trimIndent().replace("PLACEHOLDER", "\$" + "{rows.size}")
        val expected = "Sample.kt:4: \"\$" + "{rows.size} result(s)\""

        assertEquals(listOf(expected), violations("Sample.kt", sample))
    }

    @Test
    fun `pure formatting templates are allowed`() {
        val sample = """
            @Composable
            fun Example() {
                Text(text = "PLACEHOLDER%")
                Text(text = "• PLACEHOLDER")
            }
        """.trimIndent().replace("PLACEHOLDER", "\$" + "percent")

        assertTrue(violations("Sample.kt", sample).isEmpty())
    }

    @Test
    fun `preview literals are allowed`() {
        val sample = """
            @Preview
            @Composable
            private fun Preview() {
                Text("Sample data")
            }
        """.trimIndent()

        assertTrue(violations("Sample.kt", sample).isEmpty())
    }

    @Test
    fun `stringResource calls are not flagged`() {
        val sample = """
            @Composable
            fun Example() {
                Text(stringResource(R.string.common_cancel))
            }
        """.trimIndent()

        assertTrue(violations("Sample.kt", sample).isEmpty())
    }
}
