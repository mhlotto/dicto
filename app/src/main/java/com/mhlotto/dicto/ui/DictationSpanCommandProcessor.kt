package com.mhlotto.dicto.ui

class DictationSpanCommandProcessor(
    private val numberParser: SpokenNumberParser = SpokenNumberParser(),
) {
    fun process(
        text: String,
        triggerPhrase: String = DictationCommandFormatter.DEFAULT_TRIGGER_PHRASE,
    ): String {
        if (text.isBlank()) return text
        val tokens = text.trim().split(Regex("\\s+"))
        val triggerWords = triggerPhrase.normalizedTriggerWords()
            .ifEmpty { DictationCommandFormatter.DEFAULT_TRIGGER_PHRASE.normalizedTriggerWords() }
        val output = mutableListOf<String>()
        var index = 0

        while (index < tokens.size) {
            val span = parseSpan(tokens, index, triggerWords)
            if (span != null) {
                output += span.replacement
                index = span.nextIndex
            } else {
                output += tokens[index]
                index += 1
            }
        }

        return output.joinToString(" ")
    }

    private fun parseSpan(
        tokens: List<String>,
        startIndex: Int,
        triggerWords: List<String>,
    ): ParsedSpan? {
        if (!tokens.hasTriggerAt(startIndex, triggerWords)) return null
        val commandIndex = startIndex + triggerWords.size
        if (tokens.getOrNull(commandIndex).normalizedCommandTokenOrEmpty() != NUMBER_COMMAND) return null

        val payloadStart = commandIndex + 1
        val endTriggerStart = findEndTrigger(tokens, payloadStart, triggerWords) ?: return null
        val endTokenIndex = endTriggerStart + triggerWords.size
        if (tokens.getOrNull(endTokenIndex).normalizedCommandTokenOrEmpty() != END_MARKER) return null

        val payload = tokens.subList(payloadStart, endTriggerStart)
        val number = numberParser.parse(payload) ?: return null
        return ParsedSpan(replacement = number, nextIndex = endTokenIndex + 1)
    }

    private fun findEndTrigger(
        tokens: List<String>,
        fromIndex: Int,
        triggerWords: List<String>,
    ): Int? {
        var index = fromIndex
        while (index < tokens.size) {
            if (tokens.hasTriggerAt(index, triggerWords)) return index
            index += 1
        }
        return null
    }

    private fun List<String>.hasTriggerAt(index: Int, triggerWords: List<String>): Boolean {
        return triggerWords.withIndex().all { (offset, triggerWord) ->
            getOrNull(index + offset).normalizedCommandTokenOrEmpty() == triggerWord
        }
    }

    private fun String?.normalizedCommandTokenOrEmpty(): String {
        return this?.lowercase()?.trim { !it.isLetterOrDigit() }.orEmpty()
    }

    private fun String.normalizedTriggerWords(): List<String> {
        return trim()
            .split(Regex("\\s+"))
            .map { it.lowercase().trim { char -> !char.isLetterOrDigit() } }
            .filter { it.isNotBlank() }
    }

    private data class ParsedSpan(
        val replacement: String,
        val nextIndex: Int,
    )

    private companion object {
        const val NUMBER_COMMAND = "number"
        const val END_MARKER = "stop"
    }
}
