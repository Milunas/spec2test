dependencies {
    api(project(":spec2test-ir"))
    implementation(files("${rootProject.projectDir}/libs/tla2tools.jar"))
}

tasks.test {
    testLogging {
        showStandardStreams = true
    }
}
