package com.mhlotto.dicto.data

import kotlinx.coroutines.flow.Flow

class DictoRepository(
    private val projectDao: ProjectDao,
    private val noteDao: NoteDao,
) {
    val projects: Flow<List<ProjectEntity>> = projectDao.observeProjects()

    suspend fun ensureDefaultProject(): Long {
        if (projectDao.count() > 0) return 0
        return projectDao.insert(ProjectEntity(name = "Memory Palace"))
    }

    suspend fun createProject(name: String): Long {
        return projectDao.insert(ProjectEntity(name = name.trim()))
    }

    fun observeNotes(projectId: Long): Flow<List<NoteEntity>> = noteDao.observeNotes(projectId)

    fun observeNote(noteId: Long): Flow<NoteEntity?> = noteDao.observeNote(noteId)

    suspend fun getNote(noteId: Long): NoteEntity? = noteDao.getNote(noteId)

    suspend fun saveNote(title: String, body: String, projectId: Long): Long {
        val now = System.currentTimeMillis()
        return noteDao.insert(
            NoteEntity(
                title = title.ifBlank { defaultTitle(now) },
                body = body,
                createdAt = now,
                updatedAt = now,
                projectId = projectId,
            ),
        )
    }

    suspend fun updateNote(noteId: Long, title: String, body: String, projectId: Long) {
        val existing = noteDao.getNote(noteId) ?: return
        noteDao.update(
            existing.copy(
                title = title.ifBlank { defaultTitle(existing.createdAt) },
                body = body,
                updatedAt = System.currentTimeMillis(),
                projectId = projectId,
            ),
        )
    }

    private fun defaultTitle(timestamp: Long): String = "Dictation $timestamp"
}
