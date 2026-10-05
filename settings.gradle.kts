pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "HabitsAndroid"
include(":core")
// Keep portable contract/store verification available without installing the SDK.
if (providers.gradleProperty("coreOnly").orNull != "true") include(":app")
