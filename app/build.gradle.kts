plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// La version sale de la etiqueta de git (ej. v1.3 -> versionName 1.3, versionCode 103)
val tagName: String = (System.getenv("FOODXCAN_VERSION") ?: "1.0").trimStart('v', 'V')
val tagParts = tagName.split(".", "-").mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
val vCode = (tagParts.getOrElse(0) { 1 }) * 10000 +
            (tagParts.getOrElse(1) { 0 }) * 100 +
            (tagParts.getOrElse(2) { 0 })

android {
    namespace = "com.xito.foodxcan"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xito.foodxcan"
        minSdk = 24
        targetSdk = 34
        versionCode = vCode
        versionName = tagName
    }

    // Clave fija: sin esto cada compilacion firma distinto y Android
    // rechaza la instalacion con "conflicto con el paquete existente"
    signingConfigs {
        create("foodxcan") {
            storeFile = file("../foodxcan.keystore")
            storePassword = "foodxcan"
            keyAlias = "foodxcan"
            keyPassword = "foodxcan"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("foodxcan") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("foodxcan")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")

    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mlkit:barcode-scanning:17.2.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("io.coil-kt:coil-compose:2.6.0")
}
