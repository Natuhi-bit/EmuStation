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

    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(compose.desktop.uiTestJUnit4)
}

tasks.withType<Test>().configureEach {
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
            modules("java.naming", "jdk.crypto.ec", "java.net.http")
            windows {
                iconFile.set(project.file("packaging/emusync.ico"))
                menuGroup = "emusync"
                upgradeUuid = "6b1f3a52-8a0e-4c3e-9a55-3f8f2a7f5e10"
            }
        }
    }
}
