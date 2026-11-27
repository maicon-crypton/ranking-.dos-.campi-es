package com.aistudio.santrirank.mcdfbx.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.aistudio.santrirank.mcdfbx.model.AppUser

@Dao
interface AppUserDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(user: AppUser): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(user: AppUser): Long

    @Query("SELECT * FROM app_users WHERE username = :username")
    suspend fun findByUsername(username: String): AppUser?

    @Query("SELECT * FROM app_users")
    suspend fun getAll(): List<AppUser>

    @Update
    suspend fun update(user: AppUser): Int

    @Query("DELETE FROM app_users")
    suspend fun deleteAll()
}
