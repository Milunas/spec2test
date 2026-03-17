dependencies {
    api(project(":spec2test-ir"))
    implementation(project(":spec2test-parser"))
}

tasks.test {
    testLogging {
        showStandardStreams = true
    }
}
