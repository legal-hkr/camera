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
// The camera app (sh.hnet.camera)
include(":app")
// Device capability detection (platform API only), shared by the camera app and the diagnostics app
include(":capabilities")
// The app's own photo processing on the GPU (OpenGL ES 3.1 compute): developing RAW frames, later aligning and merging series; shared by the camera app
// and the diagnostics app, whose test checks it against the computer prototype
include(":processing")
// Diagnostics app (sh.hnet.camera.diag): measures what the phone really offers before the camera app is designed
include(":diag")
