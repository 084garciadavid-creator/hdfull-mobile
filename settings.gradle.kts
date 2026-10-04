pluginManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("file:///home/hatch/workspace/android-build/m2repo") }
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("file:///home/hatch/workspace/android-build/m2repo") }
    }
}
rootProject.name = "hdfull-mobile"
include(":app")
