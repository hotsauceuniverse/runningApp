import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)

    id("org.jetbrains.kotlin.kapt")
}

val properties = Properties()
val localPropertiesFile = rootProject.file("local.properties")

if (localPropertiesFile.exists()) {
    properties.load(localPropertiesFile.inputStream())
}

val kakaoMapKey = properties.getProperty("KAKAO_MAP_KEY")
    ?: throw GradleException("KAKAO_MAP_KEY가 local.properties에 없습니다.")

val kakaoRestApiKey = properties.getProperty("KAKAO_REST_API_KEY")
    ?: throw GradleException("KAKAO_REST_API_KEY가 local.properties에 없습니다.")

android {
    namespace = "com.seyoung.runningproject"
    compileSdk = 36

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.seyoung.runningproject"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "KAKAO_MAP_KEY",
            "\"$kakaoMapKey\""
        )

        buildConfigField(
            "String",
            "KAKAO_REST_API_KEY",
            "\"$kakaoRestApiKey\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation("com.kakao.maps.open:android:2.9.5")     // 카카오맵api 사용
    implementation("com.google.android.gms:play-services-location:21.3.0")      // gps 사용

    // Room 의존성
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)

    implementation(libs.androidx.recyclerview)
}