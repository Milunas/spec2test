package io.github.spec2test.generator

import io.github.spec2test.ir.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class JavaConformanceGeneratorTest {

    private val generator = JavaConformanceGenerator()

    // ─── INTERFACE GENERATION ────────────────────────────────────────────

    @Nested
    inner class InterfaceTests {

        @Test
        fun `generates interface with correct name`() {
            val files = generator.generate(counterModule(), defaultConfig())
            val iface = files.find { it.className == "CounterSpec" }
            assertThat(iface).isNotNull
            assertThat(iface!!.code).contains("public interface CounterSpec")
        }

        @Test
        fun `generates action methods as void`() {
            val files = generator.generate(counterModule(), defaultConfig())
            val code = files.find { it.className == "CounterSpec" }!!.code
            assertThat(code).contains("void increment();")
            assertThat(code).contains("void decrement();")
            assertThat(code).contains("void read();")
        }

        @Test
        fun `generates state getters for variables`() {
            val files = generator.generate(counterModule(), defaultConfig())
            val code = files.find { it.className == "CounterSpec" }!!.code
            assertThat(code).contains("int getCount();")
            assertThat(code).contains("List<String> getHistory();")
        }

        @Test
        fun `generates parameterised action methods`() {
            val files = generator.generate(reservationModule(), defaultConfig())
            val code = files.find { it.className == "ReservationSpec" }!!.code
            assertThat(code).contains("void reserve(int u, int r);")
            assertThat(code).contains("void release(int u, int r);")
            assertThat(code).contains("void checkAvailability(int r);")
        }

        @Test
        fun `generates getters for complex variable types`() {
            val files = generator.generate(reservationModule(), defaultConfig())
            val code = files.find { it.className == "ReservationSpec" }!!.code
            assertThat(code).contains("getReserved();")
            assertThat(code).contains("getAvailable();")
        }

        @Test
        fun `includes javadoc with spec name`() {
            val files = generator.generate(counterModule(), defaultConfig())
            val code = files.find { it.className == "CounterSpec" }!!.code
            assertThat(code).contains("Conformance interface generated from TLA+ specification: Counter")
        }
    }

    // ─── ABSTRACT TEST GENERATION ────────────────────────────────────────

    @Nested
    inner class AbstractTestTests {

        @Test
        fun `generates abstract test with correct name`() {
            val files = generator.generate(counterModule(), defaultConfig())
            val test = files.find { it.className == "AbstractCounterConformanceTest" }
            assertThat(test).isNotNull
            assertThat(test!!.code).contains("public abstract class AbstractCounterConformanceTest")
        }

        @Test
        fun `generates abstract createSubject method`() {
            val files = generator.generate(counterModule(), defaultConfig())
            val code = files.find { it.className == "AbstractCounterConformanceTest" }!!.code
            assertThat(code).contains("protected abstract CounterSpec createSubject();")
        }

        @Test
        fun `generates subject field`() {
            val files = generator.generate(counterModule(), defaultConfig())
            val code = files.find { it.className == "AbstractCounterConformanceTest" }!!.code
            assertThat(code).contains("protected CounterSpec subject;")
        }

        @Test
        fun `generates overridable constant getters`() {
            val files = generator.generate(counterModule(), defaultConfig())
            val code = files.find { it.className == "AbstractCounterConformanceTest" }!!.code
            assertThat(code).contains("protected int getMaxValue()")
            assertThat(code).contains("return 5;")
        }

        @Test
        fun `generates init with initial state assertions`() {
            val files = generator.generate(counterModule(), defaultConfig())
            val code = files.find { it.className == "AbstractCounterConformanceTest" }!!.code
            assertThat(code).contains("@BeforeEach")
            assertThat(code).contains("subject = createSubject();")
            assertThat(code).contains("assertEquals(0, subject.getCount()")
        }

        @Test
        fun `generates guard methods for actions`() {
            val files = generator.generate(counterModule(), defaultConfig())
            val code = files.find { it.className == "AbstractCounterConformanceTest" }!!.code
            assertThat(code).contains("private boolean canIncrement()")
            assertThat(code).contains("private boolean canDecrement()")
            assertThat(code).contains("private boolean canRead()")
        }

        @Test
        fun `guard methods reference subject getters`() {
            val files = generator.generate(counterModule(), defaultConfig())
            val code = files.find { it.className == "AbstractCounterConformanceTest" }!!.code
            assertThat(code).contains("subject.getCount()")
        }

        @Test
        fun `generates invariant checker referencing subject`() {
            val files = generator.generate(counterModule(), defaultConfig())
            val code = files.find { it.className == "AbstractCounterConformanceTest" }!!.code
            assertThat(code).contains("private void checkInvariants()")
            assertThat(code).contains("subject.getCount() >= 0")
            assertThat(code).contains("NonNegative")
            assertThat(code).contains("BoundedAbove")
        }

        @Test
        fun `generates random walk conformance test`() {
            val files = generator.generate(counterModule(), defaultConfig())
            val code = files.find { it.className == "AbstractCounterConformanceTest" }!!.code
            assertThat(code).contains("@RepeatedTest(")
            assertThat(code).contains("void randomWalkConformanceTest()")
            assertThat(code).contains("canIncrement()")
            assertThat(code).contains("subject.increment()")
        }

        @Test
        fun `random walk checks invariants after every step`() {
            val files = generator.generate(counterModule(), defaultConfig())
            val code = files.find { it.className == "AbstractCounterConformanceTest" }!!.code
            // checkInvariants is called inside the step loop
            assertThat(code).contains("checkInvariants();")
        }

        @Test
        fun `generates two files per module`() {
            val files = generator.generate(counterModule(), defaultConfig())
            assertThat(files).hasSize(2)
            assertThat(files.map { it.className }).containsExactlyInAnyOrder(
                "CounterSpec", "AbstractCounterConformanceTest"
            )
        }

        @Test
        fun `generates parameterised guard methods for reservation`() {
            val files = generator.generate(reservationModule(), defaultConfig())
            val code = files.find { it.className == "AbstractReservationConformanceTest" }!!.code
            assertThat(code).contains("private boolean canReserve(int u, int r)")
            assertThat(code).contains("private boolean canRelease(int u, int r)")
        }

        @Test
        fun `generates overridable constant getters for reservation`() {
            val files = generator.generate(reservationModule(), defaultConfig())
            val code = files.find { it.className == "AbstractReservationConformanceTest" }!!.code
            assertThat(code).contains("getCapacity()")
        }
    }

    // ─── EXPRESSION TRANSLATOR WITH GETTER PREFIX ────────────────────────

    @Nested
    inner class GetterPrefixTranslation {

        private val translator = JavaExprTranslator(
            mapOf("count" to TlaType.IntType, "items" to TlaType.SetType(TlaType.IntType)),
            mapOf("MaxValue" to TlaType.IntType)
        ) { name -> JavaExprTranslator.toGetterCall("subject", name) }

        @Test
        fun `variable reference becomes getter call`() {
            val result = translator.translateExpr(TlaExpr.NameRef("count"))
            assertThat(result).isEqualTo("subject.getCount()")
        }

        @Test
        fun `constant reference also uses accessor`() {
            val result = translator.translateExpr(TlaExpr.NameRef("MaxValue"))
            assertThat(result).isEqualTo("subject.getMaxValue()")
        }

        @Test
        fun `arithmetic uses getter for variable`() {
            val expr = TlaExpr.OpApp(TlaOper.PLUS, listOf(TlaExpr.NameRef("count"), TlaExpr.IntLit(1)))
            val result = translator.translateExpr(expr)
            assertThat(result).isEqualTo("(subject.getCount() + 1)")
        }

        @Test
        fun `set membership uses getter`() {
            val expr = TlaExpr.OpApp(TlaOper.IN, listOf(TlaExpr.IntLit(3), TlaExpr.NameRef("items")))
            val result = translator.translateExpr(expr)
            assertThat(result).isEqualTo("subject.getItems().contains(3)")
        }

        @Test
        fun `comparison uses getter`() {
            val expr = TlaExpr.OpApp(TlaOper.GEQ, listOf(TlaExpr.NameRef("count"), TlaExpr.IntLit(0)))
            val result = translator.translateExpr(expr)
            assertThat(result).isEqualTo("subject.getCount() >= 0")
        }

        @Test
        fun `literal is unaffected`() {
            assertThat(translator.translateExpr(TlaExpr.IntLit(42))).isEqualTo("42")
        }

        @Test
        fun `param ref is unaffected`() {
            assertThat(translator.translateExpr(TlaExpr.ParamRef("x"))).isEqualTo("x")
        }
    }

    // ─── HELPER METHODS ──────────────────────────────────────────────────

    @Nested
    inner class HelperMethodTests {

        @Test
        fun `toGetterName capitalises first letter`() {
            assertThat(JavaExprTranslator.toGetterName("count")).isEqualTo("getCount")
            assertThat(JavaExprTranslator.toGetterName("history")).isEqualTo("getHistory")
            assertThat(JavaExprTranslator.toGetterName("MaxValue")).isEqualTo("getMaxValue")
        }

        @Test
        fun `toGetterCall builds full call expression`() {
            assertThat(JavaExprTranslator.toGetterCall("subject", "count"))
                .isEqualTo("subject.getCount()")
        }
    }

    // ─── INTEGRATION VIA JavaTestGenerator ───────────────────────────────

    @Nested
    inner class IntegrationViaTestGenerator {

        @Test
        fun `CONFORMANCE mode produces interface and abstract test`() {
            val testGen = JavaTestGenerator()
            val config = JavaTestGenerator.Config(
                packageName = "io.github.spec2test.generated",
                mode = JavaTestGenerator.GenerationMode.CONFORMANCE,
                stepsPerTest = 50,
                numRandomTests = 10
            )
            val tests = testGen.generate(counterModule(), config)
            assertThat(tests).hasSize(2)
            assertThat(tests.map { it.className }).containsExactlyInAnyOrder(
                "CounterSpec", "AbstractCounterConformanceTest"
            )
            assertThat(tests.all { it.mode == JavaTestGenerator.GenerationMode.CONFORMANCE }).isTrue()
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

    private fun reservationModule(): TlaModule {
        return TlaModule(
            name = "Reservation",
            extends = listOf("Naturals", "FiniteSets"),
            constants = listOf(
                TlaConstant("Users", TlaType.SetType(TlaType.IntType)),
                TlaConstant("Resources", TlaType.SetType(TlaType.IntType)),
                TlaConstant("Capacity", TlaType.IntType)
            ),
            variables = listOf(
                TlaVariable("reserved", TlaType.SetType(TlaType.TupleType(listOf(TlaType.IntType, TlaType.IntType)))),
                TlaVariable("available", TlaType.FunctionType(TlaType.IntType, TlaType.IntType))
            ),
            operatorDefs = listOf(
                TlaOperatorDef("Init", body = TlaExpr.ConjList(listOf(
                    TlaExpr.OpApp(TlaOper.EQ, listOf(TlaExpr.NameRef("reserved"), TlaExpr.SetEnumExpr(emptyList()))),
                    TlaExpr.OpApp(TlaOper.EQ, listOf(
                        TlaExpr.NameRef("available"),
                        TlaExpr.FunExpr(
                            listOf(QuantBound("r", TlaExpr.NameRef("Resources"))),
                            TlaExpr.NameRef("Capacity")
                        )
                    ))
                ))),
                TlaOperatorDef(
                    "Reserve",
                    params = listOf(TlaFormalParam("u", 0), TlaFormalParam("r", 0)),
                    body = TlaExpr.ConjList(listOf(
                        TlaExpr.OpApp(TlaOper.GT, listOf(
                            TlaExpr.FunAppExpr(TlaExpr.NameRef("available"), TlaExpr.ParamRef("r")),
                            TlaExpr.IntLit(0)
                        )),
                        TlaExpr.OpApp(TlaOper.NOTIN, listOf(
                            TlaExpr.TupleExpr(listOf(TlaExpr.ParamRef("u"), TlaExpr.ParamRef("r"))),
                            TlaExpr.NameRef("reserved")
                        )),
                        TlaExpr.OpApp(TlaOper.EQ, listOf(
                            TlaExpr.PrimedRef("reserved"),
                            TlaExpr.OpApp(TlaOper.UNION, listOf(
                                TlaExpr.NameRef("reserved"),
                                TlaExpr.SetEnumExpr(listOf(TlaExpr.TupleExpr(listOf(TlaExpr.ParamRef("u"), TlaExpr.ParamRef("r")))))
                            ))
                        )),
                        TlaExpr.OpApp(TlaOper.EQ, listOf(
                            TlaExpr.PrimedRef("available"),
                            TlaExpr.ExceptExpr(
                                TlaExpr.NameRef("available"),
                                listOf(ExceptUpdate(
                                    listOf(TlaExpr.ParamRef("r")),
                                    TlaExpr.OpApp(TlaOper.MINUS, listOf(TlaExpr.AtExpr, TlaExpr.IntLit(1)))
                                ))
                            )
                        ))
                    ))
                ),
                TlaOperatorDef(
                    "Release",
                    params = listOf(TlaFormalParam("u", 0), TlaFormalParam("r", 0)),
                    body = TlaExpr.ConjList(listOf(
                        TlaExpr.OpApp(TlaOper.IN, listOf(
                            TlaExpr.TupleExpr(listOf(TlaExpr.ParamRef("u"), TlaExpr.ParamRef("r"))),
                            TlaExpr.NameRef("reserved")
                        )),
                        TlaExpr.OpApp(TlaOper.EQ, listOf(
                            TlaExpr.PrimedRef("reserved"),
                            TlaExpr.OpApp(TlaOper.SET_MINUS, listOf(
                                TlaExpr.NameRef("reserved"),
                                TlaExpr.SetEnumExpr(listOf(TlaExpr.TupleExpr(listOf(TlaExpr.ParamRef("u"), TlaExpr.ParamRef("r")))))
                            ))
                        )),
                        TlaExpr.OpApp(TlaOper.EQ, listOf(
                            TlaExpr.PrimedRef("available"),
                            TlaExpr.ExceptExpr(
                                TlaExpr.NameRef("available"),
                                listOf(ExceptUpdate(
                                    listOf(TlaExpr.ParamRef("r")),
                                    TlaExpr.OpApp(TlaOper.PLUS, listOf(TlaExpr.AtExpr, TlaExpr.IntLit(1)))
                                ))
                            )
                        ))
                    ))
                ),
                TlaOperatorDef(
                    "CheckAvailability",
                    params = listOf(TlaFormalParam("r", 0)),
                    body = TlaExpr.Unchanged(listOf("reserved", "available"))
                ),
                TlaOperatorDef("Next", body = TlaExpr.DisjList(listOf(
                    TlaExpr.QuantExpr(
                        QuantKind.EXISTS,
                        listOf(QuantBound("u", TlaExpr.NameRef("Users")), QuantBound("r", TlaExpr.NameRef("Resources"))),
                        TlaExpr.OperRef("Reserve")
                    ),
                    TlaExpr.QuantExpr(
                        QuantKind.EXISTS,
                        listOf(QuantBound("u", TlaExpr.NameRef("Users")), QuantBound("r", TlaExpr.NameRef("Resources"))),
                        TlaExpr.OperRef("Release")
                    ),
                    TlaExpr.QuantExpr(
                        QuantKind.EXISTS,
                        listOf(QuantBound("r", TlaExpr.NameRef("Resources"))),
                        TlaExpr.OperRef("CheckAvailability")
                    )
                ))),
                TlaOperatorDef("NoOverReservation", body =
                    TlaExpr.QuantExpr(
                        QuantKind.FORALL,
                        listOf(QuantBound("r", TlaExpr.NameRef("Resources"))),
                        TlaExpr.OpApp(TlaOper.GEQ, listOf(
                            TlaExpr.FunAppExpr(TlaExpr.NameRef("available"), TlaExpr.ParamRef("r")),
                            TlaExpr.IntLit(0)
                        ))
                    )
                ),
                TlaOperatorDef("CapacityBound", body =
                    TlaExpr.QuantExpr(
                        QuantKind.FORALL,
                        listOf(QuantBound("r", TlaExpr.NameRef("Resources"))),
                        TlaExpr.OpApp(TlaOper.LEQ, listOf(
                            TlaExpr.FunAppExpr(TlaExpr.NameRef("available"), TlaExpr.ParamRef("r")),
                            TlaExpr.NameRef("Capacity")
                        ))
                    )
                ),
                TlaOperatorDef("Spec", body = TlaExpr.OpApp(TlaOper.STUTTER, listOf(
                    TlaExpr.OpApp(TlaOper.AND, listOf(TlaExpr.OperRef("Init"), TlaExpr.OperRef("Next"))),
                    TlaExpr.TupleExpr(listOf(TlaExpr.NameRef("reserved"), TlaExpr.NameRef("available")))
                )))
            )
        )
    }

    private fun defaultConfig() = JavaConformanceGenerator.Config(
        packageName = "io.github.spec2test.generated",
        stepsPerTest = 50,
        numRandomTests = 10
    )
}
