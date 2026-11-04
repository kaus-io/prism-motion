import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar
import org.gradle.api.publish.PublishingExtension
import org.gradle.jvm.tasks.Jar
import org.gradle.plugins.signing.SigningExtension
import java.io.File

plugins {
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.maven.publish) apply false
}

val sdkGroup = providers.gradleProperty("group").get()
val sdkVersion = providers.gradleProperty("version").get()
require(sdkGroup == "com.kaus-io") { "Unexpected SDK group: $sdkGroup" }
require(sdkVersion == "1.0.0-alpha01") { "This frozen snapshot is only for 1.0.0-alpha01" }
group = sdkGroup
version = sdkVersion

val stagingRepository = providers.gradleProperty("stagingRepository").orNull?.let { path ->
    require(File(path).isAbsolute) { "stagingRepository must be an absolute OS-temporary path" }
    val temporaryRoot = File(
        providers.environmentVariable("TMPDIR")
            .orElse(providers.environmentVariable("TEMP"))
            .orElse(providers.environmentVariable("TMP"))
            .getOrElse(System.getProperty("java.io.tmpdir")),
    ).canonicalFile.toPath()
    val target = file(path).canonicalFile.toPath()
    require(target != temporaryRoot && target.startsWith(temporaryRoot)) {
        "stagingRepository must be an isolated directory under $temporaryRoot"
    }
    require(!target.startsWith(rootDir.canonicalFile.toPath())) {
        "stagingRepository must not be inside the snapshot"
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
val signingKey = providers.gradleProperty("signingInMemoryKey")
    .orElse(providers.environmentVariable("GPG_KEY_CONTENTS"))
val signingKeyId = providers.gradleProperty("signingInMemoryKeyId")
    .orElse(providers.environmentVariable("SIGNING_KEY_ID"))
val signingPassword = providers.gradleProperty("signingInMemoryKeyPassword")
    .orElse(providers.environmentVariable("SIGNING_PASSWORD"))
val publishableModules = listOf(
    "vap-log", "vap-core", "vap-decode-api", "vap-decode-jvm",
    "vap-vk-android", "vap-decode-android", "vap-compose",
)

val stageSdkPublications = tasks.register("stageSdkPublications") {
    group = "publishing"
    description = "Publishes the frozen VAP module closure to ReleaseStaging."
    val stagingConfigured = stagingRepository != null
    doFirst {
        require(stagingConfigured) { "Provide -PstagingRepository=<absolute OS-temporary directory>" }
    }
}
tasks.register("printPublishableModules") {
    group = "help"
    val names = publishableModules
    doLast { names.sorted().forEach { println(it) } }
}

subprojects {
    require(name in publishableModules) { "Module outside the frozen release closure: $path" }
    group = sdkGroup
    version = sdkVersion
    val moduleName = name
    val modulePath = path
    val snapshotUrl = "https://github.com/kaus-io/prism-motion/tree/main/thirdparty/prism-sdk/vap"
    val sdkLicense = rootProject.layout.projectDirectory.file("LICENSE")
    val sdkNotice = rootProject.layout.projectDirectory.file("NOTICE")

    // 基础插件不会由外部属性隐式启用 Central。 / The base plugin never implicitly enables Central from external properties.
    pluginManager.apply("com.vanniktech.maven.publish.base")
    extensions.configure<MavenPublishBaseExtension> {
        coordinates(sdkGroup, moduleName, sdkVersion)
        if (stagingRepository == null) {
            publishToMavenCentral(automaticRelease = false)
        }
        if (!skipSigning) {
            signAllPublications()
        }
        pom {
            name.set(moduleName)
            description.set("Frozen Kotlin Multiplatform VAP animation SDK source snapshot for Prism Motion.")
            inceptionYear.set("2026")
            url.set(snapshotUrl)
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
                url.set(snapshotUrl)
                connection.set("scm:git:https://github.com/kaus-io/prism-motion.git")
                developerConnection.set("scm:git:ssh://git@github.com/kaus-io/prism-motion.git")
            }
        }
    }
    if (!skipSigning && signingKey.isPresent) {
        extensions.configure<SigningExtension> {
            useInMemoryPgpKeys(signingKeyId.orNull, signingKey.get(), signingPassword.getOrElse(""))
        }
    }
    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<MavenPublishBaseExtension> {
            configure(
                KotlinMultiplatform(
                    javadocJar = JavadocJar.Empty(),
                    sourcesJar = SourcesJar.Sources(),
                    androidVariantsToPublish = emptyList(),
                ),
            )
        }
    }
    pluginManager.withPlugin("com.android.library") {
        extensions.configure<MavenPublishBaseExtension> {
            configure(AndroidSingleVariantLibrary(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Sources()))
        }
    }
    tasks.withType<Jar>().configureEach {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from(listOf(sdkLicense, sdkNotice)) { into("META-INF/prism-sdk/vap/$moduleName") }
        if (moduleName == "vap-vk-android" && name.contains("sources", ignoreCase = true)) {
            from(layout.projectDirectory.dir("src/main/cpp")) { into("native") }
        }
    }
    if (stagingRepository != null) {
        extensions.configure<PublishingExtension> {
            repositories.maven {
                name = "ReleaseStaging"
                url = stagingRepository.toURI()
            }
        }
        stageSdkPublications.configure {
            dependsOn("$modulePath:publishAllPublicationsToReleaseStagingRepository")
        }
    }
}
