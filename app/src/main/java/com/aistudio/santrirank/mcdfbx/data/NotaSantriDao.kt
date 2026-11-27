package com.aistudio.santrirank.mcdfbx.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.aistudio.santrirank.mcdfbx.model.NotaSantri

@Dao
interface NotaSantriDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(nota: NotaSantri): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(notas: List<NotaSantri>): List<Long>

    @Query("SELECT * FROM notas_santri ORDER BY data_emissao DESC")
    suspend fun getAll(): List<NotaSantri>

    @Query("SELECT * FROM notas_santri WHERE id = :id")
    suspend fun findById(id: Long): NotaSantri?

    @Query("SELECT * FROM notas_santri WHERE colaborador_id = :colaboradorId ORDER BY data_emissao DESC")
    suspend fun getByColaborador(colaboradorId: Long): List<NotaSantri>

    @Query("SELECT * FROM notas_santri WHERE status_auditoria = :status ORDER BY data_emissao DESC")
    suspend fun getByStatus(status: String): List<NotaSantri>

    @Query("SELECT * FROM notas_santri WHERE data_emissao >= :start AND data_emissao <= :end ORDER BY data_emissao DESC")
    suspend fun getByDateRange(start: Long, end: Long): List<NotaSantri>

    @Query("SELECT COUNT(*) FROM notas_santri WHERE numero_nota_santri = :numero")
    suspend fun countByNumero(numero: String): Int

    @Query("SELECT COUNT(*) FROM notas_santri WHERE foto_hash_sha256 = :hash AND foto_hash_sha256 != ''")
    suspend fun countByFotoHash(hash: String): Int

    @Update
    suspend fun update(nota: NotaSantri): Int

    @Query("DELETE FROM notas_santri")
    suspend fun deleteAll()
}
