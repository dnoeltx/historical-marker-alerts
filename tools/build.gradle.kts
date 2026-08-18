plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

// A plain JVM module. HTTP comes from the JDK's built-in java.net.http.HttpClient,
// so the only third-party dependencies are JSON parsing and the SQLite driver.
dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sqlite.jdbc)

    testImplementation(libs.junit)
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.dnoel.markeralerts.tools.MainKt")
}

// Run with:  ./gradlew :tools:run --args="--out app/src/main/assets/markers.db"
// Refresh:   ./gradlew :tools:run --args="--refresh --max-age-days 90"
// Responses are cached under tools/scratch/ (gitignored), so re-runs are fast
// and the matching logic can be tuned without re-fetching anything.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

tasks.withType<Test>().configureEach {
    useJUnit()
}
