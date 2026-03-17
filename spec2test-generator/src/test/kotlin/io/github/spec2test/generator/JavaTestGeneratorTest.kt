package io.github.spec2test.generator

import io.github.spec2test.ir.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested

class JavaTestGeneratorTest {

    private val generator = JavaTestGenerator()

    // ─── SEQUENTIAL TEST GENERATION ──────────────────────────────────────

    @Nested
    inner class SequentialTests {

        @Test
        fun `generates test class with correct name`() {
            val module = counterModule()
            val config = defaultConfig()
            val tests = generator.generate(module, config)
            assertThat(tests).isNotEmpty()
            assertThat(tests[0].className).isEqualTo("CounterSequentialTest")
        }

        @Test
        fun `generates JUnit 5 imports`() {
            val module = counterModule()
            val tests = generator.generate(module, defaultConfig())
            val code = tests[0].code
            assertThat(code).contains("import org.junit.jupiter.api.Test;")
            assertThat(code).contains("import org.junit.jupiter.api.BeforeEach;")
        }

        @Test
        fun `generates state variables as fields`() {
            val module = counterModule()
            val tests = generator.generate(module, defaultConfig())
            val code = tests[0].code
            assertThat(code).contains("private int count;")
        }

        @Test
        fun `generates init method with BeforeEach`() {
            val module = counterModule()
            val tests = generator.generate(module, defaultConfig())
            val code = tests[0].code
            assertThat(code).contains("@BeforeEach")
            assertThat(code).contains("void init()")
            assertThat(code).contains("this.count = 0")
        }

        @Test
        fun `generates action methods`() {
            val module = counterModule()
            val tests = generator.generate(module, defaultConfig())
            val code = tests[0].code
            assertThat(code).contains("String Increment(")
            assertThat(code).contains("String Decrement(")
            assertThat(code).contains("String Read(")
        }

        @Test
        fun `generates invariant checker`() {
            val module = counterModule()
            val tests = generator.generate(module, defaultConfig())
            val code = tests[0].code
            assertThat(code).contains("private void checkInvariants()")
            assertThat(code).contains("NonNegative")
            assertThat(code).contains("BoundedAbove")
        }

        @Test
        fun `embeds invariant checks in action methods`() {
            val module = counterModule()
            val tests = generator.generate(module, defaultConfig())
            val code = tests[0].code
            assertThat(code).contains("checkInvariants();")
        }

        @Test
        fun `generates random walk test`() {
            val module = counterModule()
            val tests = generator.generate(module, defaultConfig())
            val code = tests[0].code
            assertThat(code).contains("@RepeatedTest(")
            assertThat(code).contains("void randomWalkTest()")
        }

        @Test
        fun `generates bounded enumeration test for small action spaces`() {
            val module = counterModule()
            val tests = generator.generate(module, defaultConfig())
            val code = tests[0].code
            assertThat(code).contains("void boundedEnumerationTest()")
        }
    }

    // ─── CONCURRENT TEST GENERATION ──────────────────────────────────────

    @Nested
    inner class ConcurrentTests {

        @Test
        fun `generates concurrent test class`() {
            val module = counterModule()
            val config = defaultConfig().copy(mode = JavaTestGenerator.GenerationMode.CONCURRENT)
            val tests = generator.generate(module, config)
            val concurrentTest = tests.find { it.mode == JavaTestGenerator.GenerationMode.CONCURRENT }
            assertThat(concurrentTest).isNotNull
            assertThat(concurrentTest!!.className).isEqualTo("CounterConcurrentTest")
        }

        @Test
        fun `concurrent test has synchronized action methods`() {
            val config = defaultConfig().copy(mode = JavaTestGenerator.GenerationMode.CONCURRENT)
            val tests = generator.generate(counterModule(), config)
            val code = tests.find { it.mode == JavaTestGenerator.GenerationMode.CONCURRENT }!!.code
            assertThat(code).contains("synchronized (lock)")
        }

        @Test
        fun `concurrent test has phased barrier test`() {
            val config = defaultConfig().copy(mode = JavaTestGenerator.GenerationMode.CONCURRENT)
            val tests = generator.generate(counterModule(), config)
            val code = tests.find { it.mode == JavaTestGenerator.GenerationMode.CONCURRENT }!!.code
            assertThat(code).contains("CyclicBarrier")
            assertThat(code).contains("concurrentPhasedTest")
        }

        @Test
        fun `concurrent test has stress test`() {
            val config = defaultConfig().copy(mode = JavaTestGenerator.GenerationMode.CONCURRENT)
            val tests = generator.generate(counterModule(), config)
            val code = tests.find { it.mode == JavaTestGenerator.GenerationMode.CONCURRENT }!!.code
            assertThat(code).contains("concurrentStressTest")
            assertThat(code).contains("CountDownLatch")
        }

        @Test
        fun `concurrent test has trace logging`() {
            val config = defaultConfig().copy(mode = JavaTestGenerator.GenerationMode.CONCURRENT)
            val tests = generator.generate(counterModule(), config)
            val code = tests.find { it.mode == JavaTestGenerator.GenerationMode.CONCURRENT }!!.code
            assertThat(code).contains("List<String> trace")
        }
    }

