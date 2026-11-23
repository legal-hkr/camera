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
        google()
        mavenCentral()
    }
}

rootProject.name = "Camera"
// Device capability detection (platform API only), shared by the diagnostics app and, later, the camera app
include(":capabilities")
// Diagnostics app (sh.hnet.camera.diag): measures what the phone really offers before the camera app is designed
include(":diag")
