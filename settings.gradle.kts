pluginManagement {
    val localCache = System.getenv("LOCAL_MAVEN_CACHE")?.trimEnd('/')
    val localProxy = System.getenv("LOCAL_MAVEN_PROXY")?.trimEnd('/')
    repositories {
        if (localCache != null) {
            maven("$localCache/google")
            maven("$localCache/maven")
            maven("$localCache/plugins")
        }
        if (localProxy != null) {
            maven("$localProxy/google") { isAllowInsecureProtocol = true }
            maven("$localProxy/maven") { isAllowInsecureProtocol = true }
            maven("$localProxy/plugins") { isAllowInsecureProtocol = true }
        }
        if (localCache == null && localProxy == null) {
            google()
            mavenCentral()
            gradlePluginPortal()
        }
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id.startsWith("com.android.")) {
                useModule("com.android.tools.build:gradle:${requested.version}")
            }
        }
    }
}

dependencyResolutionManagement {
    val localCache = System.getenv("LOCAL_MAVEN_CACHE")?.trimEnd('/')
    val localProxy = System.getenv("LOCAL_MAVEN_PROXY")?.trimEnd('/')
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (localCache != null) {
            maven("$localCache/google")
            maven("$localCache/maven")
        }
        if (localProxy != null) {
            maven("$localProxy/google") { isAllowInsecureProtocol = true }
            maven("$localProxy/maven") { isAllowInsecureProtocol = true }
        }
        if (localCache == null && localProxy == null) {
            google()
            mavenCentral()
        }
    }
}

rootProject.name = "AgnesStudio"
include(":app")
