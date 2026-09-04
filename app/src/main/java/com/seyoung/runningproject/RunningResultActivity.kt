package com.seyoung.runningproject

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import android.widget.TextView

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
import com.kakao.vectormap.camera.CameraUpdateFactory

class RunningResultActivity : AppCompatActivity() {

    private lateinit var kmTv: TextView
    private lateinit var averagePaceTv: TextView
    private lateinit var timeTv: TextView
    private lateinit var calorieTv: TextView
    private lateinit var elevationGainTv: TextView
    private lateinit var cadenceTv: TextView
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
        cadenceTv = findViewById(R.id.cadence_tv)
        averageSpeedTv = findViewById(R.id.averageSpeed_tv)

        // Kakao MapView 연결
        resultMap = findViewById(R.id.result_map_image)
        KakaoMapSdk.init(
            this,
            BuildConfig.KAKAO_MAP_KEY
        )

        // 러닝 데이터
        runningData()

        // 러닝 경로 받기
        getRunningPath()

        // 지도 시작
        resultMap.start(
            object : MapLifeCycleCallback() {
                override fun onMapDestroy() {
                    Log.d("RESULT_MAP", "결과 지도 종료")
                }

                override fun onMapError(error: Exception) {
                    Log.e("RESULT_MAP", "결과 지도 에러: ${error.message}",
                        error
                    )
                }
            },
            object : KakaoMapReadyCallback() {
                override fun onMapReady(kakaoMap: KakaoMap) {
                    Log.d("RESULT_MAP", "결과 지도 준비 완료")
                    this@RunningResultActivity.kakaoMap = kakaoMap
                    drawRunningPath(kakaoMap)
                }

                override fun getPosition(): LatLng {
                    // 경로가 있으면 첫 번째 위치
                    if (runningPath.isNotEmpty()) {
                        return runningPath.first()
                    }

                    // 경로가 없으면 서울 시청
                    return LatLng.from(
                        37.5665,
                        126.9780
                    )
                }
                override fun getZoomLevel(): Int {
                    return 15
                }
            }
        )
    }

    // =========================
    // 러닝 결과 데이터 화면 표시
    // =========================
    private fun runningData() {
        // 데이터 받기
        val totalDistance = intent.getDoubleExtra("TOTAL_DISTANCE", 0.0)
        val elapsedTime = intent.getLongExtra("ELAPSED_TIME", 0L)
        Log.d("elapsedTime", "${elapsedTime / 1000}초")
        val elevationGain = intent.getDoubleExtra("ELEVATION_GAIN", 0.0)

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

        // 러닝 지도 이미지 표시
        val imagePath = intent.getStringExtra("MAP_IMAGE_PATH")
        Log.d("MAP_RESULT", "받은 이미지 경로 = $imagePath")
    }

    // =========================
    // 러닝 경로 받기
    // =========================
    private fun getRunningPath() {
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
        runningPath.clear()

        for (i in latitudes.indices) {
            runningPath.add(
                LatLng.from(
                    latitudes[i], longitudes[i]
                )
            )
        }
        Log.d("RESULT_PATH", "러닝 경로 ${runningPath.size}개 좌표 받음")
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


        // 첫 번째 위치로 카메라 이동
        val firstPosition = runningPath.first()

        kakaoMap.moveCamera(CameraUpdateFactory.newCenterPosition(firstPosition)
        )
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

