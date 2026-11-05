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
        maven("https://nexus-external.rustore.ru/repository/maven-rustore-exposed") {
            content { includeGroupByRegex("ru\\.rustore\\.sdk.*") }
        }
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "Стоп Энурез"
include(":app")
