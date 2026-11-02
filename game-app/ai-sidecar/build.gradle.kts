import java.util.Properties

plugins {
    id("triplea-java-library")
    id("application")
}

application {
    mainClass.set("org.triplea.ai.sidecar.SidecarMain")
    applicationName = "ai-sidecar"

    // JVM tuning for the long-running ProAi compute workload.
    //
    // -Xmx2G: ~8× triplea's headless bot (-Xmx256M in game-headless). The sidecar
    //   holds multiple concurrent GameData clones (one per SIDECAR_WORKERS slot)
    //   plus ProTerritoryManager and odds-calc intermediate state. 1G was the
    //   original ZGC sweet spot, but a 2026-08 re-profile (#3695) saw young-gen
    //   occupancy hit 850–940 MB before GC at 1.55 collections/s, and 8-worker
    //   concurrent decisions sat at ~1.2 GB RSS. 2G gives ZGC more runway.
    //
    // -XX:+UseZGC: ZGC is generational by default since JDK 23 (JEP 474), and the
    //   non-generational mode was removed in JDK 24 (JEP 490), so the old
    //   -XX:+ZGenerational flag is gone: on JDK 25 it is an obsolete option that
    //   only prints a warning. Low-pause collector with excellent throughput on
    //   allocation-heavy workloads. ProAi creates millions of short-lived
    //   Territory/Unit/ProTerritory intermediates during battle simulation and
    //   combat-move planning.
    //
    // -XX:+UseStringDeduplication: coalesces duplicate char[] backing arrays during
    //   GC. Sidecar sees massive string duplication across GameData clones
    //   (territory names, nation names, unit types, step names).
    //
    // -Xshare:auto: explicit Class Data Sharing mode (the JDK default).
    //   Speeds startup by memory-mapping a shared archive of JDK classes.
    applicationDefaultJvmArgs =
        listOf(
            "-Xmx2G",
            "-XX:+UseZGC",
            "-XX:+UseStringDeduplication",
            "-Xshare:auto",
        )
}

// Bake the git commit SHA and fork version into a classpath resource at build time.
// The sidecar health endpoint reads these so operators know exactly which build is running.
//
// providers.exec {} defers the git process to execution time, satisfying the Gradle
// config-cache requirement that external processes must not start at configuration time.
tasks.named<ProcessResources>("processResources") {
    val gitCommitFromExec =
        providers
            .exec {
                commandLine("git", "rev-parse", "--short", "HEAD")
                isIgnoreExitValue = true
            }
            .standardOutput
            .asText
            .map { it.trim() }
            .orElse("unknown")

    // CI passes the commit in directly via GIT_COMMIT (the Docker build stage has
    // no .git/ dir and no git binary — see Dockerfile). Local/dev builds fall back
    // to the git exec above. That exec is wrapped in providers.provider {} here
    // because a MISSING git binary fails at process start, not with a non-zero
    // exit: isIgnoreExitValue and .orElse("unknown") only cover a present-but-failing
    // git, not an absent one, so evaluating gitCommitFromExec.get() can still throw.
    val gitCommitProvider =
        providers.provider {
            System.getenv("GIT_COMMIT")?.takeIf { it.isNotEmpty() }
                ?: try {
                    gitCommitFromExec.get()
                } catch (ignored: Exception) {
                    "unknown"
                }
        }

    var forkVersion = "unknown"
    try {
        val versionFile = rootProject.file("latest_version.properties")
        if (versionFile.exists()) {
            val props = Properties()
            versionFile.inputStream().use { props.load(it) }
            forkVersion = props.getProperty("LATEST", "unknown")
        }
    } catch (ignored: Exception) {
    }

    inputs.property("gitCommit", gitCommitProvider)
    inputs.property("forkVersion", forkVersion)
    filesMatching("sidecar-build.properties") {
        expand("gitCommit" to gitCommitProvider.get(), "forkVersion" to forkVersion)
    }
}

// map-room/map-room#1837: 62 test classes ran serially in one JVM fork — no
// maxParallelForks, no forkEvery. Tests are CPU+IO bound (XML parse via
// CanonicalGameData, battle-sim). Half the available cores is a reasonable
// default on a dev box; forking too aggressively past that hurts more than
// it helps on a contended machine. If a fork exposes a shared-state bug,
// fix the shared state — don't lower this to paper over it. (This overrides
// the triplea-test-conventions default of one fork per core.)
tasks.named<Test>("test") {
    maxParallelForks = maxOf(2, Runtime.getRuntime().availableProcessors() / 2)
}

dependencies {
    implementation(project(":game-core"))
    implementation(project(":domain-data"))
    implementation(project(":java-extras"))
    implementation(project(":xml-reader"))

    // dropwizard-jackson 1.3.14 (transitive via game-core) pulls Jackson 2.9.9,
    // which predates Java record support. Pin Jackson to the catalog version
    // so JsonBodies can (de)serialize our record DTOs at runtime.
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.2")
    implementation("com.fasterxml.jackson.core:jackson-core:2.21.2")

    testImplementation(project(":test-common"))
    testImplementation(testFixtures(project(":game-core")))
}
