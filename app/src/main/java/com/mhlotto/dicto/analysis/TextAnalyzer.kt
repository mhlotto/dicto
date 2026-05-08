package com.mhlotto.dicto.analysis

interface TextAnalyzer {
    val id: String
    val displayName: String

    suspend fun analyze(text: String): TextAnalysisResult
}

data class TextAnalysisResult(
    val analyzerId: String,
    val entities: List<ExtractedEntity>,
    val rawTextLength: Int,
    val createdAt: Long,
)

data class ExtractedEntity(
    val type: String,
    val text: String,
    val start: Int,
    val end: Int,
    val metadata: Map<String, String> = emptyMap(),
)
