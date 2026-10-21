package com.seyoung.runningproject.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

// 러닝 기록을 저장할 데이터 클래스 (Room 데이터베이스 엔티티)
@Entity(tableName = "running_records")
data class RunningRecord(
    @PrimaryKey(autoGenerate = true)
    // 러닝 기록의 고유 번호
    val id: Long = 0,

    // 러닝 시작한 날짜와 시각 (Unix timestamp, milliseconds)
    val startedAt: Long,

    // 총 거리 (미터)
    val totalDistanceMeters: Double,

    // 실제 러닝 시간 (밀리초)
    val elapsedTimeMillis: Long,

    // 누적 상승 고도 (미터)
    val elevationGainMeters: Double,

    // GPS 경로 좌표 목록 (JSON 문자열)
    @ColumnInfo(defaultValue = "'[]'")
    val routeCoordinatesJson: String = "[]",

    // 제목 필드 추가
    val title: String = ""
)