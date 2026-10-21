
package com.seyoung.runningproject.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// 데이터베이스 생성 및 관리 담당
@Database(
    entities = [RunningRecord::class],
    version = 3,
    exportSchema = false
)
abstract class RunningDatabase : RoomDatabase() {

    abstract fun runningRecordDao(): RunningRecordDao

    companion object {
        @Volatile
        private var INSTANCE: RunningDatabase? = null

        // 버전 1 -> 2: GPS 경로 컬럼 추가
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE running_records " +
                            "ADD COLUMN routeCoordinatesJson TEXT NOT NULL DEFAULT '[]'"
                )
            }
        }

        // 버전 2 -> 3: 러닝 제목 컬럼 추가
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE running_records " +
                            "ADD COLUMN title TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        fun getDatabase(context: Context): RunningDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    RunningDatabase::class.java,
                    "running_database"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3
                    )
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
