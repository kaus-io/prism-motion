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
        commonMain.dependencies { api(projects.prismMotionCore) }
        commonTest.dependencies { implementation(kotlin("test")) }

        val posixMain by creating {
            dependsOn(commonMain.get())
            kotlin.srcDir("src/posixMain/kotlin")
        }
        val linuxX64Main by getting { dependsOn(posixMain) }
        val mingwMain by creating {
            dependsOn(commonMain.get())
            kotlin.srcDir("src/mingwMain/kotlin")
        }
        val mingwX64Main by getting { dependsOn(mingwMain) }
        if (System.getProperty("os.name").lowercase().contains("mac")) {
            val macosMain by creating {
                dependsOn(commonMain.get())
                kotlin.srcDir("src/posixMain/kotlin")
            }
            val macosX64Main by getting { dependsOn(macosMain) }
            val macosArm64Main by getting { dependsOn(macosMain) }
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
