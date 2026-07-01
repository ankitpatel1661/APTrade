import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm()

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
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("com.ionspin.kotlin:bignum:0.3.10")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
        }
    }
}
