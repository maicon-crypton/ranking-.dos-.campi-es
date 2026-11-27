package com.aistudio.santrirank.mcdfbx.data

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aistudio.santrirank.mcdfbx.model.AuditLog
import com.aistudio.santrirank.mcdfbx.model.AppUser
import com.aistudio.santrirank.mcdfbx.model.Colaborador
import com.aistudio.santrirank.mcdfbx.model.NotaSantri
import com.aistudio.santrirank.mcdfbx.model.ObservacaoEquipe
import com.aistudio.santrirank.mcdfbx.model.ScoringWeights
import com.aistudio.santrirank.mcdfbx.model.UserWorkspace

@Database(
    entities = [
        Colaborador::class,
        NotaSantri::class,
        ObservacaoEquipe::class,
        AuditLog::class,
        ScoringWeights::class,
        UserWorkspace::class,
        AppUser::class
    ],
    version = 2,
    exportSchema = true
)
@TypeConverters(SantriTypeConverters::class)
abstract class SantriDatabase : RoomDatabase() {

    abstract fun colaboradorDao(): ColaboradorDao
    abstract fun notaSantriDao(): NotaSantriDao
    abstract fun observacaoEquipeDao(): ObservacaoEquipeDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun scoringWeightsDao(): ScoringWeightsDao
    abstract fun userWorkspaceDao(): UserWorkspaceDao
    abstract fun appUserDao(): AppUserDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE notas_santri ADD COLUMN analise_ia_json TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        @Volatile private var INSTANCE: SantriDatabase? = null

        fun getDatabase(context: android.content.Context): SantriDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    SantriDatabase::class.java,
                    "santri_ranking.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
