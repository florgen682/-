plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Номер версии берём из GitHub Actions (номер сборки), локально — 1.
val buildNumber = (System.getenv("VERSION_CODE") ?: "1").toInt()

// Данные ключа подписи приходят из GitHub Secrets через переменные окружения.
val keystorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")

// Для автотестов на эмуляторе собираем под один процессор: ./gradlew ... -PtestAbi=x86_64
val testAbi: String? = providers.gradleProperty("testAbi").orNull
val releaseAbis = listOf("arm64-v8a", "armeabi-v7a")

android {
    namespace = "app.clipradar"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.clipradar"
        minSdk = 26 // Android 8.0
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Распознавание речи (whisper.cpp) собирается из исходников под нужные процессоры.
        externalNativeBuild {
            cmake {
                abiFilters.addAll(if (testAbi != null) listOf(testAbi) else releaseAbis)
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
        if (testAbi != null) {
            ndk { abiFilters.add(testAbi) }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            if (keystorePath != null && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) {
                signingConfigs.getByName("release")
            } else {
                // Локальная сборка без ключа — подписываем отладочным ключом.
                signingConfigs.getByName("debug")
            }
        }
    }

    // Отдельные APK под разные процессоры: так каждый файл в 2 раза меньше.
    splits {
        abi {
            isEnable = testAbi == null
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        // Нужно для youtubedl-android: Python и ffmpeg должны распаковываться на диск.
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/*.kotlin_module", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/*.md", "META-INF/INDEX.LIST", "META-INF/AL2.0", "META-INF/LGPL2.1",
                "META-INF/versions/**", "META-INF/FastDoubleParser-*", "META-INF/thirdparty-LICENSE",
                "META-INF/bigint-LICENSE", "**/module-info.class",
            )
        }
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":core"))

    // Основа Android и Jetpack Compose (интерфейс)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Фоновые задачи (нарезка, проверка стримеров)
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // База данных (история клипов)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Нарезка видео аппаратным кодировщиком
    implementation("androidx.media3:media3-transformer:1.5.1")
    implementation("androidx.media3:media3-effect:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")

    // Сеть: чат Twitch (WebSocket) и скачивание кусков эфира
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Превью клипов
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")

    // Предпросмотр в редакторе клипа
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    // Поиск лиц на кадре (вебка стримера, умное кадрирование) — работает офлайн
    implementation("com.google.mlkit:face-detection:16.1.7")

    // Необязательные ИИ-подписи (официальный SDK Claude API)
    implementation("com.anthropic:anthropic-java:3.11.0")

    // yt-dlp и ffmpeg внутри приложения
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")

    // Автотесты на эмуляторе (рендер без чёрных полей, распознавание речи, миграция базы)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}
