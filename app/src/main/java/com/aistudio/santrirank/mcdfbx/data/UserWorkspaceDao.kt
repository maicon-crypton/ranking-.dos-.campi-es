package com.aistudio.santrirank.mcdfbx.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aistudio.santrirank.mcdfbx.model.UserWorkspace

@Dao
interface UserWorkspaceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(workspace: UserWorkspace): Long

    @Query("SELECT * FROM user_workspace ORDER BY id DESC LIMIT 1")
    suspend fun getLatest(): UserWorkspace?

    @Query("DELETE FROM user_workspace")
    suspend fun deleteAll()
}
