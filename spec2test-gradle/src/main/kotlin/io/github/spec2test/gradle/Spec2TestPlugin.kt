package io.github.spec2test.gradle

import io.github.spec2test.generator.JavaTestGenerator
import io.github.spec2test.parser.SanyTlaParser
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import java.io.File

/**
 * Gradle plugin for spec2test: generates JUnit 5 test classes from TLA+ specifications.
 *
 * Usage in build.gradle.kts:
 * ```
 * plugins {
 *     id("io.github.spec2test")
 * }
 *
 * spec2test {
 *     tlaSourceDir.set(file("src/main/tla"))
 *     packageName.set("com.myproject.generated")
 *     mode.set("CONCURRENT")
 *     threads.set(3)
 *     stepsPerTest.set(100)
 *     embedInvariants.set(true)
 * }
 * ```
 *
 * Run: `./gradlew generateSpec2Tests`
 */
class Spec2TestPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("spec2test", Spec2TestExtension::class.java)

        val generateTask = project.tasks.register("generateSpec2Tests", GenerateSpec2TestsTask::class.java) { task ->
            task.tlaSourceDir.set(extension.tlaSourceDir)
            task.outputDir.set(extension.outputDir)
            task.packageName.set(extension.packageName)
            task.mode.set(extension.mode)
            task.threads.set(extension.threads)
            task.stepsPerTest.set(extension.stepsPerTest)
            task.numRandomTests.set(extension.numRandomTests)
            task.embedInvariants.set(extension.embedInvariants)
        }

        // Auto-wire generated sources into the test source set
        project.afterEvaluate {
            project.extensions.findByType(org.gradle.api.plugins.JavaPluginExtension::class.java)?.let { javaExt ->
                javaExt.sourceSets.findByName("test")?.java?.srcDir(
                    extension.outputDir.getOrElse(project.layout.buildDirectory.dir("generated/spec2test").get())
                )
            }

            project.tasks.findByName("compileTestJava")?.dependsOn(generateTask)
        }
    }
}

abstract class Spec2TestExtension {
    abstract val tlaSourceDir: DirectoryProperty
    abstract val outputDir: DirectoryProperty
    abstract val packageName: Property<String>
    abstract val mode: Property<String>
    abstract val threads: Property<Int>
    abstract val stepsPerTest: Property<Int>
    abstract val numRandomTests: Property<Int>
    abstract val embedInvariants: Property<Boolean>
}

abstract class GenerateSpec2TestsTask : DefaultTask() {

    @get:InputDirectory
    abstract val tlaSourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val mode: Property<String>

    @get:Input
    abstract val threads: Property<Int>

    @get:Input
    abstract val stepsPerTest: Property<Int>

    @get:Input
    abstract val numRandomTests: Property<Int>

    @get:Input
    abstract val embedInvariants: Property<Boolean>

    init {
        group = "spec2test"
        description = "Generate JUnit 5 test classes from TLA+ specifications"
    }

    @TaskAction
    fun generate() {
        val sourceDir = tlaSourceDir.get().asFile
        val outDir = outputDir.get().asFile

        if (!sourceDir.exists()) {
            logger.warn("TLA+ source directory does not exist: ${sourceDir.absolutePath}")
            return
        }

        val tlaFiles = sourceDir.walkTopDown().filter { it.extension == "tla" }.toList()
        if (tlaFiles.isEmpty()) {
            logger.warn("No .tla files found in ${sourceDir.absolutePath}")
            return
        }

        val parser = SanyTlaParser()
        val generator = JavaTestGenerator()

        val generationMode = try {
            JavaTestGenerator.GenerationMode.valueOf(mode.getOrElse("SEQUENTIAL"))
        } catch (e: IllegalArgumentException) {
            JavaTestGenerator.GenerationMode.SEQUENTIAL
        }

        val config = JavaTestGenerator.Config(
            packageName = packageName.getOrElse("io.github.spec2test.generated"),
            mode = generationMode,
            threads = threads.getOrElse(3),
            stepsPerTest = stepsPerTest.getOrElse(100),
            numRandomTests = numRandomTests.getOrElse(50),
            embedInvariants = embedInvariants.getOrElse(true)
        )

        val packageDir = config.packageName.replace('.', File.separatorChar)
        val outputPath = File(outDir, packageDir)
        outputPath.mkdirs()

        var generatedCount = 0

        for (tlaFile in tlaFiles) {
            logger.lifecycle("Parsing TLA+ spec: ${tlaFile.name}")

            val result = parser.parseFile(tlaFile)

            if (!result.isSuccessful) {
                result.errors.forEach { logger.error("  ERROR: ${it.message}") }
                continue
            }

            result.warnings.forEach { logger.warn("  WARN: ${it.message}") }

            val module = result.module!!
            logger.lifecycle("  Module: ${module.name}")
            logger.lifecycle("  Variables: ${module.variables.joinToString(", ") { it.name }}")
            logger.lifecycle("  Actions: ${module.actions.joinToString(", ") { it.name }}")
            logger.lifecycle("  Invariants: ${module.invariants.joinToString(", ") { it.name }}")

            val tests = generator.generate(module, config)
            for (test in tests) {
                val outFile = File(outputPath, "${test.className}.java")
                outFile.writeText(test.code)
                logger.lifecycle("  Generated: ${outFile.name} (${test.mode})")
                generatedCount++
            }
        }

        logger.lifecycle("spec2test: Generated $generatedCount test file(s)")
    }
}
