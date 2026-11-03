import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.zhenlong.reader"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.zhenlong.reader"
        minSdk = 30
        targetSdk = 30
        versionCode = 2
        versionName = "0.2.0"
    }

    // 正式签名读根目录的 keystore.properties（不入库；storeFile / storePassword / keyAlias / keyPassword）。
    // 没有这个文件就退回 debug 签名，方便别人 clone 下来直接装——但 debug 签的包和发布版互相升级不了
    val keystoreProps = rootProject.file("keystore.properties")
    if (keystoreProps.exists()) {
        val props = Properties().apply { keystoreProps.inputStream().use { load(it) } }
        signingConfigs.create("release") {
            storeFile = file(props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        // 只为采集 baseline profile：和 release 同样的 R8 收缩 / 优化，但不混淆，
        // 这样真机 dump 出来的 profile 里是源码类名，AGP 打包时才能映射到 release 的混淆名。
        // 用法见 CLAUDE.md「baseline profile」
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "benchmark-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true   // Readium 的 AAR 强制要求
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        // SPEC §3 固定 targetSdk 30
        disable += "ExpiredTargetSdkVersion"
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.coroutines.android)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)
    implementation(libs.readium.navigator)
    implementation(libs.jsoup)   // 原生文字引擎解析 XHTML；Readium 本来就带着它
    implementation(libs.androidx.profileinstaller)   // 把 baseline-prof.txt 装进 ART 的 profile（装机后系统 bg-dexopt 据此 AOT）

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    testImplementation(libs.junit)
}
