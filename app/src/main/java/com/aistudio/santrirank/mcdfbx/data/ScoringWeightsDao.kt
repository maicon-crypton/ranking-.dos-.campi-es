package com.aistudio.santrirank.mcdfbx.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aistudio.santrirank.mcdfbx.model.ScoringWeights

@Dao
interface ScoringWeightsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(weights: ScoringWeights): Long

    @Query("SELECT * FROM scoring_weights ORDER BY id DESC LIMIT 1")
    suspend fun getLatest(): ScoringWeights?

    @Query("DELETE FROM scoring_weights")
    suspend fun deleteAll()
}
