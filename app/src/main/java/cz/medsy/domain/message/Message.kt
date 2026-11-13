package cz.medsy.domain.message

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A sentence the domain and the ViewModels can describe without knowing the user's language.
 *
 * Everything user-visible that is not built inside a composable comes through here: the scanner
 * status line, the eRecept state, milestone labels, the patch card, the PRN limit prompt and the
 * notifications. [cz.medsy.ui.message.asText] turns the key and the arguments into text, so a
 * Czech phone reads Czech even though the sentence was composed in `domain/`.
 *
 * Arguments are [MessageArg]; a nested [Message] is rendered first, so "Started 5 mg daily" is
 * two keys rather than one string glued together in Kotlin.
 */
@Serializable
data class Message(
    val key: MessageKey,
    val args: List<MessageArg> = emptyList(),
    /** Set only for keys backed by a `<plurals>` entry. */
    val count: Int? = null,
)

@Serializable
sealed interface MessageArg {

    @Serializable
    @SerialName("text")
    data class Text(val value: String) : MessageArg

    /**
     * A number stays a number. Formatting it into text first would break every `%1$d` resource —
     * `getQuantityString` throws `IllegalFormatConversionException` when a `d` slot receives a
     * String, and plurals are exactly where the count is most often needed twice.
     */
    @Serializable
    @SerialName("number")
    data class Number(val value: Long) : MessageArg

    @Serializable
    @SerialName("message")
    data class Nested(val message: Message) : MessageArg
}

/** Builds a [Message] from mixed arguments: numbers stay numeric, [Message]s stay nested. */
fun message(key: MessageKey, vararg args: Any): Message = Message(
    key = key,
    args = args.map { arg ->
        when (arg) {
            is Message -> MessageArg.Nested(arg)
            is Int -> MessageArg.Number(arg.toLong())
            is Long -> MessageArg.Number(arg)
            is Double -> MessageArg.Text(arg.toString())
            else -> MessageArg.Text(arg.toString())
        }
    },
)

/** Builds a plural [Message]; [count] selects the grammatical form. */
fun messagePlural(key: MessageKey, count: Int, vararg args: Any): Message =
    message(key, *args).copy(count = count)

/**
 * Milestones are written once and read many times, so the key survives a change of language.
 * Legacy rows hold a plain English sentence and decode to null, which the UI shows as it is.
 */
object MessageCodec {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun encode(message: Message): String = json.encodeToString(Message.serializer(), message)

    fun decode(raw: String?): Message? {
        if (raw.isNullOrBlank() || !raw.trimStart().startsWith("{")) return null
        return runCatching { json.decodeFromString(Message.serializer(), raw) }.getOrNull()
    }
}
