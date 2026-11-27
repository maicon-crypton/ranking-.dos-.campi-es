package com.aistudio.santrirank.mcdfbx.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.aistudio.santrirank.mcdfbx.model.Colaborador

@Dao
interface ColaboradorDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(colaborador: Colaborador): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(colaboradores: List<Colaborador>): List<Long>

    @Query("SELECT * FROM colaboradores ORDER BY nome ASC")
    suspend fun getAll(): List<Colaborador>

    @Query("SELECT * FROM colaboradores WHERE id = :id")
    suspend fun findById(id: Long): Colaborador?

    @Query("SELECT * FROM colaboradores WHERE ativo = 1 ORDER BY nome ASC")
    suspend fun getActive(): List<Colaborador>

    @Query("SELECT * FROM colaboradores WHERE nome LIKE :query OR codigo_santri LIKE :query OR cargo LIKE :query OR setor LIKE :query ORDER BY nome ASC")
    suspend fun search(query: String): List<Colaborador>

    @Query("SELECT * FROM colaboradores WHERE codigo_santri = :codigo")
    suspend fun findByCodigo(codigo: String): Colaborador?

    @Update
    suspend fun update(colaborador: Colaborador): Int

    @Query("DELETE FROM colaboradores WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM colaboradores")
    suspend fun deleteAll()
}
