/*
  This convention defines a standard TripleA java library project.
  It applies the `java-library` plugin and applies universal configuration, code conventions, and sets up static analysis.
*/

plugins {
    `java-library`
    id("com.diffplug.spotless")
}

group = "triplea"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

spotless {
    format("allFiles") {
        target("*")
        targetExclude("gradlew.bat")
        endWithNewline()
        leadingTabsToSpaces()
        trimTrailingWhitespace()
    }

    java {
        googleJavaFormat()
        removeUnusedImports()
    }
}

// map-room/map-room#2015: spotlessJavaCheck is excluded from UP-TO-DATE
// avoidance and the build cache. Five files drifted out of the project's
// Java formatting standard without anyone noticing, because nothing re-ran
// this check once the files' own inputs stopped changing — it surfaced only
// on a build with no cache to reuse. The check is cheap; silent drift
// across an unknown number of files is not.
tasks.named("spotlessJavaCheck") {
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}
