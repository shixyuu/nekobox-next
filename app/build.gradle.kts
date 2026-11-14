import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val coreAar = layout.projectDirectory.file("libs/nekocore.aar").asFile

android {
    namespace = "io.nekobox.next"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.nekobox.next"
        // API 26 is the floor: notification channels, a modern VpnService.Builder,
        // and the socket options ByeDPI uses are all available, while Android 8
        // devices stay installable. sing-box 1.14 itself still runs there.
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
        }
    }

    sourceSets.named("main") {
        val coreDir = if (coreAar.exists()) "src/coreAar/kotlin" else "src/coreStub/kotlin"
        kotlin.directories.add(coreDir)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":engine"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons)
    implementation(libs.androidx.graphics.shapes)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.work)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.okhttp)
    ksp(libs.androidx.room.compiler)
    ksp(libs.hilt.compiler)
    ksp(libs.hilt.work.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.androidx.room.testing)
    if (coreAar.exists()) {
        implementation(files(coreAar))
    }
}
