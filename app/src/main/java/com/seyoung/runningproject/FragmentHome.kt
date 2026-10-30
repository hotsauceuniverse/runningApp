package com.seyoung.runningproject

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.seyoung.runningproject.data.RunningDatabase
import com.seyoung.runningproject.data.RunningRecord
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.launch
import android.util.Log
import android.content.Intent

// 이번 주 통계와 최근 기록을 Room에서 불러와 표시
class FragmentHome : Fragment(R.layout.fragment_home) {

    private lateinit var recordAdapter: RecentRunningAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recyclerView = view.findViewById<RecyclerView>(R.id.recently_running_rv)

//        recordAdapter = RecentRunningAdapter()
        recordAdapter = RecentRunningAdapter { record ->
            val intent = Intent(requireContext(), RunningResultActivity::class.java).apply {
                putExtra("RECORD_ID", record.id)
            }
            startActivity(intent)
        }

        val moreBtn = view.findViewById<TextView>(R.id.more_btn)
        moreBtn.setOnClickListener {
            val intent = Intent(requireContext(), RunningHistoryActivity::class.java)
            startActivity(intent)
        }

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = recordAdapter

        Log.d("HOME_REFRESH", "RecyclerView 인스턴스: ${recyclerView.hashCode()}")
        Log.d("HOME_REFRESH", "어댑터 인스턴스: ${recordAdapter.hashCode()}")

        loadRunningRecords()
//        deleteEmptyRouteRecords()
    }

    // HomeFragment에서 최근러닝 카드뷰 데이터 삭제
//    private fun deleteEmptyRouteRecords() {
//        viewLifecycleOwner.lifecycleScope.launch {
//            val dao = RunningDatabase
//                .getDatabase(requireContext())
//                .runningRecordDao()
//
//            dao.deleteRecord(35L)
//            dao.deleteRecord(38L)
//
//            // 삭제 후 DB에 남아 있는 기록 확인
//            val remainingRecords = dao.getAllRecords()
//
//            Log.d("TEST_DELETE", "삭제 후 전체 기록: ${
//                remainingRecords.map { it.id }
//            }")
//
//            // 카드뷰와 통계 갱신
//            loadRunningRecords()
//        }
//    }

    override fun onResume() {
        super.onResume()

        // 다른 화면에서 돌아왔을 때 최신 기록을 다시 불러오기
        if (::recordAdapter.isInitialized) {
            loadRunningRecords()
        }
    }

    private fun loadRunningRecords() {
        val currentView = view ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            val dao = RunningDatabase
                .getDatabase(requireContext())
                .runningRecordDao()

            val allRecords = dao.getAllRecords()
            val recentRecords = allRecords.take(3)

            Log.d("HOME_REFRESH", "기록 조회: ${allRecords.map { it.id }}")
            Log.d("HOME_REFRESH", "화면에 전달: ${recentRecords.map { it.id }}")

            // 최신 기록부터 최대 3개 표시
            recordAdapter.updateRecords(allRecords.take(3))

            Log.d(
                "HOME_REFRESH",
                "갱신 후 어댑터 항목 수: ${recordAdapter.itemCount}"
            )

            updateWeeklyStats(currentView, allRecords)
        }
    }

    private fun updateWeeklyStats(view: View, allRecords: List<RunningRecord>) {
        val calendar = Calendar.getInstance()

        // 이번 주 일요일 00:00
        calendar.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        val weekStart = calendar.timeInMillis

        // 다음 주 일요일 00:00 (종료 시각은 포함하지 않음)
        calendar.add(Calendar.DAY_OF_YEAR, 7)
        val weekEnd = calendar.timeInMillis

        val weeklyRecords = allRecords.filter {
            it.startedAt >= weekStart &&
                    it.startedAt < weekEnd &&
                    it.totalDistanceMeters > 0
        }

        val totalDistanceMeters =
            weeklyRecords.sumOf { it.totalDistanceMeters }

        val totalTimeMillis =
            weeklyRecords.sumOf { it.elapsedTimeMillis }

        val totalKm = totalDistanceMeters / 1000.0
        val runCount = weeklyRecords.size

        val totalSeconds = totalTimeMillis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        val timeText = if (hours > 0) {
            "${hours}h ${minutes}m"
        } else if (minutes > 0) {
            "${minutes}m ${seconds}s"
        } else {
            "${seconds}s"
        }

        view.findViewById<TextView>(R.id.weekly_distance_tv).text =
            String.format(Locale.KOREA, "%.2f km", totalKm)

        view.findViewById<TextView>(R.id.weekly_summary_tv).text =
            "${runCount}회 · $timeText"

        Log.d("WEEKLY_STATS", "===== 이번 주 러닝 통계 =====")
        Log.d("WEEKLY_STATS", "이번 주 시작 시각: $weekStart")
        Log.d("WEEKLY_STATS", "다음 주 시작 시각: $weekEnd")
        Log.d("WEEKLY_STATS", "이번 주 기록 개수: ${weeklyRecords.size}")
        Log.d("WEEKLY_STATS", "이번 주 총 거리(m): $totalDistanceMeters")
        Log.d("WEEKLY_STATS", "이번 주 총 거리(km): $totalKm")
        Log.d("WEEKLY_STATS", "이번 주 총 운동 시간(ms): $totalTimeMillis")
        Log.d("WEEKLY_STATS", "이번 주 총 운동 시간: ${hours}시간 ${minutes}분")

        weeklyRecords.forEachIndexed { index, record ->
            Log.d("WEEKLY_STATS",
                "기록 ${index + 1}: " +
                        "id=${record.id}, " +
                        "시작 시각=${record.startedAt}, " +
                        "거리=${record.totalDistanceMeters}m, " +
                        "운동 시간=${record.elapsedTimeMillis}ms"
            )
        }
        Log.d("WEEKLY_STATS", "============================")
    }
}