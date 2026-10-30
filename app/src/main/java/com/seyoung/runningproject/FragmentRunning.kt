package com.seyoung.runningproject

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.widget.EditText
import com.google.android.material.bottomsheet.BottomSheetBehavior
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import android.os.SystemClock

import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import com.seyoung.runningproject.data.RunningDatabase
import com.seyoung.runningproject.data.RunningRecord

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

import java.security.MessageDigest
import java.util.Date
import java.util.Locale
import java.text.SimpleDateFormat
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

import org.json.JSONArray
import org.json.JSONObject

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

    // API에서 조회한 추천 경로는 실제 러닝 경로와 별도 관리
    private var recommendedPolyline: Polyline? = null
    private var isRouteRequesting = false

    // 추천 경로 출발지·도착지 마커
    private var routeStartLabel: Label? = null
    private var routeEndLabel: Label? = null

    // 장소 검색으로 선택한 출발지와 도착지
    private var routeStartLatLng: LatLng? = null
    private var routeEndLatLng: LatLng? = null

    private var routeStartName = ""
    private var routeEndName = ""

    // 추천 경로 옵션
    private data class RecommendedRoute(
        val mode: String,
        val points: List<LatLng>,
        val distanceMeters: Double,
        val durationSeconds: Long
    )

    private var recommendedRoutes: List<RecommendedRoute> = emptyList()
    private var selectedRouteIndex = 0

    // 러닝 시작 시 선택한 추천 경로 보관
    private var activeRecommendedRoute: RecommendedRoute? = null

    // 전체 러닝 시간
    // 시작부터 종료까지, 직접 일시정지한 시간만 제외
    private var elapsedTime = 0L

    // 러닝 시간 측정 기준 (단조 증가 시계)
    private var runningTimeStartedAt = 0L

    // 러닝 시작 시각 (날짜 및 시각 저장용)
    private var runningStartedAt = 0L

    // 마지막 GPS 위치가 들어온 시간
    private var lastLocationTime = 0L

    // 현재 사용자가 움직이고 있는지
    private var isMoving = false

    // 연속으로 정지 신호가 감지된 횟수
    private var consecutiveStoppedSamples = 0

    // 마지막으로 지도 경로에 저장한 위치
    private var lastPathLocation: Location? = null

    // 1초마다 화면/로그 확인
    private var timerHandler = Handler(Looper.getMainLooper())

    // 1초마다 실행할 코드
    private lateinit var timerRunnable: Runnable

    // 거리 계산용 위치
    private var previousDistanceLocation: Location? = null

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

    // 현위치 중심으로 카메라를 자동 이동할지 여부
    private var isCameraFollowingCurrentLocation = true

    private val runningResultLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) {
            // 결과 화면에서 돌아오면 러닝 상태 초기화
            isRunning = false
            isPaused = false
            previousLocation = null
            lastLocationTime = 0L
            isMoving = false

            // 이전 러닝의 파란색 경로 제거
            runningPolyline?.remove()
            runningPolyline = null

            // 이전 러닝의 GPS 경로 좌표 초기화
            runningPath.clear()

            // 다음 러닝을 위해 러닝 데이터 초기화
            totalDistance = 0.0
            elapsedTime = 0L
            totalElevationGain = 0.0
            previousAltitude = null

            view?.let { currentView ->
                resetPreRunUi(currentView)
                updateRunningStats()
            }
            Log.d("RUNNING", "결과 화면에서 돌아옴 → 러닝 상태 및 파란 경로 초기화")
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

    // 지정된 출발지와 도착지로 도보 경로 요청
    private fun requestWalkingRoute(startPoint: LatLng, endPoint: LatLng) {
        if (isRouteRequesting) return

        val apiKey = BuildConfig.KAKAO_REST_API_KEY
        isRouteRequesting = true

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val routes = withContext(Dispatchers.IO) {
                    val modes = listOf(
                        "BROAD_FIRST",
                        "SHORTEST",
                        "ACCESSIBLE"
                    )

                    val foundRoutes = mutableListOf<RecommendedRoute>()
                    val signatures = mutableSetOf<String>()

                    for (mode in modes) {
                        try {
                            val result = fetchWalkingRoute(
                                startPoint = startPoint,
                                endPoint = endPoint,
                                routeMode = mode,
                                apiKey = apiKey
                            )

                            if (result.points.size < 2) continue

                            val signature = routeSignature(result.points)

                            if (signatures.add(signature)) {
                                foundRoutes.add(
                                    RecommendedRoute(
                                        mode = when (mode) {
                                            "BROAD_FIRST" -> "넓은 길 우선"
                                            "SHORTEST" -> "최단 경로"
                                            "ACCESSIBLE" -> "편안한 길"
                                            else -> "도보"
                                        },
                                        points = result.points,
                                        distanceMeters = result.distanceMeters,
                                        durationSeconds = result.durationSeconds
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            Log.w("WALK_API", "$mode 경로 조회 실패: ${e.message}")
                        }
                    }
                    foundRoutes
                }

                if (routes.isEmpty()) {
                    Toast.makeText(requireContext(), "도보 경로를 찾지 못했어요. 출발지와 도착지를 확인해 주세요.", Toast.LENGTH_LONG).show()
                    return@launch
                }

                val map = kakaoMap
                if (map == null) {
                    Toast.makeText(requireContext(), "지도가 아직 준비되지 않았어요.", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // 경로 조회가 성공했으므로 현위치 카메라 추적 중지
                isCameraFollowingCurrentLocation = false

                recommendedPolyline?.remove()
                recommendedPolyline = null

                recommendedRoutes = routes
                selectedRouteIndex = 0
                activeRecommendedRoute = routes.first()

                displayRecommendedRoute(map, routes.first())

//                Toast.makeText(requireContext(), "서로 다른 도보 경로 ${routes.size}개를 찾았어요.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("WALK_API", "도보 경로 조회 실패", e)

                Toast.makeText(requireContext(), "경로 조회 실패: ${e.message ?: "알 수 없는 오류"}", Toast.LENGTH_LONG).show()
            } finally {
                isRouteRequesting = false
            }
        }
    }

    // =========================
    // 다른 경로 보기 기능 추가
    // =========================
    private fun showNextRecommendedRoute() {
        if (recommendedRoutes.size <= 1) {
            Toast.makeText(requireContext(), "다른 도보 경로가 없어요.", Toast.LENGTH_SHORT).show()
            return
        }

        val map = kakaoMap
        if (map == null) {
            Toast.makeText(requireContext(), "지도가 아직 준비되지 않았어요.", Toast.LENGTH_SHORT).show()
            return
        }
        selectedRouteIndex = (selectedRouteIndex + 1) % recommendedRoutes.size

        val selectedRoute = recommendedRoutes[selectedRouteIndex]

        recommendedPolyline?.remove()
        recommendedPolyline = null

        displayRecommendedRoute(map, selectedRoute)

//        Toast.makeText(requireContext(), "경로 ${selectedRouteIndex + 1}/${recommendedRoutes.size}: ${selectedRoute.mode}", Toast.LENGTH_SHORT).show()
    }

    // =========================
    // 도보 경로 API 요청 함수
    // =========================
    private fun fetchWalkingRoute(startPoint: LatLng, endPoint: LatLng, routeMode: String, apiKey: String): WalkingRouteResult {
        val startLat = startPoint.latitude
        val startLon = startPoint.longitude
        val endLat = endPoint.latitude
        val endLon = endPoint.longitude

        val url = URL(
            "https://dapi.kakao.com/v2/routing/walk" +
                    "?start_x=$startLon" +
                    "&start_y=$startLat" +
                    "&end_x=$endLon" +
                    "&end_y=$endLat" +
                    "&route_mode=$routeMode"
        )

        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty(
                "Authorization",
                "KakaoAK $apiKey"
            )
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val response = stream
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            if (statusCode !in 200..299) {
                throw Exception("HTTP $statusCode: $response")
            }

            Log.d("WALK_API_RESPONSE", "탐색 옵션: $routeMode, 응답: $response")
            return parseWalkingRoute(response)
        } finally {
            connection.disconnect()
        }
    }

    // ==================
    // 경로 표시 함수
    // ==================
    private fun displayRecommendedRoute(map: KakaoMap, route: RecommendedRoute) {
        activeRecommendedRoute = route

        drawRecommendedPath(map, route.points)

        // API에서 받은 실제 경로 거리 표시
        view?.findViewById<TextView>(
            R.id.tv_recommended_distance
        )?.text = String.format(
            Locale.KOREA,
            "%.2f km",
            route.distanceMeters / 1000.0
        )

        // API에서 받은 예상 소요 시간 표시
        val totalMinutes = (route.durationSeconds + 59) / 60
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60

        val timeText = if (hours > 0) {
            "${hours}시간 ${minutes}분"
        } else {
            "${minutes}분"
        }

        view?.findViewById<TextView>(
            R.id.tv_recommended_time
        )?.text = timeText

        view?.findViewById<TextView>(
            R.id.tv_recommended_elevation
        )?.text = "정보 없음"

        drawRouteMarkers(map, route.points)
    }

    // =========================
    // 중복 경로 판별 함수
    // =========================
    private fun routeSignature(points: List<LatLng>): String {
        if (points.isEmpty()) return ""

        // 경로 전체에서 시작·중간·끝 지점 등 최대 7개 지점을 비교
        val sampleCount = minOf(7, points.size)

        return (0 until sampleCount).joinToString("|") { index ->
            val pointIndex = if (sampleCount == 1) {
                0
            } else {
                index * (points.size - 1) / (sampleCount - 1)
            }

            val point = points[pointIndex]

            "${"%.4f".format(java.util.Locale.US, point.latitude)}," +
                    "${"%.4f".format(java.util.Locale.US, point.longitude)}"
        }
    }

    // =========================
    // 장소 검색 API 함수
    // =========================
    private data class PlaceResult(
        val name: String,
        val address: String,
        val latitude: Double,
        val longitude: Double
    )

    // =========================
    // 카카오 로컬 API로 장소 검색
    // =========================
    private fun searchPlace(query: String): PlaceResult {
        val apiKey = BuildConfig.KAKAO_REST_API_KEY
        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        val url = URL(
            "https://dapi.kakao.com/v2/local/search/keyword.json" +
                    "?query=$encodedQuery"
        )

        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty(
                "Authorization",
                "KakaoAK $apiKey"
            )
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val statusCode = connection.responseCode

            val stream = if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val response = stream?.bufferedReader()?.use {
                it.readText()
            }.orEmpty()

            if (statusCode !in 200..299) {
                throw Exception("장소 검색 HTTP $statusCode: $response")
            }

            val documents = JSONObject(response)
                .optJSONArray("documents")
                ?: throw Exception("장소 검색 결과를 읽을 수 없습니다.")

            if (documents.length() == 0) {
                throw Exception("'$query' 검색 결과가 없습니다.")
            }

            // 첫 번째 검색 결과 사용
            val place = documents.getJSONObject(0)

            val longitude = place.getString("x").toDouble()
            val latitude = place.getString("y").toDouble()

            return PlaceResult(
                name = place.optString("place_name", query),
                address = place.optString("road_address_name")
                    .ifBlank {
                        place.optString("address_name")
                    },
                latitude = latitude,
                longitude = longitude
            )
        } finally {
            connection.disconnect()
        }
    }

    // =========================
    // 도보 경로 조회 결과
    // =========================
    private data class WalkingRouteResult(
        val points: List<LatLng>,
        val distanceMeters: Double,
        val durationSeconds: Long
    )

    // =========================
    // 도보 경로 응답 파싱
    // =========================
    private fun parseWalkingRoute(response: String): WalkingRouteResult {
        val json = JSONObject(response)

        val status = json.optString("status")
        if (status != "OK") {
            throw Exception("도보 경로 탐색 실패: $status")
        }

        val route = json.optJSONObject("route")
            ?: throw Exception("응답에 route가 없습니다.")

        val legs = route.optJSONArray("legs")
            ?: throw Exception("응답에 legs가 없습니다.")

        val points = mutableListOf<LatLng>()

        for (i in 0 until legs.length()) {
            val leg = legs.optJSONObject(i) ?: continue
            val steps = leg.optJSONArray("steps") ?: continue

            for (j in 0 until steps.length()) {
                val step = steps.optJSONObject(j) ?: continue
                val path = step.optJSONObject("path") ?: continue
                val coordinates = path.optJSONArray("points") ?: continue

                for (k in 0 until coordinates.length()) {
                    val coordinate = coordinates.optJSONArray(k) ?: continue
                    if (coordinate.length() < 2) continue

                    val longitude = coordinate.optDouble(0, Double.NaN)
                    val latitude = coordinate.optDouble(1, Double.NaN)

                    if (
                        longitude.isFinite() &&
                        latitude.isFinite() &&
                        longitude in -180.0..180.0 &&
                        latitude in -90.0..90.0
                    ) {
                        val point = LatLng.from(latitude, longitude)

                        if (points.lastOrNull() != point) {
                            points.add(point)
                        }
                    }
                }
            }
        }

        if (points.size < 2) {
            throw Exception("경로 좌표가 충분하지 않습니다.")
        }

        // 경로 좌표 사이의 거리를 합산
        var calculatedDistance = 0.0

        for (i in 0 until points.lastIndex) {
            val start = points[i]
            val end = points[i + 1]

            val distance = FloatArray(1)

            Location.distanceBetween(
                start.latitude,
                start.longitude,
                end.latitude,
                end.longitude,
                distance
            )

            calculatedDistance += distance[0]
        }

        // API 응답에 거리·시간이 있다면 우선 사용
        val summary = route.optJSONObject("summary")

        val apiDistance = summary
            ?.optDouble("distance", Double.NaN)
            ?.takeIf { it.isFinite() && it > 0.0 }

        val apiDuration = summary
            ?.optDouble("duration", Double.NaN)
            ?.takeIf { it.isFinite() && it > 0.0 }

        val distanceMeters = apiDistance ?: calculatedDistance

        // API 소요 시간이 없으면 평균 도보 속도 5km/h로 추정
        val durationSeconds = apiDuration?.toLong()
            ?: (distanceMeters / 5000.0 * 3600.0).toLong()

        Log.d("WALK_API", "경로 거리=${distanceMeters}m, 예상 시간=${durationSeconds}초")

        return WalkingRouteResult(
            points = points,
            distanceMeters = distanceMeters,
            durationSeconds = durationSeconds
        )
    }

    // =========================
    // 추천 경로 지도 표시
    // =========================
    private fun drawRecommendedPath(map: KakaoMap, points: List<LatLng>) {
        if (points.size < 2) return

        val layer = map.shapeManager?.getLayer()
            ?: throw Exception("지도 ShapeLayer를 가져올 수 없습니다.")

        // 이전 추천 경로와 마커만 제거
        recommendedPolyline?.remove()
        routeStartLabel?.remove()
        routeEndLabel?.remove()

        // 추천 경로 그리기
        val mapPoints = MapPoints.fromLatLng(points)

        val options = PolylineOptions.from(
            mapPoints,
            10f,
            Color.RED
        )

        recommendedPolyline = layer.addPolyline(options)

        // 출발점·도착점 마커 생성
        routeStartLabel = createRouteMarker(
            map = map,
            id = "recommended_route_start",
            position = points.first(),
            color = Color.rgb(46, 160, 67)
        )

        routeEndLabel = createRouteMarker(
            map = map,
            id = "recommended_route_end",
            position = points.last(),
            color = Color.RED
        )

        // 실제 좌표 사이의 거리를 합산
        var totalDistanceMeters = 0.0

        for (i in 0 until points.size - 1) {
            val start = points[i]
            val end = points[i + 1]

            val results = FloatArray(1)

            Location.distanceBetween(
                start.latitude,
                start.longitude,
                end.latitude,
                end.longitude,
                results
            )

            totalDistanceMeters += results[0]
        }

        // 거리 표시
        val distanceKm = totalDistanceMeters / 1000.0

        view?.findViewById<TextView>(
            R.id.tv_recommended_distance
        )?.text = String.format(
            Locale.KOREA,
            "%.2f km",
            distanceKm
        )

        // 예상 러닝 시간: 평균 페이스 6분/km 기준
        val estimatedSeconds = (distanceKm * 6 * 60).toInt()
        val estimatedMinutes = estimatedSeconds / 60
        val remainingSeconds = estimatedSeconds % 60

        val estimatedTimeText = if (estimatedMinutes >= 60) {
            String.format(
                Locale.KOREA,
                "%d시간 %d분",
                estimatedMinutes / 60,
                estimatedMinutes % 60
            )
        } else if (remainingSeconds == 0) {
            "${estimatedMinutes}분"
        } else {
            "${estimatedMinutes}분 ${remainingSeconds}초"
        }

        view?.findViewById<TextView>(
            R.id.tv_recommended_time
        )?.text = estimatedTimeText

        // 추천 경로 전체가 화면에 들어오도록 카메라 조정
//        map.moveCamera(
//            CameraUpdateFactory.newCenterPosition(points.first())
//        )

        // 추천 경로 전체가 화면에 들어오도록 카메라 조정
        map.moveCamera(
            CameraUpdateFactory.fitMapPoints(
                points.toTypedArray(),
                100,
                15
            )
        )
        Log.d(
            "WALK_API",
            "추천 경로 표시 완료: 좌표 ${points.size}개, " +
                    "거리 ${"%.2f".format(Locale.KOREA, distanceKm)}km, " +
                    "예상 시간 $estimatedTimeText"
        )
    }

    // =========================
    // 출발지·도착지 마커 표시
    // =========================
    private fun drawRouteMarkers(map: KakaoMap, points: List<LatLng>) {
        val labelManager = map.labelManager ?: return
        val layer = labelManager.getLayer() ?: return

        // 이전 검색 마커 제거
        routeStartLabel?.remove()
        routeEndLabel?.remove()

        val startPosition = routeStartLatLng ?: points.first()
        val endPosition = routeEndLatLng ?: points.last()

        routeStartLabel = layer.addLabel(
            LabelOptions.from(
                "route_start",
                startPosition
            ).setStyles(createRouteMarkerBitmap("S", Color.rgb(46, 160, 67)))
        )

        routeEndLabel = layer.addLabel(
            LabelOptions.from(
                "route_end",
                endPosition
            ).setStyles(createRouteMarkerBitmap("E", Color.RED))
        )
        Log.d("ROUTE_MARKER", "출발지 마커=$startPosition, 도착지 마커=$endPosition")
    }

    // =========================
    // S/E 문자 마커 이미지 생성
    // =========================
    private fun createRouteMarkerBitmap(text: String, backgroundColor: Int): Bitmap {
        val size = 80
        val bitmap = Bitmap.createBitmap(
            size,
            size,
            Bitmap.Config.ARGB_8888
        )

        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 흰색 테두리
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawCircle(
            size / 2f,
            size / 2f,
            30f,
            paint
        )

        // 내부 색상
        paint.color = backgroundColor
        canvas.drawCircle(
            size / 2f,
            size / 2f,
            25f,
            paint
        )

        // S / E 문자
        paint.color = Color.WHITE
        paint.textSize = 28f
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD

        val textY = size / 2f -
                (paint.ascent() + paint.descent()) / 2f

        canvas.drawText(
            text,
            size / 2f,
            textY,
            paint
        )
        return bitmap
    }

    // =========================
    // 출발점·도착점 마커 생성 함수
    // =========================
    private fun createRouteMarker(
        map: KakaoMap,
        id: String,
        position: LatLng,
        color: Int
    ): Label? {
        val labelManager = map.labelManager ?: return null
        val layer = labelManager.getLayer() ?: return null

        val size = 60
        val bitmap = Bitmap.createBitmap(
            size,
            size,
            Bitmap.Config.ARGB_8888
        )

        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 흰색 테두리
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        canvas.drawCircle(
            size / 2f,
            size / 2f,
            22f,
            paint
        )

        // 출발점 또는 도착점 색상
        paint.color = color
        canvas.drawCircle(
            size / 2f,
            size / 2f,
            16f,
            paint
        )
        val options = LabelOptions
            .from(id, position)
            .setStyles(bitmap)

        return layer.addLabel(options)
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
            showNextRecommendedRoute()
        }

        view.findViewById<Button>(R.id.btn_search_route).setOnClickListener {
            if (isRunning) {
                Toast.makeText(requireContext(), "러닝 중에는 경로를 변경할 수 없어요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val startQuery = view.findViewById<EditText>(
                R.id.et_route_start
            ).text.toString().trim()

            val endQuery = view.findViewById<EditText>(
                R.id.et_route_end
            ).text.toString().trim()

            if (startQuery.isBlank() || endQuery.isBlank()) {
                Toast.makeText(requireContext(), "출발지와 도착지를 모두 입력해 주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (isRouteRequesting) return@setOnClickListener

            isRouteRequesting = true

            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val places = withContext(Dispatchers.IO) {
                        val startPlace = searchPlace(startQuery)
                        val endPlace = searchPlace(endQuery)

                        Pair(startPlace, endPlace)
                    }

                    val startPlace = places.first
                    val endPlace = places.second

                    // 검색한 장소의 좌표 저장
                    routeStartLatLng = LatLng.from(
                        startPlace.latitude,
                        startPlace.longitude
                    )
                    routeEndLatLng = LatLng.from(
                        endPlace.latitude,
                        endPlace.longitude
                    )

                    routeStartName = startPlace.name
                    routeEndName = endPlace.name

                    // 장소 검색 완료 후 요청 상태 해제
                    isRouteRequesting = false

                    // 검색한 출발지와 도착지로 도보 경로 요청
                    requestWalkingRoute(
                        routeStartLatLng!!,
                        routeEndLatLng!!
                    )

                    Log.d(
                        "PLACE_SEARCH",
                        "출발지: ${startPlace.name}, ${startPlace.address}, " +
                                "${startPlace.latitude}, ${startPlace.longitude}"
                    )
                    Log.d(
                        "PLACE_SEARCH",
                        "도착지: ${endPlace.name}, ${endPlace.address}, " +
                                "${endPlace.latitude}, ${endPlace.longitude}"
                    )
//                    Toast.makeText(requireContext(), "장소 검색 성공!\n${startPlace.name} → ${endPlace.name}", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Log.e("PLACE_SEARCH", "장소 검색 실패", e)
                    Toast.makeText(requireContext(), "장소 검색 실패: ${e.message ?: "알 수 없는 오류"}", Toast.LENGTH_LONG).show()
                } finally {
                    isRouteRequesting = false
                }
            }
        }

        startRunningButton.setOnClickListener {
            startRunning(view)
        }

        pauseRunningButton.setOnClickListener {
            if (isRunning) {
//                if (!isPaused) {
//                    // 수동 일시정지
//                    isPaused = true
//                } else {
//                    // 러닝 재개
//                    isPaused = false
//                }
                if (!isPaused) {
                    // 일시정지: 현재까지 누적된 이동 시간 유지
                    isPaused = true
                } else {
                    // 재개: 기존 이동 시간부터 이어서 누적
                    isPaused = false
                }

                // GPS 위치 간 거리 오차 방지
                previousLocation = null
                lastLocationTime = 0L
                isMoving = false
                consecutiveStoppedSamples = 0

                pauseRunningButton.text =
                    if (isPaused) "러닝 재개" else "일시정지"

                updateRunningStats()

                // GPS 위치 간 거리 오차 방지
                previousLocation = null
                lastLocationTime = 0L
                isMoving = false

                pauseRunningButton.text =
                    if (isPaused) "러닝 재개" else "일시정지"

                view.findViewById<TextView>(
                    R.id.tv_running_active_title
                ).text = if (isPaused) {
                    "일시정지 중"
                } else {
                    "러닝 중"
                }

                updateRunningStats()

                Toast.makeText(
                    requireContext(),
                    if (isPaused) {
                        "러닝을 일시정지했어요."
                    } else {
                        "러닝을 재개했어요."
                    },
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

        // 추천 경로 모드라면 현재 선택한 경로를 보관
        activeRecommendedRoute = if (isRecommendedMode) {
            recommendedRoutes.getOrNull(selectedRouteIndex)
        } else {
            null
        }

        // 추천 경로 모드인데 아직 경로를 조회하지 않았다면 안내
        if (isRecommendedMode && activeRecommendedRoute == null) {
            Toast.makeText(requireContext(), "먼저 추천 경로를 조회해 주세요.", Toast.LENGTH_LONG).show()
            return
        }

        isRunning = true

        // 날짜 및 시각 기록
        runningStartedAt = System.currentTimeMillis()

        isPaused = false

        totalDistance = 0.0
        hasShownTargetDistanceNotice = false

        // 시간 초기화
        elapsedTime = 0L
        lastLocationTime = 0L
        isMoving = false
        consecutiveStoppedSamples = 0
        previousLocation = null
        runningPath.clear()

        runningPolyline?.remove()
        runningPolyline = null
        totalElevationGain = 0.0
        previousAltitude = null

        lastPathLocation = null
        previousDistanceLocation = null

        modeSelector.visibility = View.GONE
        view.findViewById<TextView>(R.id.tv_running_title).visibility = View.GONE
        recommendedRouteContainer.visibility = View.GONE
        freeRunningContainer.visibility = View.GONE
        startRunningButton.visibility = View.GONE
        runningActiveContainer.visibility = View.VISIBLE
        pauseRunningButton.text = "일시정지"
        view.findViewById<TextView>(R.id.tv_running_active_title).text = "러닝 중"

        // 러닝 중 접힌 상태의 높이
        bottomSheetBehavior.peekHeight = dpToPx(110)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        updateRunningStats()
        startTimer()

        Log.d(
            "RUNNING",
            "러닝 시작 / 모드: ${if (isRecommendedMode) "추천 경로" else "내 맘대로"} / " +
                    "선택 경로: ${activeRecommendedRoute?.mode ?: "없음"} / " +
                    "경로 좌표 수: ${activeRecommendedRoute?.points?.size ?: 0} / " +
                    "목표: ${targetDistanceKm ?: "제한 없음"}"
        )
    }

    private fun finishRunning() {
        isRunning = false
        isPaused = false
        stopTimer()

        Log.d("RUNNING", "러닝 종료! 최종 거리: ${totalDistance}m")
        Log.d("RUNNING_PATH", "종료 시 좌표 개수: ${runningPath.size}")
        Log.d("RUNNING_PATH", "시작 좌표: ${runningPath.firstOrNull()}")
        Log.d("RUNNING_PATH", "마지막 좌표: ${runningPath.lastOrNull()}")

        // 결과 화면으로 전달할 데이터
        val distance = totalDistance
        val time = elapsedTime

        Log.d(
            "RUN_SPEED_CHECK",
            "종료 시 거리=${distance}m, " +
                    "시간=${time}ms, " +
                    "평균속도=${if (time > 0L) distance / 1000.0 / (time / 3600000.0) else 0.0}km/h"
        )

        val elevation = totalElevationGain
        val startedAt = runningStartedAt

        val latitudes = runningPath.map { it.latitude }.toDoubleArray()
        val longitudes = runningPath.map { it.longitude }.toDoubleArray()

        val intent = Intent(requireContext(), RunningResultActivity::class.java).apply {
            putExtra("TOTAL_DISTANCE", distance)
            putExtra("ELAPSED_TIME", time)
            putExtra("ELEVATION_GAIN", elevation)
            putExtra("RUNNING_LATITUDES", latitudes)
            putExtra("RUNNING_LONGITUDES", longitudes)
            putExtra("STARTED_AT", startedAt)
        }

        // Room 데이터베이스에 러닝 기록 저장
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // GPS 경로를 JSON 문자열로 변환
                val routeCoordinatesJson = JSONArray().apply {
                    runningPath.forEach { point ->
                        put(
                            JSONArray().apply {
                                put(point.latitude)
                                put(point.longitude)
                            }
                        )
                    }
                }.toString()

                Log.d("RUNNING_DB", "저장 직전 좌표 개수: ${runningPath.size}")
                Log.d("RUNNING_DB", "저장할 경로 JSON: $routeCoordinatesJson")

                val title = createRunningTitle(startedAt)

                // 러닝 요약 정보와 GPS 경로를 함께 저장
                val record = RunningRecord(
                    startedAt = startedAt,
                    totalDistanceMeters = distance,
                    elapsedTimeMillis = time,
                    elevationGainMeters = elevation,
                    routeCoordinatesJson = routeCoordinatesJson,
                    title = title,
                )

                val dao = RunningDatabase
                    .getDatabase(requireContext())
                    .runningRecordDao()

                val recordId = dao.insert(record)

                Log.d("RUNNING_DB", "저장된 recordId = $recordId")

                Log.d("RUNNING_DB",
                    "러닝 기록 저장 완료: id=$recordId, " +
                            "좌표 개수=${runningPath.size}, " +
                            "JSON 길이=${routeCoordinatesJson.length}"
                )

                // 저장이 끝나면 기존 결과 화면으로 이동
                runningResultLauncher.launch(intent)

            } catch (e: Exception) {
                Log.e("RUNNING_DB", "러닝 기록 저장 실패", e)
                Toast.makeText(requireContext(), "러닝 기록 저장에 실패했어요.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun createRunningTitle(startedAt: Long): String {
        val date = Date(startedAt)
        val dayOfWeek = SimpleDateFormat("EEEE", Locale.KOREAN).format(date)
        val amPm = SimpleDateFormat("a", Locale.KOREAN).format(date)

        return "$dayOfWeek $amPm 러닝"
    }

    private fun resetPreRunUi(view: View) {
        modeSelector.visibility = View.VISIBLE
        view.findViewById<TextView>(R.id.tv_running_title).visibility = View.VISIBLE
        startRunningButton.visibility = View.VISIBLE
        runningActiveContainer.visibility = View.GONE
        pauseRunningButton.text = "일시정지"
        updatePreRunMode(view)

        // 러닝 전 접힌 높이로 복구
        bottomSheetBehavior.peekHeight = dpToPx(130)

        // BottomSheet 접기
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
//    private fun startTimer() {
//        timerRunnable = object : Runnable {
//            override fun run() {
//                if (!isRunning) return
//
//                updateRunningStats()
//
//                timerHandler.postDelayed(this, 1000L)
//            }
//        }
//        timerHandler.post(timerRunnable)
//    }

    private fun startTimer() {
        timerHandler.removeCallbacks(timerRunnableOrEmpty())

        timerRunnable = object : Runnable {
            override fun run() {
                if (!isRunning) return

                updateRunningStats()
                timerHandler.postDelayed(this, 1000L)
            }
        }
        timerHandler.post(timerRunnable)
    }

    private fun timerRunnableOrEmpty(): Runnable {
        return if (::timerRunnable.isInitialized) {
            timerRunnable
        } else {
            Runnable {}
        }
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
    // =========s============================
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

                            val currentLocationTime =
                                location.elapsedRealtimeNanos / 1_000_000L

                            val previous = previousLocation

                            // GPS 정확도 확인
                            val isAccurate =
                                location.hasAccuracy() && location.accuracy <= 15f

                            if (!isAccurate) {
                                Log.d(
                                    "GPS_FILTER",
                                    "정확도 부족으로 제외: 정확도=${location.accuracy}m"
                                )
                                continue
                            }

                            if (previous != null && lastLocationTime > 0L) {

                                val distance = previous.distanceTo(location)

                                val deltaTime = currentLocationTime - lastLocationTime

                                // 경과 시간 대비 이동 속도 계산 (m/s)
                                val speed = if (deltaTime > 0L) {
                                    distance / (deltaTime / 1000f)
                                } else {
                                    Float.MAX_VALUE
                                }

                                // 시간 간격이 유효하고 비정상적으로 빠른 이동이 아닐 때만 처리
                                val isPlausible =
                                    deltaTime in 1L..10_000L &&
                                            speed <= 7.0f

                                if (!isPlausible) {
                                    Log.d(
                                        "GPS_FILTER",
                                        "비정상 GPS 제외: 거리=${distance}m, " +
                                                "속도=${speed}m/s, 간격=${deltaTime}ms"
                                    )

                                    // 튄 좌표를 이전 위치로 저장하지 않음
                                    continue
                                }

                                // GPS 이동 속도 계산
                                val calculatedSpeed = distance / (deltaTime / 1000f)

                                // Android에서 제공하는 GPS 속도
                                val hasGpsSpeed =
                                    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                                            location.hasSpeed()

                                val gpsSpeed = if (hasGpsSpeed) {
                                    location.speed
                                } else {
                                    0f
                                }

                                // 이동 판정 기준
                                val isCurrentlyMoving =
                                    gpsSpeed >= 0.6f || calculatedSpeed >= 0.6f

                                // 정지 판정 기준
                                val isCurrentlyStopped =
                                    calculatedSpeed < 0.45f &&
                                            (!hasGpsSpeed || gpsSpeed < 0.35f)

                                if (!isMoving) {

                                    // 정지 상태에서 이동이 감지되면 바로 이동 시작
                                    if (isCurrentlyMoving) {
                                        isMoving = true
                                        consecutiveStoppedSamples = 0

                                        Log.d("RUN_TIMER", "이동 시작 감지")
                                    }
                                } else {

                                    // 이동 중 정지 신호가 감지되면 횟수 누적
                                    if (isCurrentlyStopped) {
                                        consecutiveStoppedSamples++
                                    } else {
                                        consecutiveStoppedSamples = 0
                                    }

                                    // 정지 신호가 2회 연속 확인되면 이동 종료
                                    if (consecutiveStoppedSamples >= 2) {
                                        isMoving = false
                                        consecutiveStoppedSamples = 0

                                        Log.d("RUN_TIMER", "정지 감지")
                                    }
                                }

                                // 이동 중인 시간만 누적
                                if (isMoving) {
                                    elapsedTime += deltaTime

                                    Log.d(
                                        "RUN_TIMER",
                                        "이동 중 | 누적 시간: ${elapsedTime / 1000}초 | " +
                                                "GPS 속도: ${gpsSpeed}m/s | " +
                                                "계산 속도: ${calculatedSpeed}m/s"
                                    )
                                }

                                // 경로는 2m 이상 차이가 날 때만 저장
                                val lastPath = lastPathLocation

                                if (lastPath == null ||
                                    lastPath.distanceTo(location) >= 2f
                                ) {
                                    runningPath.add(currentPosition)
                                    lastPathLocation = Location(location)

                                    Log.d(
                                        "RUNNING_PATH",
                                        "유효 경로 저장: ${location.latitude}, " +
                                                "${location.longitude}"
                                    )

                                    drawRunningPath(kakaoMap)
                                }

                            } else {
                                // 첫 위치는 비교 기준으로만 사용
                                isMoving = false
                                consecutiveStoppedSamples = 0
                            }

                            // 정상적인 GPS 좌표만 다음 비교 기준으로 저장
                            previousLocation = Location(location)
                            lastLocationTime = currentLocationTime
                        }

//                        if (isRunning && !isPaused) {
//                            // 현재 GPS가 들어온 시간
//                            val currentLocationTime =
//                                location.elapsedRealtimeNanos / 1_000_000L
//
//                            val previous = previousLocation
//
//                            if (previous != null && lastLocationTime > 0L) {
//                                // 이전 GPS와 현재 GPS 사이의 거리
//                                val distance = previous.distanceTo(location)
//
//                                // GPS 정확도가 15m 이내일 때만 이동 판단
//                                val isAccurate = location.accuracy <= 15f
//
//                                // 1m 이상 이동했고 GPS 정확도도 괜찮으면 이동 중
//                                isMoving = isAccurate && distance >= 1.0f
//
//                                // 이전 GPS 이후 경과한 시간
//                                val deltaTime =
//                                    currentLocationTime - lastLocationTime
//
//                                if (isMoving && deltaTime > 0L) {
//                                    // 이동 중인 시간만 누적
//                                    elapsedTime += deltaTime
//
//                                    Log.d(
//                                        "RUN_TIMER",
//                                        "이동 감지: ${distance}m / " +
//                                                "시간 추가: ${deltaTime}ms / " +
//                                                "누적 시간: ${elapsedTime}ms"
//                                    )
//                                } else {
//                                    Log.d(
//                                        "RUN_TIMER",
//                                        "정지 또는 GPS 정확도 부족: " +
//                                                "거리=${distance}m / " +
//                                                "정확도=${location.accuracy}m / " +
//                                                "누적 시간=${elapsedTime}ms"
//                                    )
//                                }
//                            } else {
//                                // 첫 GPS 위치는 비교 대상이 없으므로 시간 누적 안 함
//                                isMoving = false
//                            }
//
//                            // 다음 GPS와 비교할 시간 저장
//                            lastLocationTime = currentLocationTime
//
//                            // GPS 경로 저장
//                            runningPath.add(currentPosition)
//
//                            Log.d(
//                                "RUNNING_PATH",
//                                "경로 저장: ${currentPosition.latitude}, " +
//                                        "${currentPosition.longitude}"
//                            )
//
//                            // 지도에 러닝 경로 표시
//                            drawRunningPath(kakaoMap)
//                        }


                        // 카메라가 현위치를 따라가도록 설정된 경우에만 이동
                        if (isCameraFollowingCurrentLocation) {
                            kakaoMap.moveCamera(
                                CameraUpdateFactory.newCenterPosition(currentPosition)
                            )
                        }

                        // 현위치 마커는 계속 갱신
                        if (currentLocationLabel == null) {
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

                            val previousDistance = previousDistanceLocation

                            // 이전 GPS와 현재 GPS 사이의 거리
                            if (previousDistance != null) {
                                val distance = previousDistance.distanceTo(location)

                                // GPS 측정 간격
                                val deltaTime =
                                    (location.elapsedRealtimeNanos -
                                            previousDistance.elapsedRealtimeNanos) / 1_000_000L

                                // 이동 속도 계산
                                val speed = if (deltaTime > 0L) {
                                    distance / (deltaTime / 1000f)
                                } else {
                                    Float.MAX_VALUE
                                }

                                // 기존 GPS 유효성 검사 유지
                                val isValidDistance =
                                    location.hasAccuracy() &&
                                            location.accuracy <= 15f &&
                                            deltaTime in 1L..10_000L &&
                                            speed <= 7.0f

                                // 이동 중이고, 0.5m 이상 이동했을 때 거리 누적
                                if (isValidDistance && isMoving && distance >= 0.5f) {
                                    totalDistance += distance

                                    Log.d(
                                        "RUN_DISTANCE",
                                        "유효 거리 추가: ${distance}m / " +
                                                "총 거리=${totalDistance}m"
                                    )

                                    val targetMeters =
                                        if (!isRecommendedMode) targetDistanceKm?.times(1000.0)
                                        else null

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
                                    Log.d(
                                        "RUN_DISTANCE",
                                        "GPS 오차 또는 작은 이동으로 제외: ${distance}m"
                                    )
                                }
                            }

                            // 정확도가 유효한 GPS 위치만 다음 비교 기준으로 저장
                            if (location.hasAccuracy() && location.accuracy <= 15f) {
                                previousDistanceLocation = Location(location)
                            }
                        }

//                        if (isRunning && !isPaused) {
//                            previousLocation?.let { previous ->
//                                // 이전 위치 → 현재 위치 거리
//                                val distance = previous.distanceTo(location)
//
//                                // 2m 이하 GPS 오차 필터링
//                                if (distance >= 2) {
//                                    // 총 이동 거리 누적
//                                    totalDistance += distance
//                                    Log.d("RUN_DISTANCE", "이동 거리 추가: ${distance}m / " + "총 거리 : ${totalDistance}m")
//
//                                    val targetMeters = if (!isRecommendedMode) targetDistanceKm?.times(1000.0) else null
//                                    if (
//                                        !hasShownTargetDistanceNotice &&
//                                        targetMeters != null &&
//                                        totalDistance >= targetMeters
//                                    ) {
//                                        hasShownTargetDistanceNotice = true
//                                        Toast.makeText(
//                                            requireContext(),
//                                            "목표 거리 달성! 원하는 만큼 더 달려도 좋아요.",
//                                            Toast.LENGTH_LONG
//                                        ).show()
//                                    }
//                                    updateRunningStats()
//                                } else {
//                                    Log.d("RUN_DISTANCE", "GPS 오차 무시: ${distance}m / " + "총 거리 : ${totalDistance}m")
//                                }
//                            }
//                        }
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
//                        previousLocation = location
//                        Log.d("PREVIOUS_GPS", "저장된 이전 위치 → " + "위도: ${previousLocation?.latitude}, " + "경도: ${previousLocation?.longitude}")
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