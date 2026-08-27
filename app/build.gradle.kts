import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 读取签名配置（keystore.properties 不提交到版本库）
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

// 强制签名：发布版本必须签名，缺少签名配置直接构建失败，绝不产出未签名 APK
if (keystoreProps.isEmpty()) {
    throw GradleException(
        "缺少签名配置 keystore.properties！\n" +
        "发布版本必须签名。请确认 vibecafe-release.jks 与 keystore.properties 存在，\n" +
        "参考 keystore.properties.example 配置后再执行 assembleRelease。"
    )
}

android {
    namespace = "ai.vibecafe.usage"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "ai.vibecafe.usage"
        // lens() 折射着色器需要 API 33，blur() 需要 API 31；
        // 低版本会自动降级为无折射/无模糊的半透明玻璃，不会崩溃。
        minSdk = 26
        targetSdk = 34
        versionCode = 35
        versionName = "2.9.8"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            storeFile = file(keystoreProps["storeFile"] as String)
            storePassword = keystoreProps["storePassword"] as String
            keyAlias = keystoreProps["keyAlias"] as String
            keyPassword = keystoreProps["keyPassword"] as String
        }
    }

    buildTypes {
        release {
            // R8 混淆 + 资源收缩：体积减半；规则见 proguard-rules.pro（Gson 模型已 keep）
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    testOptions {
        // StatsEngine 引用 android.util.Log，单测默认值放行即可跑 JVM
        unitTests.isReturnDefaultValues = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        // 检查更新功能用 BuildConfig.VERSION_NAME 对比远程版本
        buildConfig = true
    }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

// Kotlin 2.x 推荐写法：compilerOptions DSL 替代已弃用的 kotlinOptions
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// 项目路径含非 ASCII（F:\项目）时测试 worker 类加载会乱码，强制 UTF-8
tasks.withType<Test>().configureEach {
    jvmArgs("-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8")
}

dependencies {
    // backdrop 1.0.2 依赖 Compose 1.10.0 / Kotlin stdlib 2.2.21，BOM 需 >= 该版本
    val composeBom = platform("androidx.compose:compose-bom:2026.01.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.activity:activity-compose:1.11.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // 液态玻璃：GPU RuntimeShader 折射 + 模糊 + 高光
    implementation("io.github.kyant0:backdrop:1.0.2")

    // 桌面小组件（Glance）+ 后台定时同步（WorkManager）
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
}
