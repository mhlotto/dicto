package com.mhlotto.dicto.ui

class SpokenNumberParser {
    fun parse(words: List<String>): String? {
        val normalized = words.map { it.normalizedNumberToken() }.filter { it.isNotBlank() }
        if (normalized.isEmpty()) return null

        parseDigitSequence(normalized)?.let { return it }
        parseComposedNumber(normalized)?.let { return it.toString() }
        return null
    }

    private fun parseDigitSequence(words: List<String>): String? {
        val digits = words.map { digitWords[it] ?: return null }
        return digits.joinToString("")
    }

    private fun parseComposedNumber(words: List<String>): Int? {
        if (words.size == 1) {
            return teenWords[words[0]] ?: tensWords[words[0]] ?: digitWords[words[0]]
        }

        if (words.size == 2) {
            val tens = tensWords[words[0]]
            val ones = digitWords[words[1]]
            if (tens != null && ones != null && ones > 0) return tens + ones
        }

        val hundredIndex = words.indexOf("hundred")
        if (hundredIndex == 1) {
            val hundreds = digitWords[words[0]]?.takeIf { it in 1..9 } ?: return null
            val rest = words.drop(2)
            val restValue = when {
                rest.isEmpty() -> 0
                rest.size <= 2 -> parseComposedNumber(rest) ?: return null
                else -> return null
            }
            if (restValue !in 0..99) return null
            return hundreds * 100 + restValue
        }

        return null
    }

    private fun String.normalizedNumberToken(): String {
        return lowercase().trim { !it.isLetterOrDigit() }
    }

    private companion object {
        val digitWords = mapOf(
            "zero" to 0,
            "oh" to 0,
            "o" to 0,
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
        )

        val teenWords = mapOf(
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
        )

        val tensWords = mapOf(
            "twenty" to 20,
            "thirty" to 30,
            "forty" to 40,
            "fourty" to 40,
            "fifty" to 50,
            "sixty" to 60,
            "seventy" to 70,
            "eighty" to 80,
            "ninety" to 90,
        )
    }
}
