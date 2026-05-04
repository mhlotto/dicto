package com.mhlotto.dicto.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY createdAt ASC")
    fun observeProjects(): Flow<List<ProjectEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(project: ProjectEntity): Long

    @Query("SELECT COUNT(*) FROM projects")
    suspend fun count(): Int
}
