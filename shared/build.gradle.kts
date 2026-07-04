import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
}

kotlin {
    // Keep the standard KMP source-set graph (commonMain → appleMain → iosMain/macosMain,
    // etc.). Applied explicitly because the manual jvmCommon wiring below would otherwise
    // suppress the automatic template and orphan the Apple actuals.
    applyDefaultHierarchyTemplate()

    jvm()

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    val xcf = XCFramework("Shared")
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
        macosArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            xcf.add(this)
        }
    }

    sourceSets {
        // Intermediate source set shared by the JVM and Android targets ONLY (java.nio +
        // JVM stdlib). It deliberately does NOT join the Apple tree, so the macOS/iOS
        // xcframework stays free of any java.nio dependency. Holds FilePortfolioStore,
        // which is pure java.nio + kotlinx-serialization + ionspin BigDecimal.
        val jvmCommonMain by creating { dependsOn(commonMain.get()) }
        val jvmCommonTest by creating { dependsOn(commonTest.get()) }
        jvmMain.get().dependsOn(jvmCommonMain)
        androidMain.get().dependsOn(jvmCommonMain)
        jvmTest.get().dependsOn(jvmCommonTest)

        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            api("com.ionspin.kotlin:bignum:0.3.10")
            implementation("io.ktor:ktor-client-core:3.0.3")
            implementation("io.ktor:ktor-client-content-negotiation:3.0.3")
            implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
            implementation("io.ktor:ktor-client-mock:3.0.3")
        }
        jvmMain.dependencies {
            implementation("io.ktor:ktor-client-cio:3.0.3")
        }
        androidMain.dependencies {
            implementation("io.ktor:ktor-client-okhttp:3.0.3")
        }
        appleMain.dependencies {
            implementation("io.ktor:ktor-client-darwin:3.0.3")
        }
    }
}

android {
    namespace = "com.aptrade.shared"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
