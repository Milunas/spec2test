plugins {
    application
}

dependencies {
    implementation(project(":spec2test-parser"))
    implementation(project(":spec2test-generator"))
}

application {
    mainClass.set("io.github.spec2test.cli.Spec2TestCli")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}