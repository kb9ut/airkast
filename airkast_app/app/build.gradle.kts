plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("kotlin-parcelize")
    id("com.google.android.gms.oss-licenses-plugin")
}

android {
    namespace = "com.example.airkast"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.airkast"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
            all {
                it.useJUnitPlatform()
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Kotlin 2.x compilerOptions DSL
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")

    // Jetpack Compose
    implementation("androidx.activity:activity-compose:1.12.2")
    implementation(platform("androidx.compose:compose-bom:2025.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")

    // Ktor for Networking
    implementation("io.ktor:ktor-client-android:3.4.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.4.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.4.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")

    // MediaBrowserCompat and MediaControllerCompat
    implementation("androidx.media:media:1.7.1")

    // ExoPlayer for playback
    implementation("androidx.media3:media3-exoplayer:1.9.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.9.0") // 追加
    implementation("androidx.media3:media3-session:1.9.0")

    // OSS Licenses
    implementation("com.google.android.gms:play-services-oss-licenses:17.3.0")

    // Testing
    testImplementation("junit:junit:4.13.2") // JUnit 4 for basic unit tests
    testImplementation("io.mockk:mockk:1.14.7") // Mocking framework
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2") // Coroutine testing utilities
    testImplementation("androidx.arch.core:core-testing:2.2.0") // For InstantTaskExecutorRule
    testImplementation("io.ktor:ktor-client-mock:3.4.0") // Ktor MockEngine for testing
    testImplementation(kotlin("test")) // Explicitly add kotlin.test
    testImplementation("org.robolectric:robolectric:4.16.1")

    // AndroidX Test - needed for Android specific tests like Context mocking if required
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}

// Workaround for OSS Licenses plugin task dependency issue with Gradle 8.11.1
// https://github.com/google/play-services-plugins/issues/223
tasks.configureEach {
    if (name.contains("OssLicensesCleanUp")) {
        val dependencyTaskName = name.replace("OssLicensesCleanUp", "OssDependencyTask")
        dependsOn(dependencyTaskName)
    }
}
