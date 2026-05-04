package com.mhlotto.dicto.ui

import com.mhlotto.dicto.data.NoteEntity
import com.mhlotto.dicto.data.ProjectEntity
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

fun draftMarkdown(title: String, body: String, projectName: String): String {
    return buildString {
        appendLine("# ${title.ifBlank { "Untitled dictation" }}")
        appendLine()
        appendLine("Project: $projectName")
        appendLine()
        appendLine(body)
    }
}

fun noteMarkdown(note: NoteEntity, projectName: String): String {
    return buildString {
        appendLine("# ${note.title}")
        appendLine()
        appendLine("Project: $projectName")
        appendLine("Created: ${formatDate(note.createdAt)}")
        appendLine("Updated: ${formatDate(note.updatedAt)}")
        appendLine()
        appendLine(note.body)
    }
}

fun draftJson(title: String, body: String, projectId: Long, projectName: String): String {
    return JSONObject()
        .put("title", title.ifBlank { "Untitled dictation" })
        .put("body", body)
        .put("projectId", projectId)
        .put("projectName", projectName)
        .toString(2)
}

fun noteJson(note: NoteEntity, projectName: String): String {
    return JSONObject()
        .put("id", note.id)
        .put("title", note.title)
        .put("body", note.body)
        .put("createdAt", note.createdAt)
        .put("updatedAt", note.updatedAt)
        .put("projectId", note.projectId)
        .put("projectName", projectName)
        .toString(2)
}

private fun formatDate(timestamp: Long): String {
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))
}