    // ─── EXPRESSION TRANSLATION ──────────────────────────────────────────

    @Nested
    inner class ExprTranslation {

        private val translator = JavaExprTranslator(
            mapOf("count" to TlaType.IntType, "items" to TlaType.SetType(TlaType.IntType)),
            mapOf("MaxValue" to TlaType.IntType)
        )

        @Test
        fun `translates integer literal`() {
            assertThat(translator.translateExpr(TlaExpr.IntLit(42))).isEqualTo("42")
        }

        @Test
        fun `translates boolean literal`() {
            assertThat(translator.translateExpr(TlaExpr.BoolLit(true))).isEqualTo("true")
        }

        @Test
        fun `translates string literal`() {
            assertThat(translator.translateExpr(TlaExpr.StringLit("hello"))).isEqualTo("\"hello\"")
        }

        @Test
        fun `translates variable reference`() {
            assertThat(translator.translateExpr(TlaExpr.NameRef("count"))).isEqualTo("count")
        }

        @Test
        fun `translates arithmetic`() {
            val expr = TlaExpr.OpApp(TlaOper.PLUS, listOf(TlaExpr.NameRef("count"), TlaExpr.IntLit(1)))
            assertThat(translator.translateExpr(expr)).isEqualTo("(count + 1)")
        }

        @Test
        fun `translates comparison`() {
            val expr = TlaExpr.OpApp(TlaOper.GT, listOf(TlaExpr.NameRef("count"), TlaExpr.IntLit(0)))
            assertThat(translator.translateExpr(expr)).isEqualTo("count > 0")
        }

        @Test
        fun `translates set membership`() {
            val expr = TlaExpr.OpApp(TlaOper.IN, listOf(TlaExpr.IntLit(3), TlaExpr.NameRef("items")))
            assertThat(translator.translateExpr(expr)).isEqualTo("items.contains(3)")
        }

        @Test
        fun `translates set enumeration`() {
            val expr = TlaExpr.SetEnumExpr(listOf(TlaExpr.IntLit(1), TlaExpr.IntLit(2), TlaExpr.IntLit(3)))
            assertThat(translator.translateExpr(expr)).isEqualTo("Set.of(1, 2, 3)")
        }

        @Test
        fun `translates conjunction list`() {
            val expr = TlaExpr.ConjList(listOf(TlaExpr.BoolLit(true), TlaExpr.BoolLit(false)))
            assertThat(translator.translateExpr(expr)).isEqualTo("(true) && (false)")
        }

        @Test
        fun `translates universal quantifier`() {
            val expr = TlaExpr.QuantExpr(
                QuantKind.FORALL,
                listOf(QuantBound("x", TlaExpr.NameRef("items"))),
                TlaExpr.OpApp(TlaOper.GT, listOf(TlaExpr.ParamRef("x"), TlaExpr.IntLit(0)))
            )
            val result = translator.translateExpr(expr)
            assertThat(result).contains("allMatch")
            assertThat(result).contains("x ->")
        }

        @Test
        fun `translates existential quantifier`() {
            val expr = TlaExpr.QuantExpr(
                QuantKind.EXISTS,
                listOf(QuantBound("x", TlaExpr.NameRef("items"))),
                TlaExpr.OpApp(TlaOper.EQ, listOf(TlaExpr.ParamRef("x"), TlaExpr.IntLit(0)))
            )
            val result = translator.translateExpr(expr)
            assertThat(result).contains("anyMatch")
        }

        @Test
        fun `translates Append`() {
            val expr = TlaExpr.OpApp(TlaOper.APPEND, listOf(TlaExpr.NameRef("seq"), TlaExpr.IntLit(42)))
            assertThat(translator.translateExpr(expr)).contains("append")
        }

        @Test
        fun `translates Cardinality`() {
            val expr = TlaExpr.OpApp(TlaOper.CARDINALITY, listOf(TlaExpr.NameRef("items")))
            assertThat(translator.translateExpr(expr)).isEqualTo("items.size()")
        }

        @Test
        fun `translates IF-THEN-ELSE`() {
            val expr = TlaExpr.IfThenElse(
                TlaExpr.BoolLit(true),
                TlaExpr.IntLit(1),
                TlaExpr.IntLit(2)
            )
            assertThat(translator.translateExpr(expr)).contains("? 1 : 2")
        }
    }

