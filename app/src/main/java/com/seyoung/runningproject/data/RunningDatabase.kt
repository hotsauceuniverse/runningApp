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
    version = 5,
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

        // 버전 3 -> 4: 이동 시간 컬럼 추가
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE running_records " +
                            "ADD COLUMN movingTimeMillis INTEGER NOT NULL DEFAULT 0"
                )
            }
        }


        // 버전 4 -> 5: 이동 시간 컬럼을 제외한 테이블로 교체
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {

                // 1. 기존 데이터를 보존하면서 새 테이블 생성
                database.execSQL(
                    """
                    CREATE TABLE running_records_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        startedAt INTEGER NOT NULL,
                        totalDistanceMeters REAL NOT NULL,
                        elapsedTimeMillis INTEGER NOT NULL,
                        elevationGainMeters REAL NOT NULL,
                        routeCoordinatesJson TEXT NOT NULL DEFAULT '[]',
                        title TEXT NOT NULL DEFAULT ''
                    )
                    """.trimIndent()
                )

                // 2. 기존 테이블에서 필요한 컬럼만 복사
                database.execSQL(
                    """
                    INSERT INTO running_records_new (
                        id,
                        startedAt,
                        totalDistanceMeters,
                        elapsedTimeMillis,
                        elevationGainMeters,
                        routeCoordinatesJson,
                        title
                    )
                    SELECT
                        id,
                        startedAt,
                        totalDistanceMeters,
                        elapsedTimeMillis,
                        elevationGainMeters,
                        routeCoordinatesJson,
                        title
                    FROM running_records
                    """.trimIndent()
                )

                // 3. 기존 테이블 삭제
                database.execSQL("DROP TABLE running_records")

                // 4. 새 테이블을 기존 테이블 이름으로 변경
                database.execSQL(
                    "ALTER TABLE running_records_new RENAME TO running_records"
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
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5
                    )
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
