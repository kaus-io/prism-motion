pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        val strictRelease = providers.gradleProperty("releaseStrictDependencies").orNull == "true"
        providers.gradleProperty("dependencyRepositoryUrl").orNull?.let { repositoryUrl ->
            require(!repositoryUrl.contains('@')) { "Dependency repository URL must not embed credentials" }
            maven { url = uri(repositoryUrl) }
        }
        if (!strictRelease) mavenLocal()
        mavenCentral()
    }
}

rootProject.name = "prism-motion"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(
    ":prism-motion-core",
    ":prism-motion-compose",
    ":prism-motion-engine-kap",
    ":prism-motion-engine-vap",
    ":prism-motion-probe",
    ":bench-android",
    ":bench-jvm",
    ":demo-android",
    ":demo-desktop",
    ":prism-motion-cli",
    ":prism-motion-utils",
    ":prism-motion-format",
    ":prism-motion-ktx",
    ":prism-motion-vap",
    ":utils-jvm",
)
