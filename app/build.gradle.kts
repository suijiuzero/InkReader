import java.io.File
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 正式签名配置：工程根目录放 keystore.properties 即启用；
// 缺这个文件时仍可构建 debug；release 打包任务会明确失败，不回退 debug 签名。
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
        versionCode = 69
        versionName = "1.15.2"
        testInstrumentationRunner = "com.inkreader.AuditInstrumentation"
        resourceConfigurations += listOf("zh", "en", "ja")
    }

    signingConfigs {
        // 独立构建缓存/ANDROID_USER_HOME 不应悄悄更换已有设备使用的调试签名。
        val existingDebugKeystore = File(System.getProperty("user.home"), ".android/debug.keystore")
        if (existingDebugKeystore.isFile) {
            getByName("debug") { storeFile = existingDebugKeystore }
        }
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
            // 正式签名：有 keystore.properties 就用 release 配置，没有就**不配签名**（出未签名包）。
            // 不再回退 debug 签名 —— 那会让全新 clone / CI 静默产出「debug 签名的发布包」，
            // 正是「禁止用 debug 签名发布」要防的事。缺文件时由下面的任务守卫直接报错。
            signingConfig = if (keystorePropertiesFile.exists())
                signingConfigs.getByName("release")
            else
                null
        }
    }

    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("tests/fixtures"))
    sourceSets.getByName("test").java.srcDir("src/sharedTest/kotlin")
    sourceSets.getByName("androidTest").java.srcDir("src/sharedTest/kotlin")

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

tasks.register<JavaExec>("unitTest") {
    group = "verification"
    description = "运行纯 Kotlin 规则单测（不受中文工程路径影响；自动发现全部 *Test 类）"
    val testClasses = tasks.named("compileDebugUnitTestKotlin").map { it.outputs.files }
    val mainClasses = tasks.named("bundleDebugClassesToRuntimeJar").map { it.outputs.files }
    // 注意：这里刻意不引用 testDebugUnitTest 任务本身 —— 引用它会让该任务一并执行，
    // 而它正是被中文路径卡住的那个任务（见文件顶部说明）。类路径改由编译产物手工拼出。
    classpath = files(testClasses, mainClasses, configurations.named("debugUnitTestRuntimeClasspath"))
    mainClass.set("org.junit.runner.JUnitCore")
    dependsOn(testClasses)
    // 自动发现测试类：以前把类名写死在 args 里，以后新增的测试类会被静默跳过
    doFirst {
        val roots = testClasses.get().files.filter { it.isDirectory }
        val discovered = roots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.name.endsWith("Test.class") && !it.name.contains('$') }
                .map {
                    it.relativeTo(root).path
                        .removeSuffix(".class")
                        .replace(File.separatorChar, '.')
                }
                .toList()
        }.distinct().sorted()
        logger.lifecycle("发现 ${discovered.size} 个测试类：${discovered.joinToString()}")
        args(discovered)
    }
}

// 挂进 check，让 `gradlew check` / `build` 也跑这套回归测试（以前只有手点 :app:unitTest 才跑）
tasks.named("check") { dependsOn("unitTest") }

// release 必须用正式签名：缺 keystore.properties 时让 release 的打包任务在**执行期**直接失败。
// 不放配置期（那会连 assembleDebug 一起打断），也不静默回退 debug 签名。
if (!keystorePropertiesFile.exists()) {
    tasks.matching { it.name.startsWith("assembleRelease") || it.name.startsWith("bundleRelease") }
        .configureEach {
            doFirst {
                throw GradleException(
                    "缺少 keystore.properties：release 必须用正式签名，不允许回退 debug 签名。" +
                        "只想出 debug 包请执行 assembleDebug。"
                )
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

    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    implementation("org.commonmark:commonmark:0.22.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.22.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.22.0")
    implementation("org.commonmark:commonmark-ext-task-list-items:0.22.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // 纯 Kotlin 规则（章节识别 / 排序）的回归单测。
    // 注意：在从未解析过该坐标的机器上，`--offline` 跑测试会失败（普通 assembleDebug 不受影响），
    // 首次需要联网让它落进 Gradle 缓存。
    testImplementation("junit:junit:4.13.2")
}
