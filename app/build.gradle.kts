import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 正式签名配置：工程根目录放 keystore.properties 即启用；
// 缺这个文件时 release 回退用 debug 签名，行为与以前一致（本机自测够用）。
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}

android {
    namespace = "com.inkreader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.inkreader"
        minSdk = 24
        targetSdk = 34
        versionCode = 36
        versionName = "1.10.10"
        resourceConfigurations += listOf("zh", "en", "ja")
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // 无法真机验证时优先保证完整性：关闭 R8 裁剪与资源收缩，
            // 避免激进优化引入只能在设备上暴露的问题
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 有 keystore.properties 就用正式签名，否则回退调试签名
            signingConfig = if (keystorePropertiesFile.exists())
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
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
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-text")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.animation:animation")

    implementation("androidx.media:media:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // PDF 正文提取。系统 PdfRenderer 只能把页面渲染成位图、拿不到文字，
    // 而 PDF 里的中文字体基本都是 CID 子集 + ToUnicode CMap，
    // 自己解析极易提出乱码，所以这里引入纯 Java 的 pdfbox-android（无 so、无 AWT）。
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Markdown 正文解析。要求「保留格式阅读」（标题/粗斜体/列表/引用/代码块/表格），
    // 自己用正则剥标记既处理不了嵌套结构，也没法把样式映射回正文偏移，
    // 所以引入纯 Java 的 commonmark-java（无 so、无 Android 依赖，约 300KB），
    // 由它出 AST，我们再走一遍 AST 生成「阅读用纯文本 + 样式覆盖」。
    implementation("org.commonmark:commonmark:0.22.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.22.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.22.0")
    implementation("org.commonmark:commonmark-ext-task-list-items:0.22.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
