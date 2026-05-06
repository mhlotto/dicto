package com.mhlotto.dicto.ui

object DictationCommandFormatter {
    private const val KEYWORD_FIRST = "dictate"
    private const val KEYWORD_SECOND = "replace"

    fun format(text: String): String {
        if (text.isBlank()) return text
        val tokens = text.trim().split(Regex("\\s+"))
        val builder = StringBuilder()
        var index = 0

        while (index < tokens.size) {
            val token = tokens[index]
            if (isCommandPrefix(tokens, index)) {
                val twoWordReplacement = replacementFor(tokens.getOrNull(index + 2), tokens.getOrNull(index + 3))
                if (twoWordReplacement != null) {
                    builder.appendFormatted(twoWordReplacement)
                    index += 4
                    continue
                }

                val oneWordReplacement = replacementFor(tokens.getOrNull(index + 2))
                if (oneWordReplacement != null) {
                    builder.appendFormatted(oneWordReplacement)
                    index += 3
                    continue
                }
            }

            builder.appendFormatted(token)
            index += 1
        }

        return builder.toString()
    }

    private fun isCommandPrefix(tokens: List<String>, index: Int): Boolean {
        return tokens.getOrNull(index).normalizedCommandTokenOrEmpty() == KEYWORD_FIRST &&
            tokens.getOrNull(index + 1).normalizedCommandTokenOrEmpty() == KEYWORD_SECOND
    }

    private fun replacementFor(first: String?, second: String? = null): String? {
        val command = listOfNotNull(first?.normalizedCommandToken(), second?.normalizedCommandToken())
            .joinToString(" ")
        return replacements[command]
    }

    private fun StringBuilder.appendFormatted(segment: String) {
        when (segment) {
            "\n" -> {
                trimTrailingSpaces()
                append('\n')
            }
            ".", "?", "!" -> {
                trimTrailingSpaces()
                append(segment)
            }
            ":", "-" -> {
                trimTrailingSpaces()
                append(segment)
            }
            else -> {
                if (segment != "\t" && isNotEmpty() && last() != '\n') append(' ')
                append(segment)
            }
        }
    }

    private fun StringBuilder.trimTrailingSpaces() {
        while (isNotEmpty() && last() == ' ') {
            deleteCharAt(lastIndex)
        }
    }

    private fun String.normalizedCommandToken(): String {
        return lowercase().trim { !it.isLetterOrDigit() }
    }

    private fun String?.normalizedCommandTokenOrEmpty(): String {
        return this?.normalizedCommandToken().orEmpty()
    }

    private val replacements = mapOf(
        "new line" to "\n",
        "newline" to "\n",
        "period" to ".",
        "question" to "?",
        "question mark" to "?",
        "exclamation" to "!",
        "exclamation point" to "!",
        "colon" to ":",
        "dash" to "-",
        "tab" to "\t",
    )
}
