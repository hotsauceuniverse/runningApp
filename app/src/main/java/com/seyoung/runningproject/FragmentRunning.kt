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
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment

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

    // GPS를 다루기 위한 변수
    private lateinit var fusedLocationClient : FusedLocationProviderClient      // GPS 담당 (위치 정보를 가져오는 객체)
    private lateinit var locationRequest : LocationRequest                      // GPS에게 요구사항 전달 (GPS를 어떻게 받을것인지에 대한 설정값)
    private lateinit var locationCallback : LocationCallback                    // GPS가 보내준 결과를 받는 사람

    private var currentLocationLabel: Label? = null     // 현재 위치 점으로 표시
    private var previousLocation: Location? =null       // 이전 위치 저장
    private var totalDistance = 0.0     // 총 이동 거리 (단위 : meter)
    private var isRunning = false       // 러닝 상태 변수 (false = 러닝 기록 안함)

    private var previousAltitude: Double? = null    // GPS 위치의 고도 저장 변수
    private var totalElevationGain = 0.0            // 러닝하면서 올라간 고도를 누적하는 변수

    private val runningPath = mutableListOf<LatLng>()   // runningPath 리스트 만들기 (러닝 GPS 경로 저장)
    private var runningPolyline: Polyline? = null       // 지도에 표시되는 러닝 경로 선

    // 러닝 타이머
    private var startTime = 0L      // 러닝을 시작한 시각
    private var elapsedTime = 0L    // 러닝 경과 시간

    private var timerHandler = Handler(Looper.getMainLooper())
    private lateinit var timerRunnable: Runnable


    private val runningResultLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) {

            if (it.resultCode == android.app.Activity.RESULT_OK) {

                // 러닝 상태 초기화
                isRunning = false

                // 버튼 다시 시작 상태로 변경
                view?.findViewById<Button>(R.id.btn_running)?.text = "러닝 시작"

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

        // 러닝 시작 / 종료 버튼
        setupRunningButtons(view)
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
    // 러닝 시작 / 종료 버튼
    // =========================
    private fun setupRunningButtons(view: View) {
        val btnRunning = view.findViewById<Button>(R.id.btn_running)
        btnRunning.setOnClickListener {

            // =========================
            // 러닝 시작
            // =========================
            if (!isRunning) {

                isRunning = true

                // 거리 초기화
                totalDistance = 0.0

                // 이전 GPS 위치 초기화
                previousLocation = null

                // GPS 경로 초기화
                runningPath.clear()

                // 기존 지도 경로 제거
                runningPolyline?.remove()
                runningPolyline = null

                // 고도 상승 초기화
                totalElevationGain = 0.0
                previousAltitude = null

                // 타이머 시작
                startTimer()

                // 버튼 텍스트 변경
                btnRunning.text = "러닝 종료"

                Log.d("RUNNING", "러닝 시작!")
            }

            // =========================
            // 러닝 종료
            // =========================
            else {
                isRunning = false
                // 타이머 종료
                stopTimer()

                // 결과페이지 갔다가 왔을 때 버튼 상태 값 변경
                btnRunning.text = "러닝 시작"

                Log.d("RUNNING", "러닝 종료! 최종 거리: ${totalDistance}m")

                // 지도 이미지 저장
                saveMapImage(btnRunning) { imagePath ->
                    // 결과 화면 이동
                    val intent = Intent(requireContext(), RunningResultActivity::class.java)

                    // 결과 값 RunningResultActivity로 전달
                    intent.putExtra("TOTAL_DISTANCE", totalDistance)
                    Log.d("TOTAL_DISTANCE", totalDistance.toString())

                    intent.putExtra("ELAPSED_TIME", elapsedTime)
                    Log.d("ELAPSED_TIME", "${elapsedTime / 1000}초")

                    intent.putExtra("ELEVATION_GAIN", totalElevationGain)
                    Log.d("ELEVATION_GAIN", totalElevationGain.toString())

                    // 지도 이미지 경로
                    intent.putExtra("MAP_IMAGE_PATH", imagePath)

                    runningResultLauncher.launch(intent)
                }
            }
        }
    }

    // =========================
    // 타이머 시작 함수
    // =========================
    private fun startTimer() {
        // 러닝 시작 시간 저장
        startTime = System.currentTimeMillis()
        timerRunnable = object : Runnable {
            override fun run() {
                // 현재 시간 - 시작 시간
                elapsedTime = System.currentTimeMillis() - startTime

                // 밀리초 -> 초
                val totalSeconds = elapsedTime / 1000
                val hours = totalSeconds / 3600
                val minutes = (totalSeconds % 3600) / 60
                val seconds = totalSeconds % 60
                Log.d("RUN_TIMER", String.format("%02d:%02d:%02d", hours, minutes, seconds))

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

    // =========================
    // GPS 위치 업데이트
    // =========================
    private fun startLocationUpdates(kakaoMap: KakaoMap) {
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

                        // =====================
                        // 러닝 중 GPS 경로 저장
                        // =====================
                        if (isRunning) {
                            runningPath.add(currentPosition)
                            Log.d("RUNNING_PATH", "경로 저장: ${currentPosition.latitude}, ${currentPosition.longitude}")
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
                        if (isRunning) {
                            previousLocation?.let { previous ->
                                // 이전 위치 → 현재 위치 거리
                                val distance = previous.distanceTo(location)

                                // 2m 이하 GPS 오차 필터링
                                if (distance >= 2) {
                                    // 총 이동 거리 누적
                                    totalDistance += distance
                                    Log.d("RUN_DISTANCE", "이동 거리 추가: ${distance}m / " + "총 거리 : ${totalDistance}m")
                                } else {
                                    Log.d("RUN_DISTANCE", "GPS 오차 무시: ${distance}m / " + "총 거리 : ${totalDistance}m")
                                }
                            }
                        }
                        // =====================
                        // 고도 상승 계산
                        // =====================
                        if (isRunning) {
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
                    startLocationUpdates(
                        kakaoMap
                    )
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
    }

    // =========================
    // Fragment 화면 비활성화
    // =========================
    override fun onPause() {
        if (::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }

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