    // ─── TYPE MAPPING ────────────────────────────────────────────────────

    @Nested
    inner class TypeMapping {

        @Test
        fun `maps IntType to int`() {
            assertThat(JavaExprTranslator.typeToJava(TlaType.IntType)).isEqualTo("int")
        }

        @Test
        fun `maps BoolType to boolean`() {
            assertThat(JavaExprTranslator.typeToJava(TlaType.BoolType)).isEqualTo("boolean")
        }

        @Test
        fun `maps SetType to Set`() {
            assertThat(JavaExprTranslator.typeToJava(TlaType.SetType(TlaType.IntType))).isEqualTo("Set<Integer>")
        }

        @Test
        fun `maps SeqType to List`() {
            assertThat(JavaExprTranslator.typeToJava(TlaType.SeqType(TlaType.StringType))).isEqualTo("List<String>")
        }

        @Test
        fun `maps FunctionType to Map`() {
            assertThat(JavaExprTranslator.typeToJava(TlaType.FunctionType(TlaType.IntType, TlaType.IntType)))
                .isEqualTo("Map<Integer, Integer>")
        }

        @Test
        fun `maps mutable Set type`() {
            assertThat(JavaExprTranslator.typeToMutableJava(TlaType.SetType(TlaType.IntType)))
                .isEqualTo("HashSet<Integer>")
        }
    }

    // ─── TLC TRACE GENERATOR ─────────────────────────────────────────────

    @Nested
    inner class TlcTraceTests {

        @Test
        fun `generates trace test from state graph`() {
            val module = counterModule()
            val graph = sampleStateGraph()
            val traceGen = TlcTraceGenerator()
            val config = TlcTraceGenerator.Config(maxTraces = 5)

            val test = traceGen.generate(module, graph, config)
            assertThat(test.className).isEqualTo("CounterTraceTest")
            assertThat(test.code).contains("trace_")
            assertThat(test.code).contains("checkInvariants")
        }

        @Test
        fun `coverage summary includes state graph statistics`() {
            val module = counterModule()
            val graph = sampleStateGraph()
            val traceGen = TlcTraceGenerator()

            val test = traceGen.generate(module, graph, TlcTraceGenerator.Config())
            assertThat(test.code).contains("States explored:")
            assertThat(test.code).contains("Transitions:")
        }

        private fun sampleStateGraph(): TlcTraceGenerator.TlcStateGraph {
            val states = mapOf(
                0 to TlcTraceGenerator.TlcState(0, mapOf("count" to "0", "history" to "<<>>"), true),
                1 to TlcTraceGenerator.TlcState(1, mapOf("count" to "1", "history" to "<<\"inc\">>")),
                2 to TlcTraceGenerator.TlcState(2, mapOf("count" to "2", "history" to "<<\"inc\", \"inc\">>"))
            )
            val transitions = listOf(
                TlcTraceGenerator.TlcTransition(0, 1, "Increment"),
                TlcTraceGenerator.TlcTransition(1, 2, "Increment"),
                TlcTraceGenerator.TlcTransition(1, 0, "Decrement"),
                TlcTraceGenerator.TlcTransition(0, 0, "Read")
            )
            return TlcTraceGenerator.TlcStateGraph(states, transitions, setOf(0))
        }
    }

