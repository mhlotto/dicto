package com.mhlotto.dicto.ui

object DictationCommandFormatter {
    const val DEFAULT_TRIGGER_PHRASE = "zeta"

    fun format(
        text: String,
        triggerPhrase: String = DEFAULT_TRIGGER_PHRASE,
    ): String {
        if (text.isBlank()) return text
        val tokens = text.trim().split(Regex("\\s+"))
        val triggerWords = triggerPhrase.normalizedTriggerWords()
            .ifEmpty { DEFAULT_TRIGGER_PHRASE.normalizedTriggerWords() }
        val builder = StringBuilder()
        var index = 0

        while (index < tokens.size) {
            val token = tokens[index]
            if (isCommandPrefix(tokens, index, triggerWords)) {
                val replacementStart = index + triggerWords.size
                val deleteCount = deleteCountFor(tokens.getOrNull(replacementStart), tokens.getOrNull(replacementStart + 1))
                if (deleteCount != null) {
                    builder.deletePreviousWords(deleteCount)
                    index += triggerWords.size + 2
                    continue
                }

                val twoWordReplacement = replacementFor(
                    tokens.getOrNull(replacementStart),
                    tokens.getOrNull(replacementStart + 1),
                )
                if (twoWordReplacement != null) {
                    builder.appendFormatted(twoWordReplacement)
                    index += triggerWords.size + 2
                    continue
                }

                val oneWordReplacement = replacementFor(tokens.getOrNull(replacementStart))
                if (oneWordReplacement != null) {
                    builder.appendFormatted(oneWordReplacement)
                    index += triggerWords.size + 1
                    continue
                }
            }

            builder.appendFormatted(token)
            index += 1
        }

        return builder.toString()
    }

    private fun isCommandPrefix(
        tokens: List<String>,
        index: Int,
        triggerWords: List<String>,
    ): Boolean {
        return triggerWords.withIndex().all { (offset, triggerWord) ->
            tokens.getOrNull(index + offset).normalizedCommandTokenOrEmpty() == triggerWord
        }
    }

    private fun replacementFor(first: String?, second: String? = null): String? {
        val command = listOfNotNull(first?.normalizedCommandToken(), second?.normalizedCommandToken())
            .joinToString(" ")
        return replacements[command]
    }

    private fun deleteCountFor(command: String?, count: String?): Int? {
        if (command.normalizedCommandTokenOrEmpty() != "delete") return null
        return count?.normalizedCommandToken()?.let { normalized ->
            normalized.toIntOrNull() ?: numberWords[normalized]
        }
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

    private fun StringBuilder.deletePreviousWords(wordCount: Int) {
        var remaining = wordCount.coerceAtLeast(0)
        while (remaining > 0) {
            trimTrailingWhitespace()
            trimTrailingPunctuation()
            trimTrailingWhitespace()
            if (isEmpty()) return

            val end = length
            while (isNotEmpty() && last().isWordChar()) {
                deleteCharAt(lastIndex)
            }
            if (length == end) return

            trimTrailingWhitespace()
            remaining -= 1
        }
    }

    private fun StringBuilder.trimTrailingSpaces() {
        while (isNotEmpty() && last() == ' ') {
            deleteCharAt(lastIndex)
        }
    }

    private fun StringBuilder.trimTrailingWhitespace() {
        while (isNotEmpty() && last().isWhitespace()) {
            deleteCharAt(lastIndex)
        }
    }

    private fun StringBuilder.trimTrailingPunctuation() {
        while (isNotEmpty() && last() in trailingPunctuation) {
            deleteCharAt(lastIndex)
        }
    }

    private fun Char.isWordChar(): Boolean {
        return isLetterOrDigit() || this == '\''
    }

    private fun String.normalizedCommandToken(): String {
        return lowercase().trim { !it.isLetterOrDigit() }
    }

    private fun String?.normalizedCommandTokenOrEmpty(): String {
        return this?.normalizedCommandToken().orEmpty()
    }

    private fun String.normalizedTriggerWords(): List<String> {
        return trim()
            .split(Regex("\\s+"))
            .map { it.normalizedCommandToken() }
            .filter { it.isNotBlank() }
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

    private val numberWords = mapOf(
        "zero" to 0,
        "one" to 1,
        "won" to 1,
        "two" to 2,
        "to" to 2,
        "too" to 2,
        "three" to 3,
        "four" to 4,
        "for" to 4,
        "fore" to 4,
        "five" to 5,
        "six" to 6,
        "sex" to 6,
        "seven" to 7,
        "eight" to 8,
        "nine" to 9,
        "ten" to 10,
        "tin" to 10,
        "eleven" to 11,
        "twelve" to 12,
        "thirteen" to 13,
        "fourteen" to 14,
        "fifteen" to 15,
        "sixteen" to 16,
        "seventeen" to 17,
        "eighteen" to 18,
        "nineteen" to 19,
        "twenty" to 20,
    )

    private val trailingPunctuation = setOf('.', '?', '!', ':', ';', ',', '-', '"', '\'')
}
