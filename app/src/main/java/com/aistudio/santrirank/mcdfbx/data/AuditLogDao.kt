package com.aistudio.santrirank.mcdfbx.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aistudio.santrirank.mcdfbx.model.AuditLog

@Dao
interface AuditLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: AuditLog): Long

    @Query("SELECT * FROM audit_logs ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<AuditLog>

    @Query("DELETE FROM audit_logs")
    suspend fun deleteAll()
}
