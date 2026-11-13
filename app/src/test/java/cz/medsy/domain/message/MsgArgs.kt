package cz.medsy.domain.message

/**
 * Renders an argument the way `asText` would, so a test does not care whether the producer passed
 * a string or a number: both end up in the same sentence.
 */
fun MessageArg.plain(): String = when (this) {
    is MessageArg.Text -> value
    is MessageArg.Number -> value.toString()
    is MessageArg.Nested -> message.key.name
}

/** The rendered arguments of a message, in order. */
fun Message.plainArgs(): List<String> = args.map { it.plain() }
