import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.application) apply false
}

val prismVersion = providers.gradleProperty("prismVersion").get()
val androidLibraries = listOf(
    "prism-motion-core",
    "prism-motion-compose",
    "prism-motion-engine-kap",
    "prism-motion-engine-vap",
    "prism-motion-format",
    "prism-motion-ktx",
    "prism-motion-vap",
)

subprojects {
    val platform = name
    val expectedVersion = prismVersion
    val expectedLibraries = androidLibraries + if (platform == "jvm") {
        listOf("prism-motion-probe", "prism-motion-utils", "prism-motion-native-loader")
    } else {
        emptyList()
    }
    val classpaths = if (platform == "jvm") {
        listOf("compileClasspath", "runtimeClasspath")
    } else {
        listOf("debugCompileClasspath", "debugRuntimeClasspath")
    }
    val verifyMavenGraph = tasks.register("verifyMavenGraph") {
        group = "verification"
        description = "Verifies published Maven variants without local project dependencies."
        doLast {
            classpaths.forEach { configurationName ->
                val graph = configurations.getByName(configurationName).incoming.resolutionResult
                graph.allDependencies.forEach { dependency ->
                    check(dependency !is UnresolvedDependencyResult) {
                        "Unresolved Maven dependency in $configurationName: ${dependency.requested}"
                    }
                    val selected = (dependency as ResolvedDependencyResult).selected.id
                    check(selected !is ProjectComponentIdentifier) {
                        "Local project dependency in $configurationName: $selected"
                    }
                    val requested = dependency.requested as? ModuleComponentSelector
                    check(requested?.let {
                        it.group == "com.kaus-io" && it.module in setOf("utils-jvm", "utils-jvm-jvm")
                    } != true) {
                        "Stale utils-jvm Maven coordinates in $configurationName"
                    }
                    if (selected is ModuleComponentIdentifier && selected.group == "com.kaus-io" &&
                        selected.module.startsWith("prism-motion")
                    ) {
                        check(selected.version == expectedVersion) {
                            "Unexpected Prism version in $configurationName: $selected"
                        }
                    }
                }
            }
            val artifacts = configurations.getByName(classpaths.last()).incoming.artifacts.artifacts
            val modules = artifacts.mapNotNull { it.id.componentIdentifier as? ModuleComponentIdentifier }
                .filter { it.group == "com.kaus-io" }
            expectedLibraries.forEach { library ->
                check(modules.any { it.module in setOf(library, "$library-$platform") && it.version == expectedVersion }) {
                    "Missing published $platform artifact for com.kaus-io:$library:$expectedVersion"
                }
            }
            check(modules.none { it.module == "utils-jvm" || it.module == "utils-jvm-jvm" }) {
                "Resolved stale utils-jvm instead of prism-motion-native-loader"
            }
            if (platform == "jvm") {
                check(modules.any { it.module == "prism-motion-native-loader-jvm" && it.version == expectedVersion }) {
                    "The engine runtime must resolve the published prism-motion-native-loader-jvm artifact"
                }
            } else {
                check(modules.any { it.module == "kap-android" }) { "Missing transitive KAP Android SDK" }
                check(modules.any { it.module == "vap-vk-android" }) { "Missing transitive VAP Android SDK" }
            }
            logger.lifecycle("Verified $platform Maven graph: ${modules.sortedBy { it.module }.joinToString()}")
        }
    }
    tasks.matching { it.name in setOf("run", "assembleDebug", "dependencies", "check") }.configureEach {
        dependsOn(verifyMavenGraph)
    }
}
