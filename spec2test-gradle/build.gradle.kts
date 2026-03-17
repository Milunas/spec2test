plugins {
    `java-gradle-plugin`
}

dependencies {
    implementation(project(":spec2test-parser"))
    implementation(project(":spec2test-generator"))
}

gradlePlugin {
    plugins {
        create("spec2test") {
            id = "io.github.spec2test"
            implementationClass = "io.github.spec2test.gradle.Spec2TestPlugin"
            displayName = "TLA+ to Java Test Generator"
            description = "Generates JUnit 5 test classes from TLA+ specifications"
        }
    }
}
