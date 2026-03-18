package io.github.spec2test.cli

import io.github.spec2test.generator.JavaTestGenerator
import io.github.spec2test.parser.SanyTlaParser
import java.io.File
import java.io.PrintStream
import kotlin.system.exitProcess

object Spec2TestCli {

    private val suggestionSearchSkipDirs = setOf(".git", ".gradle", "build")

    @JvmStatic
    fun main(args: Array<String>) {
        exitProcess(run(args))
    }

    internal fun run(
        args: Array<String>,
        workingDir: File = File(System.getProperty("user.dir")),
        stdout: PrintStream = System.out,
        stderr: PrintStream = System.err
    ): Int {
        val config = try {
            parseArgs(args)
        } catch (e: IllegalArgumentException) {
            stderr.println("spec2test: ${e.message}")
            printUsage(stdout)
            return 2
        }

        if (config.help) {
            printUsage(stdout)
            return 0
        }

        if (config.mode == JavaTestGenerator.GenerationMode.TRACE_GUIDED) {
            stderr.println(
                "spec2test: TRACE_GUIDED preview requires a TLC state graph and is not available from a .tla file alone"
            )
            return 2
        }

        val parser = SanyTlaParser()
        val generator = JavaTestGenerator()
        var failed = false
        var generatedCount = 0

        for (specPath in config.specPaths) {
            val specFile = resolveSpecFile(workingDir, specPath)
            if (!specFile.exists()) {
                stderr.println(buildMissingSpecMessage(specPath, workingDir))
                failed = true
                continue
            }

            val result = parser.parseFile(specFile)
            if (!result.isSuccessful || result.module == null) {
                stderr.println("spec2test: failed to parse ${specFile.absolutePath}")
                result.errors.forEach { stderr.println("  ERROR: ${it.message}") }
                failed = true
                continue
            }

            val module = result.module ?: continue

            val tests = generator.generate(
                module,
                JavaTestGenerator.Config(
                    packageName = config.packageName,
                    mode = config.mode,
                    threads = config.threads,
                    stepsPerTest = config.stepsPerTest,
                    numRandomTests = config.numRandomTests,
                    embedInvariants = config.embedInvariants
                )
            )

            if (config.stdout) {
                tests.forEachIndexed { index, test ->
                    if (index > 0 || generatedCount > 0) {
                        stdout.println()
                    }
                    stdout.println("// ===== ${test.className}.java (${test.mode}) =====")
                    stdout.println(test.code)
                }
            } else {
                val packageDir = config.packageName.replace('.', File.separatorChar)
                val targetDir = File(config.outputDir, packageDir).apply { mkdirs() }
                tests.forEach { test ->
                    val outputFile = File(targetDir, "${test.className}.java")
                    outputFile.writeText(test.code)
                    stdout.println("generated: ${outputFile.absolutePath}")
                }
            }

            generatedCount += tests.size
        }

        if (!config.stdout) {
            stdout.println("spec2test: generated $generatedCount test file(s)")
        }

        return if (failed) 1 else 0
    }

    private fun parseArgs(args: Array<String>): CliConfig {
        if (args.isEmpty()) {
            return CliConfig(help = true)
        }

        val specPaths = mutableListOf<String>()
        var outputDir = File("build/generated/spec2test-preview")
        var packageName = "generated.spec2test"
        var mode = JavaTestGenerator.GenerationMode.SEQUENTIAL
        var stdout = false
        var threads = 3
        var stepsPerTest = 100
        var numRandomTests = 50
        var embedInvariants = true

        var index = 0
        while (index < args.size) {
            when (val arg = args[index]) {
                "--help", "-h" -> return CliConfig(help = true)
                "--spec" -> {
                    specPaths += requireValue(args, ++index, "--spec")
                }
                "--out" -> {
                    outputDir = File(requireValue(args, ++index, "--out"))
                }
                "--package" -> {
                    packageName = requireValue(args, ++index, "--package")
                }
                "--mode" -> {
                    val raw = requireValue(args, ++index, "--mode")
                    mode = parseMode(raw)
                }
                "--threads" -> {
                    threads = requireValue(args, ++index, "--threads").toInt()
                }
                "--steps" -> {
                    stepsPerTest = requireValue(args, ++index, "--steps").toInt()
                }
                "--random-tests" -> {
                    numRandomTests = requireValue(args, ++index, "--random-tests").toInt()
                }
                "--no-invariants" -> {
                    embedInvariants = false
                }
                "--stdout" -> {
                    stdout = true
                }
                else -> {
                    if (arg.startsWith("-")) {
                        throw IllegalArgumentException("unknown option: $arg")
                    }
                    specPaths += arg
                }
            }
            index++
        }

        if (specPaths.isEmpty()) {
            throw IllegalArgumentException("at least one spec path must be provided with --spec")
        }

        return CliConfig(
            specPaths = specPaths,
            outputDir = outputDir,
            packageName = packageName,
            mode = mode,
            stdout = stdout,
            threads = threads,
            stepsPerTest = stepsPerTest,
            numRandomTests = numRandomTests,
            embedInvariants = embedInvariants,
            help = false
        )
    }

