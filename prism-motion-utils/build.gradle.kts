plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// 平台工具：文件读写（kotlinx-io）、进程、环境变量、临时目录、可执行文件路径。
// 被 CLI 复用；后续其它工具模块也可以直接用。
kotlin {
    jvm()
    linuxX64()
    mingwX64()
    if (System.getProperty("os.name").lowercase().contains("mac")) {
        macosX64()
        macosArm64()
    }

    sourceSets {
        val commonMain by getting {
            dependencies { api(libs.kotlinx.io.core) }
        }
        val posixMain by creating {
            dependsOn(commonMain)
        }
        val linuxX64Main by getting {
            dependsOn(posixMain)
        }
        val mingwMain by creating {
            dependsOn(commonMain)
        }
        val mingwX64Main by getting { dependsOn(mingwMain) }
        if (System.getProperty("os.name").lowercase().contains("mac")) {
            val macosMain by creating {
                dependsOn(posixMain)
            }
            val macosX64Main by getting { dependsOn(macosMain) }
            val macosArm64Main by getting { dependsOn(macosMain) }
        }
    }
}
