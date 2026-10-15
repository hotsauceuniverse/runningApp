package com.seyoung.runningproject

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.core.view.doOnLayout

import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.KakaoMapSdk
import com.kakao.vectormap.LatLng
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.MapView
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.label.Label
import com.kakao.vectormap.label.LabelOptions
import com.kakao.vectormap.shape.MapPoints
import com.kakao.vectormap.shape.Polyline
import com.kakao.vectormap.shape.PolylineOptions
import java.io.File
import java.io.FileOutputStream

import java.security.MessageDigest

class FragmentRunning : Fragment(R.layout.fragment_running) {

    private lateinit var mapView: MapView
    private var kakaoMap: KakaoMap? = null

    // GPS를 다루기 위한 변수
    private lateinit var fusedLocationClient : FusedLocationProviderClient      // GPS 담당 (위치 정보를 가져오는 객체)
    private lateinit var locationRequest : LocationRequest                      // GPS에게 요구사항 전달 (GPS를 어떻게 받을것인지에 대한 설정값)
    private lateinit var locationCallback : LocationCallback                    // GPS가 보내준 결과를 받는 사람
    private var isLocationUpdating = false                                      // GPS 중복 실행 방지

    private var currentLocationLabel: Label? = null     // 현재 위치 점으로 표시
    private var previousLocation: Location? =null       // 이전 위치 저장
    private var totalDistance = 0.0     // 총 이동 거리 (단위 : meter)
    private var isRunning = false       // 러닝 상태 변수 (false = 러닝 기록 안함)

    private var previousAltitude: Double? = null    // GPS 위치의 고도 저장 변수
    private var totalElevationGain = 0.0            // 러닝하면서 올라간 고도를 누적하는 변수

    private val runningPath = mutableListOf<LatLng>()   // runningPath 리스트 만들기 (러닝 GPS 경로 저장)
    private var runningPolyline: Polyline? = null       // 지도에 표시되는 러닝 경로 선

    // 러닝 타이머
    // 실제 움직인 시간 누적
    private var elapsedTime = 0L

    // 마지막 GPS 위치가 들어온 시간
    private var lastLocationTime = 0L

    // 현재 사용자가 움직이고 있는지
    private var isMoving = false

    // 1초마다 화면/로그 확인
    private var timerHandler = Handler(Looper.getMainLooper())

    // 1초마다 실행할 코드
    private lateinit var timerRunnable: Runnable

    // Bottom Sheet 및 화면 요소
    private lateinit var bottomSheet: View
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>
    private lateinit var modeSelector: View
    private lateinit var recommendedRouteContainer: View
    private lateinit var freeRunningContainer: View
    private lateinit var startRunningButton: Button
    private lateinit var runningActiveContainer: View
    private lateinit var pauseRunningButton: Button
    private lateinit var stopRunningButton: Button
    private lateinit var runningDistanceText: TextView
    private lateinit var runningTimeText: TextView
    private lateinit var runningPaceText: TextView

    // 추천 경로 / 자유 러닝 선택 상태
    private var isRecommendedMode = true
    private var targetDistanceKm: Double? = null
    private var isPaused = false
    private var hasShownTargetDistanceNotice = false


