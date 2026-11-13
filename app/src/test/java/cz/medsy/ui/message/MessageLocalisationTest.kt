package cz.medsy.ui.message

import cz.medsy.domain.message.MessageKey
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the seam between domain sentences and resources.
 *
 * The domain cannot call `stringResource`, so every user-visible sentence produced in a
 * ViewModel, a domain object or a worker goes through [cz.medsy.domain.message.Message]. These
 * checks keep the mapping complete: a key without a resource, or a resolver with an `else`
 * branch, would put English back on a Czech phone without failing any screen test.
 */
class MessageLocalisationTest {

    private val resolverSource: String by lazy {
        File("src/main/java/cz/medsy/ui/message/MessageText.kt").readText()
    }

    @Test
    fun `every message key maps to a resource`() {
        val missing = MessageKey.entries.filter { key ->
            val res = key.res()
            res.string == 0 && res.plural == 0
        }
        assertTrue("keys without a resource: $missing", missing.isEmpty())
    }

    @Test
    fun `plural keys use plurals and the rest use strings`() {
        val wrong = MessageKey.entries.filter { key ->
            val expectedPlural = key in PLURAL_KEYS
            val line = resolverSource.lineSequence()
                .firstOrNull { it.contains("MessageKey.$key ->") }
                ?: return@filter true
            if (expectedPlural) {
                !line.contains("R.plurals.")
            } else {
                !line.contains("R.string.")
            }
        }
        assertTrue("keys mapped to the wrong resource kind: $wrong", wrong.isEmpty())
    }

    @Test
    fun `the resolver has no fallback that could swallow a new key`() {
        assertTrue(
            "an else branch would silently hide a missing key",
            !resolverSource.contains("else ->"),
        )
    }