    private fun requireValue(args: Array<String>, index: Int, option: String): String {
        if (index >= args.size) {
            throw IllegalArgumentException("missing value for $option")
        }
        return args[index]
    }

    private fun parseMode(value: String): JavaTestGenerator.GenerationMode {
        val normalized = value.uppercase().replace('-', '_')
        return try {
            JavaTestGenerator.GenerationMode.valueOf(normalized)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("invalid mode '$value' (expected SEQUENTIAL, CONCURRENT, TRACE_GUIDED, or CONFORMANCE)")
        }
    }

    private fun printUsage(output: PrintStream = System.out) {
        output.println(
            """
            spec2test preview CLI

            Usage:
              ./gradlew :spec2test-cli:run --args='--spec /path/to/Spec.tla --stdout'

            Options:
              --spec <file>          TLA+ spec file to parse (repeatable)
              --out <dir>            Output directory for generated files
              --package <name>       Java package name for generated tests
              --mode <mode>          SEQUENTIAL | CONCURRENT | CONFORMANCE
              --threads <n>          Concurrent generation thread count
              --steps <n>            Steps per generated random/stress test
              --random-tests <n>     Number of random sequential tests
              --no-invariants        Disable embedded invariant checks
              --stdout               Print generated Java to stdout
              --help                 Show this help
            """.trimIndent()
        )
    }

    private fun resolveSpecFile(workingDir: File, specPath: String): File {
        val file = File(specPath)
        return if (file.isAbsolute) file else File(workingDir, specPath)
    }

    private fun buildMissingSpecMessage(specPath: String, workingDir: File): String {
        val specFile = resolveSpecFile(workingDir, specPath)
        val suggestion = findSuggestedSpecPath(specPath, workingDir)
        return buildString {
            append("spec2test: spec file not found: ${specFile.absolutePath}")
            if (suggestion != null) {
                appendLine()
                append("spec2test: did you mean: $suggestion")
            }
        }
    }

    private fun findSuggestedSpecPath(specPath: String, workingDir: File): String? {
        if (File(specPath).isAbsolute) {
            return null
        }

        val requestedFile = resolveSpecFile(workingDir, specPath)
        val searchRoot = nearestExistingDirectory(requestedFile.parentFile) ?: workingDir.takeIf { it.exists() }
        val matches = searchRoot
            ?.walkTopDown()
            ?.onEnter { directory -> directory.name !in suggestionSearchSkipDirs }
            ?.filter { candidate -> candidate.isFile && candidate.name.equals(requestedFile.name, ignoreCase = true) }
            ?.take(2)
            ?.toList()
            .orEmpty()

        if (matches.size != 1) {
            return null
        }

        return toDisplayPath(matches.single(), workingDir)
    }

    private fun nearestExistingDirectory(directory: File?): File? {
        var current = directory
        while (current != null && !current.exists()) {
            current = current.parentFile
        }
        return current?.takeIf { it.isDirectory }
    }

    private fun toDisplayPath(file: File, workingDir: File): String {
        val path = runCatching { file.relativeTo(workingDir).path }
            .getOrElse { file.absolutePath }
        return path.replace(File.separatorChar, '/')
    }
}

private data class CliConfig(
    val specPaths: List<String> = emptyList(),
    val outputDir: File = File("build/generated/spec2test-preview"),
    val packageName: String = "generated.spec2test",
    val mode: JavaTestGenerator.GenerationMode = JavaTestGenerator.GenerationMode.SEQUENTIAL,
    val stdout: Boolean = false,
    val threads: Int = 3,
    val stepsPerTest: Int = 100,
    val numRandomTests: Int = 50,
    val embedInvariants: Boolean = true,
    val help: Boolean = false
)