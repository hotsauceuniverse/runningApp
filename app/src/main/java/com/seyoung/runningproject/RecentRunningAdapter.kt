package com.seyoung.runningproject

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.seyoung.runningproject.data.RunningRecord
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

// 저장된 기록을 카드 형태로 표시
class RecentRunningAdapter :
    RecyclerView.Adapter<RecentRunningAdapter.RunningViewHolder>() {

    private val records = mutableListOf<RunningRecord>()

    class RunningViewHolder(itemView: View) :
        RecyclerView.ViewHolder(itemView) {

        val mapImage: ImageView =
            itemView.findViewById(R.id.recently_map_iv)

        val dateText: TextView =
            itemView.findViewById(R.id.recently_date_tv)

        val distanceText: TextView =
            itemView.findViewById(R.id.distance_total)

        val durationText: TextView =
            itemView.findViewById(R.id.time_total)

        val paceText: TextView =
            itemView.findViewById(R.id.pace_total)
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): RunningViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recently_running, parent, false)

        return RunningViewHolder(view)
    }

    override fun onBindViewHolder(holder: RunningViewHolder, position: Int) {
        Log.d("RECENT_ROUTE", "onBindViewHolder 실행: position=$position")
        val record = records[position]

        // 날짜
        val dateFormat = SimpleDateFormat("M월 d일", Locale.KOREA)
        holder.dateText.text = dateFormat.format(Date(record.startedAt))

        // 거리
        holder.distanceText.text = String.format(
            Locale.KOREA,
            "%.2fkm",
            record.totalDistanceMeters / 1000.0
        )

        // 운동 시간
        val totalSeconds = record.elapsedTimeMillis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60

        holder.durationText.text = String.format(
            Locale.KOREA,
            "%02d:%02d",
            minutes,
            seconds
        )

        // 평균 페이스
        holder.paceText.text = if (record.totalDistanceMeters > 0) {
            val secondsPerKm =
                (record.elapsedTimeMillis / 1000.0) /
                        (record.totalDistanceMeters / 1000.0)

            val paceMinutes = secondsPerKm.toInt() / 60
            val paceSeconds = secondsPerKm.toInt() % 60

            String.format(
                Locale.KOREA,
                "%d'%02d\"/km",
                paceMinutes,
                paceSeconds
            )
        } else {
            "--/km"
        }

        // 저장된 러닝 경로 JSON 확인
        Log.d("RECENT_ROUTE",
            "기록 ID: ${record.id}, 경로 JSON: ${record.routeCoordinatesJson}"
        )

        // 경로 이미지
        val routeBitmap = createRouteBitmap(record.routeCoordinatesJson)

        if (routeBitmap != null) {
            holder.mapImage.setImageBitmap(routeBitmap)

            Log.d("RECENT_ROUTE",
                "경로 이미지 생성 성공 - 기록 ID: ${record.id}, " +
                        "이미지 크기: ${routeBitmap.width}x${routeBitmap.height}"
            )
        } else {
            // 좌표가 없으면 기존 기본 이미지 유지
            holder.mapImage.setImageResource(R.drawable.ic_launcher_background)

            Log.w("RECENT_ROUTE",
                "경로 이미지 생성 실패 또는 좌표 없음 - 기록 ID: ${record.id}"
            )
        }
    }

    override fun getItemCount(): Int = records.size

    fun updateRecords(newRecords: List<RunningRecord>) {
        records.clear()
        records.addAll(newRecords)
        notifyDataSetChanged()
    }

    /**
     * 저장된 GPS 좌표(JSON)를 이용해 경로 미리보기 이미지를 생성한다.
     * JSON 형식: [[위도, 경도], [위도, 경도], ...]
     */
    private fun createRouteBitmap(routeCoordinatesJson: String): Bitmap? {
        Log.d(
            "RECENT_ROUTE",
            "경로 이미지 생성 시작 - JSON 길이: ${routeCoordinatesJson.length}"
        )

        val coordinates = mutableListOf<Pair<Double, Double>>()

        try {
            val jsonArray = JSONArray(routeCoordinatesJson)

            Log.d(
                "RECENT_ROUTE",
                "JSON 배열 파싱 성공 - 좌표 개수: ${jsonArray.length()}"
            )

            for (i in 0 until jsonArray.length()) {
                val point = jsonArray.optJSONArray(i)

                if (point == null || point.length() < 2) {
                    Log.w(
                        "RECENT_ROUTE",
                        "잘못된 좌표 형식 - 인덱스: $i"
                    )
                    continue
                }

                val latitude = point.optDouble(0, Double.NaN)
                val longitude = point.optDouble(1, Double.NaN)

                if (!latitude.isFinite() || !longitude.isFinite()) {
                    Log.w(
                        "RECENT_ROUTE",
                        "유효하지 않은 좌표 - 인덱스: $i, " +
                                "위도: $latitude, 경도: $longitude"
                    )
                    continue
                }

                coordinates.add(latitude to longitude)
            }
        } catch (e: Exception) {
            Log.e(
                "RECENT_ROUTE",
                "경로 JSON 파싱 실패",
                e
            )
            return null
        }

        if (coordinates.isEmpty()) {
            Log.w(
                "RECENT_ROUTE",
                "사용 가능한 좌표가 없음"
            )
            return null
        }

        Log.d(
            "RECENT_ROUTE",
            "유효 좌표 추출 완료 - 개수: ${coordinates.size}, " +
                    "시작 좌표: ${coordinates.first()}, " +
                    "마지막 좌표: ${coordinates.last()}"
        )

        // 미리보기 비트맵 크기
        val bitmapWidth = 300
        val bitmapHeight = 372

        val bitmap = Bitmap.createBitmap(
            bitmapWidth,
            bitmapHeight,
            Bitmap.Config.ARGB_8888
        )

        val canvas = Canvas(bitmap)

        // 배경색
        canvas.drawColor(Color.rgb(237, 247, 242))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(37, 145, 102)
            style = Paint.Style.STROKE
            strokeWidth = 8f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        // 좌표의 위도/경도 범위
        val minLat = coordinates.minOf { it.first }
        val maxLat = coordinates.maxOf { it.first }
        val minLon = coordinates.minOf { it.second }
        val maxLon = coordinates.maxOf { it.second }

        val padding = 35f
        val drawableWidth = bitmapWidth - padding * 2
        val drawableHeight = bitmapHeight - padding * 2

        val latRange = max(maxLat - minLat, 0.000001)
        val lonRange = max(maxLon - minLon, 0.000001)

        // 경로 비율을 유지하면서 이미지 안에 맞춘다.
        val scale = min(
            drawableWidth / lonRange.toFloat(),
            drawableHeight / latRange.toFloat()
        )

        val path = Path()

        coordinates.forEachIndexed { index, coordinate ->
            val x = padding +
                    (coordinate.second - minLon).toFloat() * scale

            // 화면에서는 위도가 커질수록 위쪽에 표시
            val y = bitmapHeight - padding -
                    (coordinate.first - minLat).toFloat() * scale

            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        if (coordinates.size >= 2) {
            canvas.drawPath(path, paint)
        } else {
            // 좌표가 하나뿐인 경우 점으로 표시
            paint.style = Paint.Style.FILL
            canvas.drawCircle(
                bitmapWidth / 2f,
                bitmapHeight / 2f,
                10f,
                paint
            )
        }

        // 시작 지점: 초록색 원
        val first = coordinates.first()
        val firstX = padding +
                (first.second - minLon).toFloat() * scale
        val firstY = bitmapHeight - padding -
                (first.first - minLat).toFloat() * scale

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(20, 110, 75)
        canvas.drawCircle(firstX, firstY, 11f, paint)

        // 종료 지점: 주황색 원
        if (coordinates.size >= 2) {
            val last = coordinates.last()
            val lastX = padding +
                    (last.second - minLon).toFloat() * scale
            val lastY = bitmapHeight - padding -
                    (last.first - minLat).toFloat() * scale

            paint.color = Color.rgb(245, 135, 55)
            canvas.drawCircle(lastX, lastY, 11f, paint)
        }

        return bitmap
    }
}