    @Test
    fun `no screen field is assigned an English sentence`() {
        val offenders = ArrayList<String>()
        File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("*") || trimmed.startsWith("//")) return@forEachIndexed
                    if (ASSIGNMENT.containsMatchIn(line)) {
                        offenders += "${file.path}:${index + 1}: $trimmed"
                    }
                }
            }
        assertTrue("build a Message instead of a sentence: $offenders", offenders.isEmpty())
    }

    @Test
    fun `no domain object carries an English phrase`() {
        // Domain classes cannot reach resources, so a phrase stored in a field is English
        // forever — it survives a language switch and never reaches a translator.
        val offenders = ArrayList<String>()
        for (dir in listOf("src/main/java/cz/medsy/domain", "src/main/java/cz/medsy/data")) {
            File(dir).walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    file.readLines().forEachIndexed { index, line ->
                        val trimmed = line.trim()
                        if (trimmed.startsWith("*") || trimmed.startsWith("//")) return@forEachIndexed
                        if (PROSE_FIELD.containsMatchIn(line) || PROSE_BRANCH.containsMatchIn(line)) {
                            offenders += "${file.path}:${index + 1}: $trimmed"
                        }
                    }
                }
        }
        assertTrue("store a Message instead of a phrase: $offenders", offenders.isEmpty())
    }

    @Test
    fun `no ViewModel falls back to an English sentence`() {
        val offenders = ArrayList<String>()
        File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    if (ENGLISH_FALLBACK.containsMatchIn(line)) {
                        offenders += "${file.path}:${index + 1}: ${line.trim()}"
                    }
                }
            }
        assertTrue(
            "a failed save must be reported as a Message, not as English prose: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `no composable passes an English sentence as a named parameter`() {
        // `title = "…"`, `subtitle = "…"`, `label = "…"` hide just as well as `Text("…")`:
        // the literal is not the first argument, so the `Text("…"` scan never sees it.
        val offenders = ArrayList<String>()
        for (dir in listOf("src/main/java/cz/medsy/ui", "src/main/java/cz/medsy/work")) {
            File(dir).walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    file.readLines().forEachIndexed { index, line ->
                        val trimmed = line.trim()
                        if (trimmed.startsWith("*") || trimmed.startsWith("//")) return@forEachIndexed
                        if (NAMED_PARAMETER.containsMatchIn(line)) {
                            offenders += "${file.path}:${index + 1}: $trimmed"
                        }
                    }
                }
        }
        assertTrue("pass a string resource instead of a sentence: $offenders", offenders.isEmpty())
    }

    @Test
    fun `no validator builds an English error`() {
        // `errors += "Dose must be …"` renders straight into the error card: the scheduler
        // editor shipped English until this check existed, because neither the screen scan
        // (it looks at `text =`) nor the domain scan (that class lives in `ui/`) saw it.
        val offenders = ArrayList<String>()
        File("src/main/java/cz/medsy/ui").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    if (ERROR_LITERAL.containsMatchIn(line)) {
                        offenders += "${file.path}:${index + 1}: ${line.trim()}"
                    }
                }
            }
        assertTrue("build a Message instead: $offenders", offenders.isEmpty())
    }

    @Test
    fun `navigation labels come from resources`() {
        // The bottom bar was the last surface that kept its labels in Kotlin, so the Czech
        // build showed "Home | Meds | Labs" under a Czech dashboard.
        val source = File("src/main/java/cz/medsy/ui/MedsyApp.kt").readText()
        assertTrue(
            "the destination enum must carry a @StringRes label",
            source.contains("val label: Int") && !source.contains("val label: String"),
        )
        val missing = Regex("""R\.string\.(nav_[a-z_]+)""").findAll(source)
            .map { it.groupValues[1] }
            .filter { key ->
                !File("src/main/res/values-cs/strings.xml").readText().contains("name=\"$key\"")
            }
            .toList()
        assertTrue("nav labels without a Czech translation: $missing", missing.isEmpty())
    }

    @Test
    fun `no screen branches or falls back to an English sentence`() {
        // Two shortcuts the earlier scans missed: `else -> "Good evening"` in a ViewModel and
        // `?: "No results yet"` behind a nullable value. Both rendered English on a Czech
        // phone while every `Text("…")` was already a resource.
        val offenders = ArrayList<String>()
        File("src/main/java/cz/medsy/ui").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("*") || trimmed.startsWith("//")) return@forEachIndexed
                    if (PROSE_BRANCH.containsMatchIn(line) || ELVIS_SENTENCE.containsMatchIn(line)) {
                        offenders += "${file.path}:${index + 1}: $trimmed"
                    }
                }
            }
        assertTrue("build a Message or a resource instead: $offenders", offenders.isEmpty())
    }

    @Test
    fun `no screen glues an English sentence around a value`() {
        // `error = "Camera unavailable: ${it.message}"` is a sentence with a value in the middle:
        // neither the `Text("…")` scan (the literal is not passed to Text) nor the branch scan
        // (there is no arrow) sees it. After the placeholders are removed, two English words
        // remain — that is the signal.
        val quoted = Regex(""""([^"]*)"""")
        // Both `$value` and `${expression}` count; `%1$s` inside a resource string does not.
        val placeholder = Regex("""\$\{[^}]*\}|\$[A-Za-z_][A-Za-z0-9_]*""")
        val word = Regex("""^[A-Za-z]{2,}$""")
        val offenders = ArrayList<String>()
        File("src/main/java/cz/medsy/ui").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("*") || trimmed.startsWith("//")) return@forEachIndexed
                    quoted.findAll(line).forEach { match ->
                        val literal = match.groupValues[1]
                        if (!literal.contains('$')) return@forEach
                        val rest = placeholder.replace(literal, " ")
                        val words = rest.split(' ', '	', ',', ':', '·').filter { word.matches(it) }
                        if (words.size >= 2) offenders += "${file.path}:${index + 1}: $literal"
                    }
                }
            }
        assertTrue("describe the failure with a Message key: $offenders", offenders.isEmpty())
    }

    @Test
    fun `no UI file continues an English sentence across lines`() {
        // Multi-line concatenations are invisible to the `Text("…"` scan: the literal starts
        // on a continuation line, which looks like any other string.
        val offenders = ArrayList<String>()
        for (dir in listOf("src/main/java/cz/medsy/ui", "src/main/java/cz/medsy/work")) {
            File(dir).walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    file.readLines().forEachIndexed { index, line ->
                        // Only the literal is judged: a line like
                        // `" — " + warnings.first().detail.asText()` is punctuation, not prose.
                        val literal = LEADING_LITERAL.find(line)?.groupValues?.get(1)
                            ?: return@forEachIndexed
                        if (!literal.contains(' ')) return@forEachIndexed
                        // A sentence has at least two words: one word is usually an enum name,
                        // a route or preview sample data ("Progesterone").
                        if (literal.count { it.isLetter() } >= 3) {
                            offenders += "${file.path}:${index + 1}: ${line.trim()}"
                        }
                    }
                }
        }
        assertTrue("continue with stringResource instead: $offenders", offenders.isEmpty())
    }

    internal companion object {

        /**
         * `error.message ?: "…"` was the shortcut that leaked English into snackbars; the
         * typed `message` field makes it unnecessary everywhere.
         */
        val ENGLISH_FALLBACK = Regex("""error\.message\s*\?:\s*"[A-Za-z]""")

        /** A prose field: `reason = "…"`, `warnings += "…"`, `detail = "…"`. */
        val PROSE_FIELD = Regex(
            """(label|reason|detail|suggestion|message|errors|warnings|title|subtitle|description)""" +
                """\s*(\+=|=)\s*"[^"]*\s[^"]*"""",
        )

        /** `errors += "…"`, `errors.add("…")`, `errors = listOf("…")`. */
        val ERROR_LITERAL = Regex(
            """(errors|warnings|messageErrors)[^\n]*(\+=|\.add\(|=\s*listOf\()\s*"[^"]*"""",
        )

        /** `value ?: "Two words"` — a fallback English sentence. */
        val ELVIS_SENTENCE = Regex("""\?:\s*"[A-Za-z][^"]*\s[^"]*"""")
        // The subject may be a comparison or a call (`chosen == null -> "…"`, `kind(x.isVial) -> "…"`),
        // so it is anything up to the arrow — restricting it to a bare identifier let several
        // English branches through for months.
        val PROSE_BRANCH = Regex("""^\s*\S.*?\s*->\s*"[A-Za-z][^"]*\s[^"]*",?$""")

        /**
         * A named parameter that renders on screen and holds a literal with at least two
         * words — one word is usually an enum name, a route or sample data.
         */
        val NAMED_PARAMETER = Regex(
            """(title|subtitle|label|headline|placeholder|description|summary)\s*=\s*"[^"]*\s[^"]*"""",
        )

        /** The literal that opens a continuation line. */
        val LEADING_LITERAL = Regex("^\\s*\"([^\"\\\\]*)\"")

        /** Properties that end up on screen or in a notification. */
        val ASSIGNMENT = Regex("""(statusMessage|reminderMessage)\s*=\s*"""")

        /**
         * Keys backed by `<plurals>`. Listed explicitly because the resource kind cannot be
         * introspected from an id in a unit test.
         */
        internal val PLURAL_KEYS = setOf(
            MessageKey.BODYMAP_AGE_DAYS,
            MessageKey.BACKUP_RESTORED,
            MessageKey.SCAN_RX_SAVED,
            MessageKey.RX_EXPIRES_IN_DAYS,
            MessageKey.RX_REMINDER_MORE,
            MessageKey.SCHEDULE_EVERY_DAYS,
            MessageKey.SCHEDULE_EVERY_HOURS,
            MessageKey.NOTIFY_LOW_STOCK,
            MessageKey.UV_EXPOSURE_MANY,
            MessageKey.INVENTORY_ENOUGH_DAYS,
        )
    }
}
