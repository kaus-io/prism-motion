import java.net.URI

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

/** 显式接收 CI 的仓库地址，不回退到本机 Maven。 / Requires CI repository URLs without falling back to local Maven. */
fun requiredRepositoryUrl(name: String): URI {
    val value = requireNotNull(providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }) {
        "Provide -P$name=<absolute staging repository URL>"
    }
    val repository = URI(value)
    require(repository.isAbsolute && repository.scheme in setOf("file", "https")) {
        "$name must be an absolute file: or https: URL"
    }
    require(repository.rawUserInfo == null && repository.query == null && repository.fragment == null) {
        "$name must not contain credentials, a query, or a fragment"
    }
    if (repository.scheme == "file") {
        require(repository.path?.startsWith('/') == true && repository.authority.isNullOrEmpty()) {
            "$name must refer to an absolute local staging directory"
        }
    }
    return repository
}

val prismVersion = requireNotNull(providers.gradleProperty("prismVersion").orNull?.takeIf { it.isNotBlank() }) {
    "Provide -PprismVersion=<version from the root manifest or release CI>"
}
require(Regex("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)(?:-(?:alpha|beta|rc)(?:0[1-9]|[1-9][0-9]))?").matches(prismVersion)) {
    "prismVersion must use SemVer with optional -alphaNN/-betaNN/-rcNN (01..99)"
}
val prismRepository = requiredRepositoryUrl("prismRepositoryUrl")
val sdkRepository = requiredRepositoryUrl("sdkRepositoryUrl")
require(prismRepository.normalize() != sdkRepository.normalize()) {
    "Prism and SDK publications must use separate staging repositories"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    versionCatalogs {
        create("libs") {
            from(files("../../gradle/libs.versions.toml"))
        }
    }
    repositories {
        maven {
            name = "prismStage"
            url = prismRepository
            content { includeModuleByRegex("com\\.kaus-io", "prism-motion(?:-.*)?") }
        }
        maven {
            name = "sdkStage"
            url = sdkRepository
            content { includeModuleByRegex("com\\.kaus-io", "(?:kap|vap)(?:-.*)?") }
        }
        google {
            content { excludeGroup("com.kaus-io") }
        }
        mavenCentral {
            content { excludeGroup("com.kaus-io") }
        }
    }
}

rootProject.name = "prism-maven-consumer"
include(":jvm", ":android")
