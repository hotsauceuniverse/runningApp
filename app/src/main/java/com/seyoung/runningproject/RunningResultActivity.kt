package com.seyoung.runningproject

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.view.View
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.seyoung.runningproject.data.RunningDatabase
import com.seyoung.runningproject.data.RunningRecord
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import androidx.appcompat.app.AppCompatActivity
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.MapView
import com.kakao.vectormap.KakaoMapSdk
import com.kakao.vectormap.LatLng
import com.kakao.vectormap.shape.MapPoints
import com.kakao.vectormap.shape.Polyline
import com.kakao.vectormap.shape.PolylineOptions
import android.graphics.Color
import android.widget.EditText
import com.kakao.vectormap.camera.CameraUpdateFactory

class RunningResultActivity : AppCompatActivity() {

    private lateinit var kmTv: TextView
    private lateinit var averagePaceTv: TextView
    private lateinit var timeTv: TextView
    private lateinit var calorieTv: TextView
    private lateinit var elevationGainTv: TextView
    private lateinit var averageSpeedTv: TextView

    // 결과 화면 카카오 지도
    private lateinit var resultMap: MapView
    private var kakaoMap: KakaoMap? = null

    // 러닝 경로
    private var runningPath = mutableListOf<LatLng>()
    private var runningPolyline: Polyline? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.running_result_activity)

        kmTv = findViewById(R.id.km_tv)
        averagePaceTv = findViewById(R.id.average_pace_tv)
        timeTv = findViewById(R.id.time_tv)
        calorieTv = findViewById(R.id.calorie_tv)
        elevationGainTv = findViewById(R.id.distance_tv)
        averageSpeedTv = findViewById(R.id.averageSpeed_tv)

        // X 버튼 클릭 이벤트
        findViewById<View>(R.id.close_btn).setOnClickListener {
            finish()
        }

        val saveButton = findViewById<View>(R.id.btn_save)
        val recordId = intent.getLongExtra("RECORD_ID", -1L)
        val dayTv = findViewById<EditText>(R.id.day_tv)

// 기존 제목과 제목 로딩 여부
        var originalTitle = ""
        var isTitleLoaded = false

// 처음에는 저장 버튼 숨기기
        saveButton.visibility = View.GONE

// 제목이 실제로 변경됐을 때만 저장 버튼 표시
        dayTv.addTextChangedListener(object : TextWatcher {

            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int
            ) {}

            override fun onTextChanged(
                s: CharSequence?,
                start: Int,
                before: Int,
                count: Int
            ) {
                if (recordId != -1L && isTitleLoaded) {
                    val currentTitle = s.toString().trim()

                    saveButton.visibility =
                        if (currentTitle != originalTitle) {
                            View.VISIBLE
                        } else {
                            View.GONE
                        }
                }
            }

            override fun afterTextChanged(s: Editable?) {}
        })

