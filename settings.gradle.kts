pluginManagement {
    repositories {
        google()
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

rootProject.name = "TurnThePage"
include(
    ":app",
    ":benchmark",
    ":core:adaptive",
    ":core:database",
    ":core:designsystem",
    ":core:files",
    ":core:model",
    ":feature:library",
    ":feature:reader",
    ":format:fb2",
    ":reader:pagination",
    ":reader:rendering",
)
