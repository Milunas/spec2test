package io.github.spec2test.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class Spec2TestCliTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `prints usage for help without errors`() {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        val exitCode = Spec2TestCli.run(
            args = arrayOf("--help"),
            workingDir = tempDir.toFile(),
            stdout = PrintStream(stdout),
            stderr = PrintStream(stderr)
        )

        assertThat(exitCode).isZero()
        assertThat(stdout.toString()).contains("spec2test preview CLI")
        assertThat(stderr.toString()).isEmpty()
    }

    @Test
    fun `suggests a matching relative spec path when a unique file exists`() {
        val examplesDir = Files.createDirectories(tempDir.resolve("examples/reservation"))
        Files.writeString(examplesDir.resolve("Reservation.tla"), "---- MODULE Reservation ----\n====")

        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        val exitCode = Spec2TestCli.run(
            args = arrayOf("--spec", "examples/Reservation.tla", "--stdout"),
            workingDir = tempDir.toFile(),
            stdout = PrintStream(stdout),
            stderr = PrintStream(stderr)
        )

        assertThat(exitCode).isEqualTo(1)
        assertThat(stdout.toString()).isEmpty()
        assertThat(stderr.toString()).contains("spec2test: spec file not found:")
        assertThat(stderr.toString()).contains("spec2test: did you mean: examples/reservation/Reservation.tla")
    }

    @Test
    fun `conformance mode generates interface and abstract test to stdout`() {
        val specContent = """
            ---- MODULE Tiny ----
            EXTENDS Naturals
            VARIABLE x
            Init == x = 0
            Step == x' = x + 1
            Next == Step
            Inv == x >= 0
            Spec == Init /\ [][Next]_x
            ====
        """.trimIndent()

        val specFile = Files.writeString(tempDir.resolve("Tiny.tla"), specContent)

        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        val exitCode = Spec2TestCli.run(
            args = arrayOf("--spec", specFile.toString(), "--mode", "CONFORMANCE", "--stdout"),
            workingDir = tempDir.toFile(),
            stdout = PrintStream(stdout),
            stderr = PrintStream(stderr)
        )

        val output = stdout.toString()
        assertThat(exitCode).isZero()
        assertThat(output).contains("interface TinySpec")
        assertThat(output).contains("abstract class AbstractTinyConformanceTest")
        assertThat(output).contains("protected abstract TinySpec createSubject()")
        assertThat(output).contains("getX()")
    }
}