plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

group = "jps"
version = "0.1.0"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
    explicitApi()
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(libs.asm)
    implementation(libs.asm.tree)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

application {
    mainClass.set("jps.CliKt")
    applicationDefaultJvmArgs = listOf("-Xmx1g")
}

tasks.test {
    useJUnitPlatform { excludeTags("corpus") }
    maxHeapSize = "1g"
}

// Network-backed corpus checks; archives are downloaded and SHA-256 verified, never committed.
val corpusTest by tasks.registering(Test::class) {
    description = "Runs the pinned-archive corpus tests (needs network)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("corpus") }
    maxHeapSize = "2g"
    outputs.upToDateWhen { false }
    systemProperty("jps.corpus.out", layout.buildDirectory.dir("corpus-out").get().asFile.absolutePath)
    systemProperty("jps.corpus.cache", layout.buildDirectory.dir("corpus-cache").get().asFile.absolutePath)
    systemProperty("jps.pins", file("src/test/resources/corpus-pins.tsv").absolutePath)
}