// 저장 버튼 클릭 시 DB에 제목 저장
        saveButton.setOnClickListener {
            if (recordId == -1L) {
                return@setOnClickListener
            }
            val newTitle = dayTv.text.toString().trim()

            if (newTitle.isBlank()) {
                Toast.makeText(this, "러닝 제목을 입력해 주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                try {
                    val dao = RunningDatabase
                        .getDatabase(this@RunningResultActivity)
                        .runningRecordDao()

                    dao.updateTitle(recordId, newTitle)

                    // 저장 완료 후 기준 제목 갱신
                    originalTitle = newTitle
                    saveButton.visibility = View.GONE

                    Toast.makeText(this@RunningResultActivity, "러닝 제목이 저장되었습니다.", Toast.LENGTH_SHORT).show()
                    Log.d("RUN_TITLE", "제목 저장 완료: $newTitle")

                } catch (e: Exception) {
                    Log.e("RUN_TITLE", "제목 저장 실패", e)

                    Toast.makeText(this@RunningResultActivity, "제목 저장에 실패했습니다.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Kakao MapView 연결
        resultMap = findViewById(R.id.result_map_image)
        KakaoMapSdk.init(
            this,
            BuildConfig.KAKAO_MAP_KEY
        )

        lifecycleScope.launch {
            Log.d("RUN_TITLE", "recordId = $recordId")
            if (recordId != -1L) {
                // 과거 러닝 기록 조회
                val dao = RunningDatabase
                    .getDatabase(this@RunningResultActivity)
                    .runningRecordDao()

                val record = dao.getRecordById(recordId)

                if (record == null) {
                    Log.e("RESULT_HISTORY", "기록을 찾을 수 없습니다. ID: $recordId")
                    finish()
                    return@launch
                }

                Log.d("RESULT_HISTORY", "과거 기록 조회 성공: ID=${record.id}")

                // DB 데이터로 결과 화면 구성
                runningData(record)

                // DB에 저장된 GPS 경로 복원
                getRunningPath(record)

                // 제목 설정
                val savedTitle = record.title.ifBlank {
                    createRunningTitle(record.startedAt)
                }
                dayTv.setText(savedTitle)

                // 현재 제목을 기준값으로 저장
                originalTitle = savedTitle
                isTitleLoaded = true

                // 최초 로딩 시에는 저장 버튼 숨김
                saveButton.visibility = View.GONE

                // 날짜 표시
                findViewById<TextView>(R.id.date_tv).text = SimpleDateFormat("yyyy년 M월 d일", Locale.KOREA).format(Date(record.startedAt))

            } else {
                Log.d("RUN_TITLE", "새 러닝 결과 else 진입")
                // 새 러닝 결과 화면의 제목 설정
                setRunningTitle()

                // 기존 새 러닝 결과 표시 로직 유지
                runningData()       // 러닝 데이터
                getRunningPath()    // 러닝 경로 받기
            }

            // 데이터와 경로를 준비한 다음 지도 시작
            startResultMap()
        }
    }

    private fun createRunningTitle(startedAt: Long): String {
        val date = Date(startedAt)
        val dayOfWeek = SimpleDateFormat("EEEE", Locale.KOREAN).format(date)
        val amPm = SimpleDateFormat("a", Locale.KOREAN).format(date)

        return "$dayOfWeek $amPm 러닝"
    }

    // ======================
    // 요일과 오전/오후를 설정
    // ======================
    private fun setRunningTitle() {
        Log.d("RUN_TITLE", "setRunningTitle() 실행됨")
        val dayTv = findViewById<EditText>(R.id.day_tv)

        // 실제 러닝 시작 시각 가져오기
        val startedAt = intent.getLongExtra("STARTED_AT", -1L)
        if (startedAt == -1L) {
            Log.e("RUN_TITLE", "STARTED_AT 값이 전달되지 않았습니다.")
            return
        }
        val runDate = Date(startedAt)

        // 실제 날짜에 맞는 요일 계산
        val datOfWeek = SimpleDateFormat("EEEE", Locale.KOREA).format(runDate)

        // 실제 시작 시각에 맞는 오전/오후 계산
        val amPm = SimpleDateFormat("a", Locale.KOREA).format(runDate)
        dayTv.setText("$datOfWeek $amPm 러닝")
        Log.d("RUN_TITLE", "설정된 제목 = ${dayTv.text}")
    }



    // =================
    // 지도 시작
    // =================
    private fun startResultMap() {
        resultMap.start(
            object : MapLifeCycleCallback() {
                override fun onMapDestroy() {
                    Log.d("RESULT_MAP", "결과 지도 종료")
                }

                override fun onMapError(error: Exception) {
                    Log.e(
                        "RESULT_MAP",
                        "결과 지도 에러: ${error.message}",
                        error
                    )
                }
            },
            object : KakaoMapReadyCallback() {
                override fun onMapReady(kakaoMap: KakaoMap) {
                    Log.d("RESULT_MAP", "결과 지도 준비 완료")

                    this@RunningResultActivity.kakaoMap = kakaoMap
                    // 러닝 경로 그리기
                    drawRunningPath(kakaoMap)

                    // 러닝 경로에 맞춰 지도 자동 확대/축소
                    fitRunningPath(kakaoMap)
                }

                override fun getPosition(): LatLng {
                    return if (runningPath.isNotEmpty()) {
                        // 경로가 있으면 첫 번째 위치
                        runningPath.first()
                    } else {
                        // 경로가 없으면 서울 시청
                        LatLng.from(37.5665, 126.9780)
                    }
                }
                // 초기값
                // 실제 경로에 맞는 줌은 onMapReady()에서 fitRunningPath()가 결정
                override fun getZoomLevel(): Int = 15
            }
        )
    }

    // =========================
    // 러닝 결과 데이터 화면 표시
    // =========================
    private fun runningData(record: RunningRecord? = null) {
        // 데이터 받기
        val totalDistance = record?.totalDistanceMeters
            ?: intent.getDoubleExtra("TOTAL_DISTANCE", 0.0)

        val elapsedTime = record?.elapsedTimeMillis
            ?: intent.getLongExtra("ELAPSED_TIME", 0L)

        val elevationGain = record?.elevationGainMeters
            ?: intent.getDoubleExtra("ELEVATION_GAIN", 0.0)

        // 킬로미터 계산 (m → km 변환)
        val distanceKm = totalDistance / 1000.0
        Log.d("totalDistance", distanceKm.toString())
        kmTv.text = String.format("%.2f", distanceKm)

        // 시간 계산 (밀리초 → 시/분/초)
        val hours = elapsedTime / 3600000
        val minutes = (elapsedTime % 3600000) / 60000
        val seconds = (elapsedTime % 60000) / 1000
        timeTv.text = String.format("%02d:%02d:%02d", hours, minutes, seconds)

        // 평균 페이스 계산
        // 거리 0 방지
        if (distanceKm > 0) {
            // 전체 시간을 초 단위로 변환
            val totalSeconds = elapsedTime / 1000.0

            // 1km당 걸린 시간(초)
            val paceSeconds = totalSeconds / distanceKm

            // 분
            val paceMinutes = (paceSeconds / 60).toInt()

            // 초
            val paceRemainSeconds = (paceSeconds % 60).toInt()

            // 화면 표시
            averagePaceTv.text = String.format("%d'%02d\"", paceMinutes, paceRemainSeconds)
            Log.d("RUN_PACE", "평균 페이스 : ${paceMinutes}분 ${paceRemainSeconds}초 / km")
        } else {
            averagePaceTv.text =  "--'--\""
        }

        // 평균 속도 계산
        val timeHours = elapsedTime / 3600000.0
        val averageSpeed = if (timeHours > 0) {
            distanceKm / timeHours
        } else {
            0.0
        }
        averageSpeedTv.text = String.format("%.1f km/h", averageSpeed)
        Log.d("RUN_SPEED", "평균 속도 : %.1f km/h".format(averageSpeed))

        // 칼로리 계산
        // 체중 임시값 넣기(70kg) -> 나중에 회원정보 / 설정에서 가져오기
        val weightKg = 70.0

        // 평균 속도에 따른 MET 값 (2024 Adult Compendium 기준)
        val met = when {
            averageSpeed < 6.9 -> 6.5       // 4.0 ~ 4.2 mph
            averageSpeed < 8.5 -> 7.8       // 4.3 ~ 4.8 mph
            averageSpeed < 9.5 -> 8.5       // 5.0 ~ 5.2 mph
            averageSpeed < 10.5 -> 9.0      // 5.5 ~ 5.8 mph
            averageSpeed < 11.0 -> 9.3      // 6.0 ~ 6.3 mph
            averageSpeed < 12.0 -> 10.5     // 6.7 mph
            averageSpeed < 12.5 -> 11.0     // 7.0 mph
            averageSpeed < 13.4 -> 11.8     // 7.5 mph
            averageSpeed < 14.2 -> 12.0     // 8.0 mph
            averageSpeed < 15.0 -> 12.5     // 8.6 mph
            averageSpeed < 16.0 -> 13.0     // 9.0 mph
            else -> 14.8                    // 9.3 ~ 10 mph
        }

        // 운동 시간 -> 시간 단위 변환
        val exercisedHours = elapsedTime / 3600000.0

        // 칼로리 계산
        val calories = met * weightKg * exercisedHours

        // 화면 표시
        calorieTv.text = "${calories.toInt()} kcal"
        Log.d("RUN_CALORIE", "평균속도: $averageSpeed km/h / MET: $met / 칼로리: ${calories.toInt()} kcal")

        // 상승고도 데이터 값 받기
        elevationGainTv.text = String.format("%.0f m", elevationGain)
    }

    // =========================
    // 러닝 경로 받기
    // =========================
    private fun getRunningPath(record: RunningRecord? = null) {
        runningPath.clear()

        // 과거 기록이면 DB에 저장된 JSON 경로 사용
        if (record != null) {
            try {
                val jsonArray = JSONArray(record.routeCoordinatesJson)

                for (i in 0 until jsonArray.length()) {
                    val point = jsonArray.optJSONArray(i) ?: continue

                    if (point.length() < 2) continue

                    val latitude = point.optDouble(0, Double.NaN)
                    val longitude = point.optDouble(1, Double.NaN)

                    if (!latitude.isFinite() || !longitude.isFinite()) {
                        continue
                    }

                    runningPath.add(
                        LatLng.from(latitude, longitude)
                    )
                }

                Log.d(
                    "RESULT_PATH",
                    "DB 경로 복원 완료: 기록 ID=${record.id}, 좌표=${runningPath.size}개"
                )
            } catch (e: Exception) {
                Log.e("RESULT_PATH", "DB 경로 JSON 파싱 실패", e)
            }

            return
        }

        // 새 러닝이면 기존 Intent 배열 사용
        val latitudes = intent.getDoubleArrayExtra("RUNNING_LATITUDES")
        val longitudes = intent.getDoubleArrayExtra("RUNNING_LONGITUDES")

        if (latitudes == null || longitudes == null) {
            Log.e("RESULT_PATH", "러닝 경로 데이터가 없습니다.")
            return
        }

        if (latitudes.size != longitudes.size) {
            Log.e("RESULT_PATH", "위도와 경도의 개수가 일치하지 않습니다.")
            return
        }

        for (i in latitudes.indices) {
            runningPath.add(
                LatLng.from(latitudes[i], longitudes[i])
            )
        }

        Log.d("RESULT_PATH", "새 러닝 경로 ${runningPath.size}개 좌표 받음")
    }

    // =========================
    // 러닝 경로 Polyline 그리기
    // =========================
    private fun drawRunningPath(kakaoMap: KakaoMap) {
        if (runningPath.size < 2) {
            Log.d("RESULT_PATH", "경로 좌표가 2개 미만입니다.")
            return
        }
        val shapeManager = kakaoMap.shapeManager

        val layer = shapeManager?.getLayer()

        if (layer == null) {
            Log.e("RESULT_PATH", "ShapeLayer를 가져올 수 없습니다.")
            return
        }

        // 기존 Polyline 제거
        runningPolyline?.remove()

        // LatLng → MapPoints
        val mapPoints = MapPoints.fromLatLng(runningPath)

        // Polyline 설정
        val options = PolylineOptions.from(
            mapPoints,
            10f,
            Color.BLUE
        )

        // 지도에 Polyline 추가
        runningPolyline = layer.addPolyline(options)
        Log.d("RESULT_PATH", "결과 화면 Polyline 그리기 완료")
    }

    private fun fitRunningPath(kakaoMap: KakaoMap) {
        if (runningPath.size < 2) {
            Log.d("RESULT_MAP", "경로가 2개 미만이라 자동 맞춤을 하지 않습니다.")
            return
        }

        // MutableList -> Array
        val points = runningPath.toTypedArray()

        /*
        * padding
        * 지도 가장자리와 러닝 경로 사이의 여백
        * 값이 클수록 경로가 지도 중앙 쪽으로 들어옴
        * 40px 정도면 현재 결과 화면에 적당한 여백
        */
        val padding = 40

        /*
         * KakaoMap SDK가
         * "이 좌표들을 모두 화면에 보여주려면
         * 카메라를 어떻게 잡아야 하는지"
         * 자동으로 계산
         */
        val cameraUpdate = CameraUpdateFactory.fitMapPoints(points, padding)
        kakaoMap.moveCamera(cameraUpdate)
        Log.d("RESULT_MAP", "러닝 경로에 맞춰 지도 자동 맞춤 완료 / " + "좌표개수 : ${runningPath.size} / " + "padding : ${padding}px")
    }

    // =========================
    // Activity 생명주기
    // =========================
    override fun onResume() {
        super.onResume()
        if (::resultMap.isInitialized) {
            resultMap.resume()
        }
    }

    override fun onPause() {
        if (::resultMap.isInitialized) {
            resultMap.pause()
        }
        super.onPause()
    }
}

