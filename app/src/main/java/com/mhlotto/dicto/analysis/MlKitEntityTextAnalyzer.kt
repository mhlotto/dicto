package com.mhlotto.dicto.analysis

import com.google.android.gms.tasks.Task
import com.google.mlkit.nl.entityextraction.Entity
import com.google.mlkit.nl.entityextraction.EntityAnnotation
import com.google.mlkit.nl.entityextraction.EntityExtraction
import com.google.mlkit.nl.entityextraction.EntityExtractorOptions
import java.lang.reflect.Modifier
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class MlKitEntityTextAnalyzer : TextAnalyzer {
    override val id: String = "mlkit_entity_extraction"
    override val displayName: String = "ML Kit entity extraction"

    override suspend fun analyze(text: String): TextAnalysisResult {
        val sourceText = text.trim()
        if (sourceText.isBlank()) {
            return TextAnalysisResult(
                analyzerId = id,
                entities = emptyList(),
                rawTextLength = text.length,
                createdAt = System.currentTimeMillis(),
            )
        }

        return withContext(Dispatchers.IO) {
            val extractor = EntityExtraction.getClient(
                EntityExtractorOptions.Builder(EntityExtractorOptions.ENGLISH).build(),
            )
            try {
                extractor.downloadModelIfNeeded().awaitTask()
                val annotations = extractor.annotate(sourceText).awaitTask()
                TextAnalysisResult(
                    analyzerId = id,
                    entities = annotations.flatMap { annotation ->
                        annotation.toExtractedEntities(sourceText)
                    },
                    rawTextLength = text.length,
                    createdAt = System.currentTimeMillis(),
                )
            } finally {
                extractor.close()
            }
        }
    }

    private fun EntityAnnotation.toExtractedEntities(sourceText: String): List<ExtractedEntity> {
        val safeStart = start.coerceIn(0, sourceText.length)
        val safeEnd = end.coerceIn(safeStart, sourceText.length)
        val extractedText = sourceText.substring(safeStart, safeEnd)
        return entities.map { entity ->
            ExtractedEntity(
                type = entity.displayType(),
                text = extractedText,
                start = safeStart,
                end = safeEnd,
                metadata = entity.metadata(),
            )
        }
    }

    private fun Entity.displayType(): String {
        return javaClass.simpleName
            .removeSuffix("Entity")
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .ifBlank { "Unknown" }
    }

    private fun Entity.metadata(): Map<String, String> {
        return javaClass.methods
            .asSequence()
            .filter { method ->
                method.parameterCount == 0 &&
                    Modifier.isPublic(method.modifiers) &&
                    method.name !in ignoredMethods &&
                    (method.name.startsWith("get") || method.name.startsWith("is"))
            }
            .mapNotNull { method ->
                val value = runCatching { method.invoke(this) }.getOrNull() ?: return@mapNotNull null
                if (!value.isUsefulMetadataValue()) return@mapNotNull null
                method.name.propertyNameFromGetter() to value.toString()
            }
            .toMap()
    }

    private fun Any.isUsefulMetadataValue(): Boolean {
        return this is String ||
            this is Number ||
            this is Boolean ||
            javaClass.isEnum
    }

    private fun String.propertyNameFromGetter(): String {
        val raw = removePrefix("get").removePrefix("is")
        return raw.replaceFirstChar { it.lowercase() }
    }

    private suspend fun <T> Task<T>.awaitTask(): T {
        return suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { result -> continuation.resume(result) }
            addOnFailureListener { error -> continuation.resumeWithException(error) }
            addOnCanceledListener { continuation.cancel() }
        }
    }

    private companion object {
        val ignoredMethods = setOf(
            "getClass",
            "getType",
            "hashCode",
            "toString",
            "asAddressEntity",
            "asDateTimeEntity",
            "asEmailEntity",
            "asFlightNumberEntity",
            "asIbanEntity",
            "asIsbnEntity",
            "asMoneyEntity",
            "asPaymentCardEntity",
            "asPhoneEntity",
            "asTrackingNumberEntity",
            "asUrlEntity",
        )
    }
}
