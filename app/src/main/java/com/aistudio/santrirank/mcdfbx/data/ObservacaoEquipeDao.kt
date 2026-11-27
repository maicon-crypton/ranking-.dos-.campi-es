package com.aistudio.santrirank.mcdfbx.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aistudio.santrirank.mcdfbx.model.ObservacaoEquipe

@Dao
interface ObservacaoEquipeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(obs: ObservacaoEquipe): Long

    @Query("SELECT * FROM observacoes_equipe WHERE colaborador_id = :colaboradorId ORDER BY created_at DESC")
    suspend fun getByColaborador(colaboradorId: Long): List<ObservacaoEquipe>

    @Query("SELECT * FROM observacoes_equipe ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<ObservacaoEquipe>

    @Query("DELETE FROM observacoes_equipe")
    suspend fun deleteAll()
}