    private val runningResultLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) {

            if (it.resultCode == android.app.Activity.RESULT_OK) {

                // 결과 화면에서 돌아오면 러닝 UI 초기화
                isRunning = false
                isPaused = false
                view?.let { resetPreRunUi(it) }

                Log.d("RUNNING", "결과 화면에서 돌아옴 → 러닝 초기화")
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // =========================
        // GPS 위치 서비스 초기화
        // =========================

        fusedLocationClient =
            LocationServices.getFusedLocationProviderClient(
                requireContext()
            )

        // 약 1초마다 GPS 위치를 받도록 설정
        locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            1000L
        )
            .setMinUpdateIntervalMillis(500L)
            .build()

        // =========================
        // 카카오맵 SDK 초기화
        // =========================

        KakaoMapSdk.init(
            requireContext(),
            BuildConfig.KAKAO_MAP_KEY
        )

        // =========================
        // MapView 가져오기
        // =========================

        mapView = view.findViewById(
            R.id.map_view
        )

        // 카카오맵 시작
        kakaoMapLoad()

        // 앱 해시 키 확인
        getAppKeyHash()

        // Bottom Sheet 초기화 및 버튼 이벤트 연결
        setupBottomSheet(view)
    }

    // =========================
    // 지도 이미지 저장
    // =========================
    private fun saveMapImage(btnRunning: Button, onComplete: (String?) -> Unit)  {

        // 지도 캡쳐 전 러닝 버튼 숨김
        btnRunning.visibility = View.INVISIBLE

        // 버튼이 화면에서 사라진 후 캡쳐
        mapView.post {
            val bitmap = Bitmap.createBitmap(
                mapView.width,
                mapView.height,
                Bitmap.Config.ARGB_8888
            )
            val location = IntArray(2)

            mapView.getLocationOnScreen(location)

            Log.d("MAP_CAPTURE", """MapView 위치X = ${location[0]}Y = ${location[1]}MapView 크기 Width = ${mapView.width}Height = ${mapView.height}""".trimIndent())

            val rect = Rect(
                location[0],
                location[1],
                location[0] + mapView.width,
                location[1] + mapView.height
            )

            // PixelCopy.request()는 API 26(Android 8.0)이상 사용 가능
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PixelCopy.request(
                    requireActivity().window,
                    rect,
                    bitmap,
                    { result ->

                        // 지도 캡쳐 완료 후 버튼 다시 표시
                        btnRunning.visibility = View.VISIBLE

                        if (result == PixelCopy.SUCCESS) {
                            try{
                                val file = File(
                                    requireContext().cacheDir,
                                    "running_map.png"
                                )
                                FileOutputStream(file).use { outputStream ->
                                    bitmap.compress(
                                        Bitmap.CompressFormat.PNG,
                                        100,
                                        outputStream
                                    )
                                }
                                Log.d("MAP_IMAGE_PATH", file.absolutePath)
                                onComplete(file.absolutePath)
                            } catch (e: Exception) {
                                Log.e("MAP_IMAGE_ERROR", "이미지 저장 실패", e)
                                onComplete(null)
                            }
                        } else {
                            Log.d("MAP_IMAGE", "이미지 저장 실패 : $result")
                            onComplete(null)
                        }
                    },
                    Handler(Looper.getMainLooper())
                )
            } else {
                Log.e("MAP_IMAGE", "PixelCopy는 Android 8.0 이상에서만 사용할 수 있습니다.")
                onComplete(null)
            }
        }
    }

    // =========================
    // 선 그리기
    // =========================
    private fun drawRunningPath(kakaoMap: KakaoMap) {
        // 테스트용 임시 좌표
//        val testPath = listOf(
//            LatLng.from(37.5220908, 126.9198282),
//            LatLng.from(37.5225000, 126.9200000),
//            LatLng.from(37.5228000, 126.9205000),
//            LatLng.from(37.5231000, 126.9210000)
//        )

        // 선을 그리려면 최소 GPS 좌표가 2개 필요
        if (runningPath.size < 2) {
            return
        }

        val shapeManager = kakaoMap.shapeManager
        val layer = shapeManager?.getLayer()

        if (layer == null) {
            Log.e("RUNNING_PATH", "ShapeLayer를 가져올 수 없습니다.")
            return
        }

        // 기존 선이 있으면 제거
        runningPolyline?.remove()

        // 테스트 좌표 → MapPoints 변환
//        val mapPoints = MapPoints.fromLatLng(testPath)

        // 실제 GPS 경로 좌표를 MapPoints로 변환
        val mapPoints = MapPoints.fromLatLng(runningPath)

        // Polyline 옵션 생성
        val options = PolylineOptions.from(
            mapPoints,
            10f,
            Color.BLUE
        )

        // 지도에 선 추가
        runningPolyline = layer.addPolyline(options)
        Log.d("RUNNING_PATH", "테스트 경로 그리기 완료 / 좌표 개수: ${runningPath.size}")
    }

    // =========================
    // Bottom Sheet 초기화
    // =========================
    private fun setupBottomSheet(view: View) {
        bottomSheet = view.findViewById(R.id.running_bottom_sheet)

        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet).apply {
            isFitToContents = true
            isHideable = false
            peekHeight = dpToPx(130)    // BottomSheet를 접었을 때 노출 높이
        }

        // 실제 레이아웃 측정이 끝난 후 상태 설정
        bottomSheet.doOnLayout { sheet ->
            Log.d("BOTTOM_SHEET", "초기화 확인: " + "height=${sheet.height}, " + "measuredHeight=${sheet.measuredHeight}, " + "visibility=${sheet.visibility}")

            if (sheet.height > 0) {
                bottomSheetBehavior.state =
                    BottomSheetBehavior.STATE_COLLAPSED
            } else {
                Log.e("BOTTOM_SHEET", "레이아웃 높이가 여전히 0입니다.")
            }
        }

        modeSelector = view.findViewById(R.id.running_mode_selector)
        recommendedRouteContainer = view.findViewById(R.id.recommended_route_container)
        freeRunningContainer = view.findViewById(R.id.free_running_container)
        startRunningButton = view.findViewById(R.id.btn_start_running)
        runningActiveContainer = view.findViewById(R.id.running_active_container)
        pauseRunningButton = view.findViewById(R.id.btn_pause_running)
        stopRunningButton = view.findViewById(R.id.btn_stop_running)
        runningDistanceText = view.findViewById(R.id.tv_running_distance)
        runningTimeText = view.findViewById(R.id.tv_running_time)
        runningPaceText = view.findViewById(R.id.tv_running_pace)

        view.findViewById<TextView>(R.id.btn_mode_recommended).setOnClickListener {
            if (!isRunning) {
                isRecommendedMode = true
                updatePreRunMode(view)
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }

        view.findViewById<TextView>(R.id.btn_mode_free).setOnClickListener {
            if (!isRunning) {
                isRecommendedMode = false
                updatePreRunMode(view)
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }

        view.findViewById<Button>(R.id.btn_target_none).setOnClickListener {
            setTargetDistance(null, view)
        }
        view.findViewById<Button>(R.id.btn_target_3km).setOnClickListener {
            setTargetDistance(3.0, view)
        }
        view.findViewById<Button>(R.id.btn_target_5km).setOnClickListener {
            setTargetDistance(5.0, view)
        }
        view.findViewById<Button>(R.id.btn_target_10km).setOnClickListener {
            setTargetDistance(10.0, view)
        }

        view.findViewById<Button>(R.id.btn_other_route).setOnClickListener {
            Toast.makeText(
                requireContext(),
                "추천 경로 기능은 다음 단계에서 연결할게요.",
                Toast.LENGTH_SHORT
            ).show()
        }

        startRunningButton.setOnClickListener {
            startRunning(view)
        }

        pauseRunningButton.setOnClickListener {
            if (isRunning) {
                isPaused = !isPaused
                previousLocation = null
                lastLocationTime = 0L
                isMoving = false
                pauseRunningButton.text = if (isPaused) "러닝 재개" else "일시정지"
                view.findViewById<TextView>(R.id.tv_running_active_title).text =
                    if (isPaused) "일시정지 중" else "러닝 중"
                updateRunningStats()
                Toast.makeText(
                    requireContext(),
                    if (isPaused) "러닝을 일시정지했어요." else "러닝을 재개했어요.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        stopRunningButton.setOnClickListener {
            if (isRunning) finishRunning()
        }

        updatePreRunMode(view)
        setTargetDistance(null, view)
        updateRunningStats()
    }

    private fun updatePreRunMode(view: View) {
        recommendedRouteContainer.visibility =
            if (isRecommendedMode) View.VISIBLE else View.GONE
        freeRunningContainer.visibility =
            if (isRecommendedMode) View.GONE else View.VISIBLE

        val recommendedButton = view.findViewById<TextView>(R.id.btn_mode_recommended)
        val freeButton = view.findViewById<TextView>(R.id.btn_mode_free)

        recommendedButton.setBackgroundColor(
            if (isRecommendedMode) Color.WHITE else Color.TRANSPARENT
        )
        freeButton.setBackgroundColor(
            if (!isRecommendedMode) Color.WHITE else Color.TRANSPARENT
        )
        recommendedButton.alpha = if (isRecommendedMode) 1f else 0.7f
        freeButton.alpha = if (!isRecommendedMode) 1f else 0.7f

        view.findViewById<TextView>(R.id.tv_running_title).text =
            if (isRecommendedMode) "오늘 어떻게 뛸까요?" else "내 맘대로 러닝"
        startRunningButton.text =
            if (isRecommendedMode) "이 경로로 러닝 시작" else "러닝 시작"
    }

    private fun setTargetDistance(distanceKm: Double?, view: View) {
        targetDistanceKm = distanceKm
        val options = listOf(
            R.id.btn_target_none to (distanceKm == null),
            R.id.btn_target_3km to (distanceKm == 3.0),
            R.id.btn_target_5km to (distanceKm == 5.0),
            R.id.btn_target_10km to (distanceKm == 10.0)
        )
        options.forEach { (id, selected) ->
            view.findViewById<Button>(id).alpha = if (selected) 1f else 0.65f
        }
    }

    private fun startRunning(view: View) {
        if (isRunning) return

        isRunning = true
        isPaused = false
        totalDistance = 0.0
        hasShownTargetDistanceNotice = false
        elapsedTime = 0L
        lastLocationTime = 0L
        isMoving = false
        previousLocation = null
        runningPath.clear()
        runningPolyline?.remove()
        runningPolyline = null
        totalElevationGain = 0.0
        previousAltitude = null

        modeSelector.visibility = View.GONE
        view.findViewById<TextView>(R.id.tv_running_title).visibility = View.GONE
        recommendedRouteContainer.visibility = View.GONE
        freeRunningContainer.visibility = View.GONE
        startRunningButton.visibility = View.GONE
        runningActiveContainer.visibility = View.VISIBLE
        pauseRunningButton.text = "일시정지"
        view.findViewById<TextView>(R.id.tv_running_active_title).text = "러닝 중"

        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        updateRunningStats()
        startTimer()

        Log.d("RUNNING", "러닝 시작 / 모드: ${if (isRecommendedMode) "추천 경로" else "내 맘대로"} / 목표: ${targetDistanceKm ?: "제한 없음"}")
    }

    private fun finishRunning() {
        isRunning = false
        isPaused = false
        stopTimer()

        Log.d("RUNNING", "러닝 종료! 최종 거리: ${totalDistance}m")
        Log.d("RUNNING_PATH", "종료 시 좌표 개수: ${runningPath.size}")

        val intent = Intent(requireContext(), RunningResultActivity::class.java)
        intent.putExtra("TOTAL_DISTANCE", totalDistance)
        intent.putExtra("ELAPSED_TIME", elapsedTime)
        intent.putExtra("ELEVATION_GAIN", totalElevationGain)

        val latitudes = runningPath.map { it.latitude }.toDoubleArray()
        val longitudes = runningPath.map { it.longitude }.toDoubleArray()
        intent.putExtra("RUNNING_LATITUDES", latitudes)
        intent.putExtra("RUNNING_LONGITUDES", longitudes)

        runningResultLauncher.launch(intent)
    }

    private fun resetPreRunUi(view: View) {
        modeSelector.visibility = View.VISIBLE
        view.findViewById<TextView>(R.id.tv_running_title).visibility = View.VISIBLE
        startRunningButton.visibility = View.VISIBLE
        runningActiveContainer.visibility = View.GONE
        pauseRunningButton.text = "일시정지"
        updatePreRunMode(view)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    private fun updateRunningStats() {
        if (!::runningDistanceText.isInitialized) return

        val distanceKm = totalDistance / 1000.0
        runningDistanceText.text = String.format("%.2f km", distanceKm)

        val totalSeconds = elapsedTime / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        runningTimeText.text = if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }

        runningPaceText.text = if (distanceKm > 0.0 && totalSeconds > 0) {
            val paceSeconds = (totalSeconds / distanceKm).toInt()
            String.format("%d'%02d\"/km", paceSeconds / 60, paceSeconds % 60)
        } else {
            "--'--\"/km"
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    // =========================
    // 타이머 시작 함수
    // =========================
    private fun startTimer() {
        timerRunnable = object  : Runnable {
            override fun run() {
                // 현재까지 누적된 실제 이동 시간 표시
                val totalSeconds = elapsedTime / 1000

                val hours = totalSeconds / 3600
                val minutes = (totalSeconds % 3600) / 60
                val seconds = totalSeconds % 60
                Log.d("RUN_TIMER", String.format("%02d:%02d:%02d", hours, minutes, seconds))
                updateRunningStats()

                // 1초 후 다시 실행
                timerHandler.postDelayed(this, 1000)
            }
        }
        timerHandler.post(timerRunnable)
    }

    // =========================
    // 타이머 종료 함수
    // =========================
    private fun stopTimer() {
        timerHandler.removeCallbacks(timerRunnable)
        Log.d("RUN_TIMER", "최종 러닝 시간 : ${elapsedTime / 1000}초")
    }

    // =========================
    // 현재 위치 마커 생성
    // =========================
    private fun createCurrentLocationMarker(kakaoMap: KakaoMap, position: LatLng) {
        val labelManager = kakaoMap.labelManager
        if (labelManager == null) {
            Log.e("MARKER", "LabelManager가 null")
            return
        }

        val layer = labelManager.getLayer()
        if (layer == null) {
            Log.e("MARKER", "LabelLayer가 null")
            return
        }

        // 🔵 현재 위치 Bitmap 생성
        val currentLocationBitmap = createCurrentLocationBitmap()

        // Label 생성
        val options = LabelOptions.from("current_location", position).setStyles(currentLocationBitmap)

        currentLocationLabel = layer.addLabel(options)
        Log.d("MARKER", "현재 위치 마커 생성 완료: $position")
    }

    // =========================
    // 🔵 현재 위치 Bitmap
    // =========================
    private fun createCurrentLocationBitmap(): Bitmap {
        val size = 60
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)

        val canvas = Canvas(bitmap)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 바깥쪽 흰색 원
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL

        canvas.drawCircle(size / 2f, size / 2f, 22f, paint)

        // 안쪽 파란색 원
        paint.color = Color.rgb(66, 133, 244)

        canvas.drawCircle(size / 2f, size / 2f, 16f, paint)

        return bitmap
    }

    // =====================================
    // GPS 위치 업데이트 (실제 시간 계산하여 증가)
    // =====================================
    private fun startLocationUpdates(kakaoMap: KakaoMap) {
        // 이미 GPS 업데이트 중이면 중복 실행하지 않음
        if (isLocationUpdating) {
            return
        }

        if (ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION
                ),
                100
            )
            return
        }

        // GPS 업데이트 시작 상태
        isLocationUpdating = true

        // 실시간 위치 업데이트 받기
        locationCallback =
            object : LocationCallback() {

                override fun onLocationResult(
                    locationResult: LocationResult
                ) {
                    for (
                    location in locationResult.locations
                    ) {
                        val latitude = location.latitude
                        val longitude = location.longitude
                        Log.d("GPS", "위도 : $latitude, 경도 : $longitude")

                        // GPS 좌표 만들기
                        val currentPosition = LatLng.from(latitude, longitude)

                        // =================================
                        // 러닝 중 GPS 경로 저장 + 이동 시간 계산
                        // =================================
                        if (isRunning && !isPaused) {
                            // 현재 GPS가 들어온 시간
                            val currentLocationTime = location.elapsedRealtimeNanos / 1_000_000L

                            // 이전 GPS 위치가 있는 경우
                            if (previousLocation != null) {
                                // 이전 GPS -> 현재 GPS 거리
                                val distance = previousLocation !!.distanceTo(location)

                                // 1m 이상 이동했으면 움직이는 중
                                isMoving = distance >= 1.0f

                                // 이전 GPS 시간도 존재한다면
                                if (lastLocationTime != 0L) {
                                    // 이전 GPS -> 현재 GPS 사이의 시간 (현재시간 - 이전시간)
                                    val deltaTime = currentLocationTime - lastLocationTime

                                    // 움직이고 있을 때만 시간 누적
                                    if (isMoving && deltaTime > 0L) {
                                        elapsedTime += deltaTime
                                        Log.d("RUN_TIMER", "이동 시간 추가 : ${deltaTime}ms / " + "총 이동 시간 : ${elapsedTime}ms")
                                    }
                                }
                            }
                            // 현재 GPS 시간을 다음 GPS의 이전 시간으로 저장
                            lastLocationTime = currentLocationTime

                            // GPS 경로 저장
                            runningPath.add(currentPosition)
                            Log.d("RUNNING_PATH", "경로 저장 : ${currentPosition.latitude}, " + "${currentPosition.longitude}")

                            // 현재까지 저장된 GPS 경로를 지도에 선으로 표시
                            drawRunningPath(kakaoMap)
                        }

                        // 현재 위치로 지도 이동
                        kakaoMap.moveCamera(
                            CameraUpdateFactory.newCenterPosition(currentPosition)
                        )

                        // 🔵 현재 위치 표시
                        if (
                            currentLocationLabel == null
                        ) {
                            // 최초 위치 → 마커 생성
                            createCurrentLocationMarker(kakaoMap, currentPosition)

                        } else {
                            // 이후 위치 → 기존 마커 이동
                            currentLocationLabel?.moveTo(currentPosition)
                        }

                        // 이전 위치와 현재 위치 확인
                        Log.d("GPS_COMPARE", "이전 위치: " + "${previousLocation?.latitude}, " + "${previousLocation?.longitude} / " + "현재 위치: " + "${location.latitude}, " + "${location.longitude}")

                        // =====================
                        // 러닝 중일 경우 거리 계산
                        // =====================
                        if (isRunning && !isPaused) {
                            previousLocation?.let { previous ->
                                // 이전 위치 → 현재 위치 거리
                                val distance = previous.distanceTo(location)

                                // 2m 이하 GPS 오차 필터링
                                if (distance >= 2) {
                                    // 총 이동 거리 누적
                                    totalDistance += distance
                                    Log.d("RUN_DISTANCE", "이동 거리 추가: ${distance}m / " + "총 거리 : ${totalDistance}m")

                                    val targetMeters = if (!isRecommendedMode) targetDistanceKm?.times(1000.0) else null
                                    if (
                                        !hasShownTargetDistanceNotice &&
                                        targetMeters != null &&
                                        totalDistance >= targetMeters
                                    ) {
                                        hasShownTargetDistanceNotice = true
                                        Toast.makeText(
                                            requireContext(),
                                            "목표 거리 달성! 원하는 만큼 더 달려도 좋아요.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                    updateRunningStats()
                                } else {
                                    Log.d("RUN_DISTANCE", "GPS 오차 무시: ${distance}m / " + "총 거리 : ${totalDistance}m")
                                }
                            }
                        }
                        // =====================
                        // 고도 상승 계산
                        // =====================
                        if (isRunning && !isPaused) {
                            val currentAltitude = location.altitude
                            previousAltitude?.let { previous ->
                                val altitudeDifference = currentAltitude - previous

                                // 상승한 경우만 누적
                                if (altitudeDifference >= 2.0) {
                                    totalElevationGain += altitudeDifference

                                    Log.d("ELEVATION", "고도 상승 : ${altitudeDifference}m / " + "총 상승 : ${totalElevationGain}m")
                                }
                            }
                            previousAltitude = currentAltitude
                        }

                        // =====================
                        // 현재 위치를 이전 위치로 저장
                        // =====================
                        previousLocation = location
                        Log.d("PREVIOUS_GPS", "저장된 이전 위치 → " + "위도: ${previousLocation?.latitude}, " + "경도: ${previousLocation?.longitude}")
                    }
                }
            }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            requireActivity().mainLooper
        )
    }

    // =========================
    // 카카오맵 로드
    // =========================
    private fun kakaoMapLoad() {
        mapView.start(
            object : MapLifeCycleCallback() {
                override fun onMapDestroy() {
                    Log.d("KakaoMap", "지도 종료")
                }

                override fun onMapError(error: Exception) {
                    Log.e("KAKAO_MAP_ERROR", "지도 에러 발생: ${error.message}", error)
                }
            },
            object : KakaoMapReadyCallback() {
                override fun onMapReady(kakaoMap: KakaoMap) {
                    Log.d("KakaoMap", "지도 준비 완료")
                    this@FragmentRunning.kakaoMap = kakaoMap
                    startLocationUpdates(kakaoMap)
                }

                override fun getPosition(): LatLng {
                    return LatLng.from(37.5665, 126.9780)
                }

                override fun getZoomLevel(): Int {
                    return 15
                }
            }
        )
    }

    // =========================
    // Fragment 화면 활성화
    // =========================
    override fun onResume() {
        super.onResume()

        if (::mapView.isInitialized) {
            mapView.resume()
        }

        // 다시 화면으로 돌아오면 GPS 재시작
        kakaoMap?.let {
            startLocationUpdates(it)
        }
    }

    // =========================
    // Fragment 화면 비활성화
    // =========================
    override fun onPause() {
        // 러닝 중이 아닐 때만 GPS 종료
        if (!isRunning && ::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            isLocationUpdating = false
        }

        // 지도는 일단 pause
        if (::mapView.isInitialized) {
            mapView.pause()
        }

        super.onPause()
    }

    // =========================
    // 앱 해시 키 얻기
    // =========================
    private fun getAppKeyHash() {
        try {
            val packageInfo =
                requireActivity()
                    .packageManager
                    .getPackageInfo(
                        requireActivity().packageName,
                        PackageManager.GET_SIGNING_CERTIFICATES
                    )

            val signatures =
                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.P
                ) {
                    packageInfo
                        .signingInfo
                        ?.apkContentsSigners
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.signatures
                }

            signatures?.forEach { signature ->
                val md = MessageDigest.getInstance("SHA")
                md.update(signature.toByteArray())

                val hashKey = Base64.encodeToString(md.digest(), Base64.NO_WRAP)

                Log.e("Hash key", hashKey)
            }

        } catch (e: Exception) {
            Log.e(
                "name not found",
                e.toString()
            )
        }
    }
}