    // ─── TEST FIXTURES ───────────────────────────────────────────────────

    private fun counterModule(): TlaModule {
        return TlaModule(
            name = "Counter",
            extends = listOf("Naturals", "Sequences"),
            constants = listOf(TlaConstant("MaxValue", TlaType.IntType)),
            variables = listOf(
                TlaVariable("count", TlaType.IntType),
                TlaVariable("history", TlaType.SeqType(TlaType.StringType))
            ),
            operatorDefs = listOf(
                TlaOperatorDef("Init", body = TlaExpr.ConjList(listOf(
                    TlaExpr.OpApp(TlaOper.EQ, listOf(TlaExpr.NameRef("count"), TlaExpr.IntLit(0))),
                    TlaExpr.OpApp(TlaOper.EQ, listOf(TlaExpr.NameRef("history"), TlaExpr.TupleExpr(emptyList())))
                ))),
                TlaOperatorDef("Increment", body = TlaExpr.ConjList(listOf(
                    TlaExpr.OpApp(TlaOper.LT, listOf(TlaExpr.NameRef("count"), TlaExpr.NameRef("MaxValue"))),
                    TlaExpr.OpApp(TlaOper.EQ, listOf(
                        TlaExpr.PrimedRef("count"),
                        TlaExpr.OpApp(TlaOper.PLUS, listOf(TlaExpr.NameRef("count"), TlaExpr.IntLit(1)))
                    )),
                    TlaExpr.OpApp(TlaOper.EQ, listOf(
                        TlaExpr.PrimedRef("history"),
                        TlaExpr.OpApp(TlaOper.APPEND, listOf(TlaExpr.NameRef("history"), TlaExpr.StringLit("inc")))
                    ))
                ))),
                TlaOperatorDef("Decrement", body = TlaExpr.ConjList(listOf(
                    TlaExpr.OpApp(TlaOper.GT, listOf(TlaExpr.NameRef("count"), TlaExpr.IntLit(0))),
                    TlaExpr.OpApp(TlaOper.EQ, listOf(
                        TlaExpr.PrimedRef("count"),
                        TlaExpr.OpApp(TlaOper.MINUS, listOf(TlaExpr.NameRef("count"), TlaExpr.IntLit(1)))
                    )),
                    TlaExpr.OpApp(TlaOper.EQ, listOf(
                        TlaExpr.PrimedRef("history"),
                        TlaExpr.OpApp(TlaOper.APPEND, listOf(TlaExpr.NameRef("history"), TlaExpr.StringLit("dec")))
                    ))
                ))),
                TlaOperatorDef("Read", body = TlaExpr.Unchanged(listOf("count", "history"))),
                TlaOperatorDef("Next", body = TlaExpr.DisjList(listOf(
                    TlaExpr.OperRef("Increment"),
                    TlaExpr.OperRef("Decrement"),
                    TlaExpr.OperRef("Read")
                ))),
                TlaOperatorDef("NonNegative", body =
                    TlaExpr.OpApp(TlaOper.GEQ, listOf(TlaExpr.NameRef("count"), TlaExpr.IntLit(0)))
                ),
                TlaOperatorDef("BoundedAbove", body =
                    TlaExpr.OpApp(TlaOper.LEQ, listOf(TlaExpr.NameRef("count"), TlaExpr.NameRef("MaxValue")))
                ),
                TlaOperatorDef("Spec", body = TlaExpr.OpApp(TlaOper.STUTTER, listOf(
                    TlaExpr.OpApp(TlaOper.AND, listOf(TlaExpr.OperRef("Init"), TlaExpr.OperRef("Next"))),
                    TlaExpr.TupleExpr(listOf(TlaExpr.NameRef("count"), TlaExpr.NameRef("history")))
                )))
            )
        )
    }

    private fun defaultConfig() = JavaTestGenerator.Config(
        packageName = "io.github.spec2test.generated",
        mode = JavaTestGenerator.GenerationMode.SEQUENTIAL,
        threads = 3,
        stepsPerTest = 50,
        numRandomTests = 10,
        embedInvariants = true
    )
}
