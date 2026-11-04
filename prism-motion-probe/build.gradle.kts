import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    explicitApi()

    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    linuxX64()
    mingwX64()
    if (System.getProperty("os.name").lowercase().contains("mac")) {
        macosX64()
        macosArm64()
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.prismMotionCore)
            api(projects.prismMotionFormat)
            api(projects.prismMotionKtx)
            api(projects.prismMotionVap)
            api(libs.kotlinx.io.core)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
