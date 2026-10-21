package com.seyoung.runningproject.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

// 기록 저장, 전체 조회, 삭제 담당
@Dao
interface RunningRecordDao {

    // 러닝 기록 저장
    @Insert
    suspend fun insert(record: RunningRecord): Long

    // 모든 러닝 기록 조회 (최근 기록부터)
    @Query("SELECT * FROM running_records ORDER BY startedAt DESC")
    suspend fun getAllRecords(): List<RunningRecord>

    // 특정 러닝 기록 조회
    @Query("SELECT * FROM running_records WHERE id = :recordId")
    suspend fun getRecordById(recordId: Long): RunningRecord?

    // 러닝 기록 전체 개수
    @Query("SELECT COUNT(*) FROM running_records")
    suspend fun getRecordCount(): Int

    // 특정 러닝 기록 삭제
    @Query("DELETE FROM running_records WHERE id = :recordId")
    suspend fun deleteRecord(recordId: Long)

    // 제목 수정 쿼리
    @Query("UPDATE running_records " + "SET title = :title WHERE id = :recordId")
    suspend fun updateTitle(recordId: Long, title: String)
}