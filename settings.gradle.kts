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
        // kap / vap 目前只发布到本机 maven（0.x-LOCAL），没有公共仓库。
        mavenLocal()
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
)
