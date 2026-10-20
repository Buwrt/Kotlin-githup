plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.hubmobile.ui"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hubmobile.ui"
        minSdk = 26          // RenderEffect（真实背景模糊）需要 26+；26 以下自动退化为渐变玻璃
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            // UI 复刻版不混淆，方便对照阅读源码
            isMinifyEnabled = false
            isShrinkResources = false
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
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // 产物统一命名，便于 CI 里直接定位
    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val out = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            out.outputFileName = "githup-ui-${variant.versionName}-${variant.name}.apk"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // Toast / 启动屏的延时用；Compose 的 LaunchedEffect 本身不引入协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
