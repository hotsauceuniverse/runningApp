package com.seyoung.runningproject

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.seyoung.runningproject.data.RunningRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RunningHistoryAdapter(private val onRecordClick: (RunningRecord) -> Unit) : RecyclerView.Adapter<RunningHistoryAdapter.HistoryViewHolder>() {

    private val records = mutableListOf<RunningRecord>()

    class HistoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val recordCard: View = itemView.findViewById(R.id.layout_record_card)
        val monthTv: TextView = itemView.findViewById(R.id.tv_record_month)
        val dayTv: TextView = itemView.findViewById(R.id.tv_record_day)
        val weekdayTv: TextView = itemView.findViewById(R.id.tv_record_weekday)
        val titleTv: TextView = itemView.findViewById(R.id.tv_record_title)
        val dateTv: TextView = itemView.findViewById(R.id.tv_record_date)
        val distanceTv: TextView = itemView.findViewById(R.id.tv_record_distance)
        val durationTv: TextView = itemView.findViewById(R.id.tv_record_duration)
        val paceTv: TextView = itemView.findViewById(R.id.tv_record_pace)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_running_history, parent, false)
        return HistoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        val record = records[position]
        val date = Date(record.startedAt)

        // 날짜 표시
        holder.monthTv.text = SimpleDateFormat("MM월", Locale.KOREA).format(date)
        holder.dayTv.text = SimpleDateFormat("d", Locale.KOREA).format(date)
        holder.weekdayTv.text = SimpleDateFormat("EEE", Locale.KOREA).format(date)
        holder.dateTv.text = SimpleDateFormat("yyyy.MM.dd", Locale.KOREA).format(date)

        // 기록 제목
        holder.titleTv.text = "러닝 기록"

        // 거리 : m -> km
        val distanceKm = record.totalDistanceMeters / 1000.0
        holder.distanceTv.text = String.format(Locale.KOREA, "%.2f", distanceKm)

        // 러닝 시간
        holder.durationTv.text = formatDuration(record.elapsedTimeMillis)

        // 평균 페이스 : 분/km
        holder.paceTv.text = formatPace(record.elapsedTimeMillis, record.totalDistanceMeters)

        // 카트 클릭 시, 선택한 기록 전달
        holder.recordCard.setOnClickListener{
            onRecordClick(record)
        }
    }

    override fun getItemCount(): Int = records.size

    fun submitList(newRecords: List<RunningRecord>) {
        records.clear()
        records.addAll(newRecords)
        notifyDataSetChanged()
    }

    private fun formatDuration(elapsedTimeMillis: Long): String {
        val totalSeconds = elapsedTimeMillis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return if (hours > 0) {
            String.format(Locale.KOREA, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.KOREA, "%02d:%02d", minutes, seconds)
        }
    }

    private fun formatPace(elapsedTimeMillis: Long, distanceMeters: Double): String {
        if (distanceMeters <= 0.0 || elapsedTimeMillis <= 0L) {
            return  "--"
        }
        val secondsPerKm = (elapsedTimeMillis / 1000.0) / (distanceMeters / 1000.0)
        val totalSeconds = secondsPerKm.toInt()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60

        return String.format(Locale.KOREA, "%d'%02d\"", minutes, seconds)
    }
}