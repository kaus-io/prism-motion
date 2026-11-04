import com.vanniktech.maven.publish.DeploymentValidation
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar
import org.gradle.api.publish.PublishingExtension
import java.io.File

// Prism Motion：统一动画门面，VAP / KAP 作为引擎实现。
//
// 所有插件在根项目声明一次并 apply false：AGP 模块与 Kotlin/Native 目标需要在
// 同一个插件 classloader 下解析，否则 K/N 的 build service 会在兄弟项目间冲突。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.maven.publish) apply false
}

// 产品与测量软件独立递增，Android 序号由单独属性声明。 / Product and benchmark software advance independently; Android codes use a separate property.
val semVerPattern = Regex("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)(?:-(?:alpha|beta|rc)(?:0[1-9]|[1-9][0-9]))?")
val prismVersionName = providers.gradleProperty("version").get()
require(semVerPattern.matches(prismVersionName)) {
    "version must use MAJOR.MINOR.PATCH with optional -alphaNN/-betaNN/-rcNN (01..99): $prismVersionName"
}
val androidVersionCode = providers.gradleProperty("androidVersionCode").get()
val prismVersionCode = requireNotNull(androidVersionCode.takeIf { Regex("[1-9][0-9]*").matches(it) }
    ?.toIntOrNull()?.takeIf { it in 1..2100000000 }) {
    "androidVersionCode must be an explicit positive integer (1..2100000000): $androidVersionCode"
}
val prismBenchVersion = providers.gradleProperty("benchVersion").get()
require(semVerPattern.matches(prismBenchVersion)) {
    "benchVersion must use MAJOR.MINOR.PATCH with optional -alphaNN/-betaNN/-rcNN (01..99): $prismBenchVersion"
}

group = "com.kaus-io"
version = prismVersionName
subprojects {
    group = rootProject.group
    version = rootProject.version
}
extra["prismVersionName"] = prismVersionName
extra["prismVersionCode"] = prismVersionCode
extra["prismBenchVersion"] = prismBenchVersion

val publishedLibraries = mapOf(
    ":prism-motion-format" to "MP4, KTX2 and VAP container primitives for Prism Motion.",
    ":prism-motion-core" to "Multiplatform animation contracts and engine registry for Prism Motion.",
    ":prism-motion-compose" to "Compose Multiplatform animation components for Prism Motion.",
    ":prism-motion-engine-kap" to "KAP animation engine integration for Prism Motion.",
    ":prism-motion-engine-vap" to "VAP animation engine integration for Prism Motion.",
    ":prism-motion-ktx" to "Multiplatform KTX2 container support for Prism Motion.",
    ":prism-motion-vap" to "Multiplatform VAP container support for Prism Motion.",
    ":prism-motion-probe" to "Multiplatform animation inspection for Prism Motion.",
    ":prism-motion-utils" to "Multiplatform file and process utilities for Prism Motion.",
    ":utils-jvm" to "JVM native-library loading utilities for Prism Motion.",
)
val stagingRepository = providers.gradleProperty("stagingRepository").orNull?.let { path ->
    val directory = file(path)
    require(File(path).isAbsolute) { "stagingRepository must be an absolute OS-temporary path" }
    val temporaryRoot = File(
        providers.environmentVariable("TMPDIR")
            .orElse(providers.environmentVariable("TEMP"))
            .orElse(providers.environmentVariable("TMP"))
            .getOrElse(System.getProperty("java.io.tmpdir")),
    ).canonicalFile.toPath()
    val target = directory.canonicalFile.toPath()
    require(target != temporaryRoot && target.startsWith(temporaryRoot)) {
        "stagingRepository must be an isolated directory under $temporaryRoot"
    }
    require(!target.startsWith(rootDir.canonicalFile.toPath())) {
        "stagingRepository must not be inside the project"
    }
    target.toFile()
}
val skipSigning = providers.gradleProperty("skipSigning").map { it.toBooleanStrict() }.getOrElse(false)
val runningInCi = listOf("CI", "GITHUB_ACTIONS").any { name ->
    providers.environmentVariable(name).orNull?.let {
        it.isNotBlank() && !it.equals("false", ignoreCase = true) && it != "0"
    } ?: false
}
require(!skipSigning || (stagingRepository != null && !runningInCi)) {
    "skipSigning=true is allowed only for local stagingRepository validation outside CI"
}

// 本地暂存不注册 Central 服务或任务，避免验证命令触发远程上传。 / Local staging registers no Central service or tasks, preventing remote uploads during validation.
val stagePrismPublications = tasks.register("stagePrismPublications") {
    group = "publishing"
    description = "Publishes every Prism library to the isolated stagingRepository directory."
    doFirst {
        require(stagingRepository != null) { "Provide -PstagingRepository=<absolute OS-temporary directory>" }
    }
}
subprojects {
    val libraryDescription = publishedLibraries[path] ?: return@subprojects
    // 避开 prism-motion-utils 的 JVM 目标坐标。 / Avoids colliding with the JVM target of prism-motion-utils.
    val libraryArtifactId = if (name == "utils-jvm") "prism-motion-native-loader" else name
    val libraryGroup = group.toString()
    val libraryVersion = version.toString()
    val libraryPath = path
    pluginManager.apply("com.vanniktech.maven.publish.base")
    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<MavenPublishBaseExtension> {
            coordinates(
                groupId = libraryGroup,
                artifactId = libraryArtifactId,
                version = libraryVersion,
            )
            configure(
                KotlinMultiplatform(
                    javadocJar = JavadocJar.Empty(),
                    sourcesJar = SourcesJar.Sources(),
                    androidVariantsToPublish = emptyList(),
                ),
            )
            if (stagingRepository == null) {
                publishToMavenCentral(
                    automaticRelease = false,
                    validateDeployment = DeploymentValidation.PUBLISHED,
                )
            }
            if (!skipSigning) {
                signAllPublications()
            }
            pom {
                name.set(libraryArtifactId)
                description.set(libraryDescription)
                url.set("https://github.com/kaus-io/prism-motion")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/license/mit/")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("zxhhyj")
                        name.set("zxhhyj")
                        url.set("https://github.com/zxhhyj")
                    }
                }
                scm {
                    url.set("https://github.com/kaus-io/prism-motion")
                    connection.set("scm:git:https://github.com/kaus-io/prism-motion.git")
                    developerConnection.set("scm:git:ssh://git@github.com/kaus-io/prism-motion.git")
                }
            }
        }
    }
    if (stagingRepository != null) {
        extensions.configure<PublishingExtension> {
            repositories.maven {
                name = "staging"
                url = stagingRepository.toURI()
            }
        }
        stagePrismPublications.configure {
            dependsOn("$libraryPath:publishAllPublicationsToStagingRepository")
        }
    }
}

tasks.register("nativeCli") {
    group = "build"
    description = "构建并内嵌工具的原生 CLI（当前平台）"
    dependsOn(":prism-motion-cli:embedTools")
}
