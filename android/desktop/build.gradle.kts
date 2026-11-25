// Windows 用のアプリ（タスクトレイ常駐、ゲームのライブラリ、PS3 風の画面）。同期は :core を使う
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.jetbrains.compose)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.jna.platform)
    // コントローラー（PlayStation・Xbox・Switch Pro など）を SDL3 で読む。配るのは Windows 用のネイティブ、テストは Linux 用
    // 操作音の MP3 を読む
    implementation(libs.jlayer)
    implementation(libs.lwjgl.core)
    implementation(libs.lwjgl.sdl)
    for (lib in listOf(libs.lwjgl.core, libs.lwjgl.sdl)) {
        runtimeOnly(variantOf(lib) { classifier("natives-windows") })
        testRuntimeOnly(variantOf(lib) { classifier("natives-linux") })
    }

    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(compose.desktop.uiTestJUnit4)
}

tasks.withType<Test>().configureEach {
    // CI のログで失敗の理由（モンキーテストの seed と直前の操作など）が見えるように
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = false
    }
    environment("LC_ALL", "C.UTF-8")
}

compose.desktop {
    application {
        mainClass = "com.emustation.emusync.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "emusync"
            packageVersion = "1.0.0"
            vendor = "emusync"
            description = "エミュのセーブを自宅サーバーと同期するランチャー"
            // suggestRuntimeModules の結果 ＋ TLS（楕円曲線の暗号）
            modules("java.instrument", "jdk.unsupported", "jdk.crypto.ec", "java.naming")
            windows {
                iconFile.set(project.file("packaging/emusync.ico"))
                menuGroup = "emusync"
                upgradeUuid = "6b1f3a52-8a0e-4c3e-9a55-3f8f2a7f5e10"
            }
        }
    }
}
