package com.seyoung.runningproject

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.seyoung.runningproject.data.RunningDatabase
import kotlinx.coroutines.launch
import java.util.Locale

class RunningHistoryActivity : AppCompatActivity() {

    private lateinit var historyAdapter: RunningHistoryAdapter

    private lateinit var totalDistanceTv: TextView
    private lateinit var totalRunsTv: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_running_history)

        // 누적 통계 뷰 연결
        totalDistanceTv = findViewById(R.id.tv_total_distance)
        totalRunsTv = findViewById(R.id.tv_total_runs)

        // RecyclerView 설정
        val historyRecyclerView = findViewById<RecyclerView>(R.id.rv_running_history)

        historyAdapter = RunningHistoryAdapter { record ->

            // 선택한 기록의 ID를 상세 화면으로 전달
            val intent = Intent(
                this,
                RunningResultActivity::class.java
            ).apply {
                putExtra("RECORD_ID", record.id)
            }

            startActivity(intent)
        }

        historyRecyclerView.layoutManager =
            LinearLayoutManager(this)

        historyRecyclerView.adapter = historyAdapter

        // DB에서 전체 러닝 기록 조회
        loadRunningHistory()
    }

    private fun loadRunningHistory() {
        lifecycleScope.launch {
            val dao = RunningDatabase
                .getDatabase(this@RunningHistoryActivity)
                .runningRecordDao()

            val records = dao.getAllRecords()

            // 누적 거리 계산: 미터 -> 킬로미터
            val totalDistanceKm =
                records.sumOf { it.totalDistanceMeters } / 1000.0

            totalDistanceTv.text =
                String.format(Locale.KOREA, "%.2f", totalDistanceKm)

            // 전체 러닝 횟수
            totalRunsTv.text = records.size.toString()

            // 조회한 기록을 목록에 전달
            historyAdapter.submitList(records)
        }
    }
}