import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// vendor 模块：Kyant0/AndroidLiquidGlass v2.0.1 源码（Apache-2.0，见本目录 LICENSE）。
// 为什么不直接用 Maven 坐标 io.github.kyant0:backdrop:2.0.1：
//   2.0.1 传递依赖 androidx.compose.ui:ui-android:1.12.0 要求 AGP 9.1+ / compileSdk 37，
//   且其 Kotlin 元数据由 2.4.10 编译，本项目的 Kotlin 2.2.21 编译器无法读取。
//   故合并 KMP 的 commonMain+androidMain 为单平台源码，用现有工具链编译，API 完全一致。
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.kyant.backdrop"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.01.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    // @Language("AGSL") / @FloatRange 注解（仅编译期）
    compileOnly("org.jetbrains:annotations:23.0.0")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // LayerRecorder.kt 使用 Kotlin context parameters（上游 Kotlin 2.4 已转正，2.2 需显式开启）
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}
