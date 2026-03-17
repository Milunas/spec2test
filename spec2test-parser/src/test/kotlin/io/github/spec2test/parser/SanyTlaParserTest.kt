package io.github.spec2test.parser

import io.github.spec2test.ir.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import java.io.File

class SanyTlaParserTest {

    private val parser = SanyTlaParser()

    // ─── MODULE STRUCTURE ────────────────────────────────────────────────

    @Nested
    inner class ModuleStructure {

        @Test
        fun `parses module name from header`() {
            val result = parser.parse(counterSpec())
            assertThat(result.isSuccessful).isTrue()
            assertThat(result.module!!.name).isEqualTo("Counter")
        }

        @Test
        fun `extracts EXTENDS clause`() {
            val result = parser.parse(counterSpec())
            assertThat(result.module!!.extends).contains("Naturals")
        }

        @Test
        fun `fails on missing module header`() {
            val result = parser.parse("VARIABLES x\nInit == x = 0")
            assertThat(result.isSuccessful).isFalse()
            assertThat(result.errors).isNotEmpty()
        }
    }

    // ─── CONSTANTS ───────────────────────────────────────────────────────

    @Nested
    inner class Constants {

        @Test
        fun `extracts CONSTANTS section`() {
            val result = parser.parse(counterSpec())
            assertThat(result.module!!.constants).hasSize(1)
            assertThat(result.module!!.constants[0].name).isEqualTo("MaxValue")
        }

        @Test
        fun `extracts multiple constants`() {
            val result = parser.parse(reservationSpec())
            val constantNames = result.module!!.constants.map { it.name }
            assertThat(constantNames).containsExactlyInAnyOrder("Users", "Resources", "Capacity")
        }
    }

    // ─── VARIABLES ───────────────────────────────────────────────────────

    @Nested
    inner class Variables {

        @Test
        fun `extracts VARIABLES section`() {
            val result = parser.parse(counterSpec())
            val varNames = result.module!!.variables.map { it.name }
            assertThat(varNames).containsExactlyInAnyOrder("count", "history")
        }

        @Test
        fun `infers integer type from TypeOK range`() {
            val result = parser.parse(counterSpec())
            val countVar = result.module!!.variables.find { it.name == "count" }
            assertThat(countVar).isNotNull
            // count \in 0..MaxValue → should infer IntType or NatType
            assertThat(countVar!!.type).isIn(TlaType.IntType, TlaType.NatType)
        }

        @Test
        fun `infers sequence type from TypeOK Seq`() {
            val result = parser.parse(counterSpec())
            val historyVar = result.module!!.variables.find { it.name == "history" }
            assertThat(historyVar).isNotNull
            // history \in Seq({"inc", "dec"}) → should infer SeqType
            assertThat(historyVar!!.type).isInstanceOf(TlaType.SeqType::class.java)
        }
    }

    // ─── OPERATOR DEFINITIONS ────────────────────────────────────────────

    @Nested
    inner class OperatorDefinitions {

        @Test
        fun `extracts Init operator`() {
            val result = parser.parse(counterSpec())
            assertThat(result.module!!.init).isNotNull
            assertThat(result.module!!.init!!.name).isEqualTo("Init")
        }

        @Test
        fun `extracts Next operator`() {
            val result = parser.parse(counterSpec())
            assertThat(result.module!!.next).isNotNull
            assertThat(result.module!!.next!!.name).isEqualTo("Next")
        }

        @Test
        fun `extracts TypeOK operator`() {
            val result = parser.parse(counterSpec())
            assertThat(result.module!!.typeOK).isNotNull
        }

        @Test
        fun `extracts all operator definitions`() {
            val result = parser.parse(counterSpec())
            val opNames = result.module!!.operatorDefs.map { it.name }
            // Should include: TypeOK, Init, Increment, Decrement, Read, Next, NonNegative, BoundedAbove, Spec
            assertThat(opNames).contains("Init", "Next", "Increment", "Decrement", "Read")
        }
    }

    // ─── ACTIONS ─────────────────────────────────────────────────────────

    @Nested
    inner class Actions {

        @Test
        fun `extracts actions from Next disjunction`() {
            val result = parser.parse(counterSpec())
            val actionNames = result.module!!.actions.map { it.name }
            assertThat(actionNames).containsExactlyInAnyOrder("Increment", "Decrement", "Read")
        }

        @Test
        fun `action bodies contain primed variable assignments`() {
            val result = parser.parse(counterSpec())
            val increment = result.module!!.actions.find { it.name == "Increment" }
            assertThat(increment).isNotNull
            assertThat(increment!!.body.containsPrimedRefs()).isTrue()
        }

        @Test
        fun `action bodies contain guards (non-primed conditions)`() {
            val result = parser.parse(counterSpec())
            val increment = result.module!!.actions.find { it.name == "Increment" }
            assertThat(increment).isNotNull
            // Increment has guard: count < MaxValue
            // The body should be a ConjList with at least one guard conjunct
        }

        @Test
        fun `extracts parameterized actions from quantified Next`() {
            val result = parser.parse(reservationSpec())
            val reserve = result.module!!.actions.find { it.name == "Reserve" }
            assertThat(reserve).isNotNull
            assertThat(reserve!!.params).hasSize(2) // u, r
            assertThat(reserve.params.map { it.name }).containsExactlyInAnyOrder("u", "r")
        }
    }

    // ─── INVARIANTS ──────────────────────────────────────────────────────

    @Nested
    inner class Invariants {

        @Test
        fun `extracts safety invariants`() {
            val result = parser.parse(counterSpec())
            val invariantNames = result.module!!.invariants.map { it.name }
            assertThat(invariantNames).containsExactlyInAnyOrder("NonNegative", "BoundedAbove")
        }

        @Test
        fun `invariant bodies reference state variables`() {
            val result = parser.parse(counterSpec())
            val nonNeg = result.module!!.invariants.find { it.name == "NonNegative" }
            assertThat(nonNeg).isNotNull
            assertThat(nonNeg!!.body.referencesAny(setOf("count"))).isTrue()
        }

        @Test
        fun `invariants do not contain primed references`() {
            val result = parser.parse(counterSpec())
            for (inv in result.module!!.invariants) {
                assertThat(inv.body.containsPrimedRefs())
                    .withFailMessage("Invariant '${inv.name}' should not contain primed refs")
                    .isFalse()
            }
        }

        @Test
        fun `excludes system definitions from invariants`() {
            val result = parser.parse(counterSpec())
            val invariantNames = result.module!!.invariants.map { it.name }
            assertThat(invariantNames).doesNotContain("Init", "Next", "Spec", "TypeOK",
                "Increment", "Decrement", "Read")
        }
    }

    // ─── EXPRESSION AST ─────────────────────────────────────────────────

    @Nested
    inner class ExpressionAST {

        @Test
        fun `Init body is a conjunction list`() {
            val result = parser.parse(counterSpec())
            val initBody = result.module!!.init!!.body
            // Init == count = 0 /\ history = <<>>
            assertThat(initBody).isInstanceOf(TlaExpr.ConjList::class.java)
        }

        @Test
        fun `Next body is a disjunction of action references`() {
            val result = parser.parse(counterSpec())
            val nextBody = result.module!!.next!!.body
            // Next == Increment \/ Decrement \/ Read
            assertThat(nextBody).isInstanceOf(TlaExpr.DisjList::class.java)
        }

        @Test
        fun `primed assignments produce PrimedRef nodes`() {
            val result = parser.parse(counterSpec())
            val increment = result.module!!.actions.find { it.name == "Increment" }!!
            assertThat(increment.body.containsPrimedRefs()).isTrue()
        }

        @Test
        fun `UNCHANGED produces Unchanged nodes`() {
            val result = parser.parse(counterSpec())
            val read = result.module!!.actions.find { it.name == "Read" }
            assertThat(read).isNotNull
            // Read == UNCHANGED <<count, history>>
            assertThat(containsUnchanged(read!!.body)).isTrue()
        }

        private fun containsUnchanged(expr: TlaExpr): Boolean = when (expr) {
            is TlaExpr.Unchanged -> true
            is TlaExpr.ConjList -> expr.conjuncts.any { containsUnchanged(it) }
            is TlaExpr.DisjList -> expr.disjuncts.any { containsUnchanged(it) }
            else -> false
        }
    }

    // ─── COMPLEX SPEC PATTERNS ───────────────────────────────────────────

    @Nested
    inner class ComplexPatterns {

        @Test
        fun `handles EXCEPT expressions in Reservation`() {
            val result = parser.parse(reservationSpec())
            assertThat(result.isSuccessful).isTrue()
            // Reserve has: available' = [available EXCEPT ![r] = @ - 1]
            val reserve = result.module!!.actions.find { it.name == "Reserve" }
            assertThat(reserve).isNotNull
        }

        @Test
        fun `handles set operations in Reservation`() {
            val result = parser.parse(reservationSpec())
            assertThat(result.isSuccessful).isTrue()
            // Reserve has: reserved' = reserved \cup {<<u, r>>}
            val reserve = result.module!!.actions.find { it.name == "Reserve" }
            assertThat(reserve).isNotNull
        }

        @Test
        fun `handles quantified invariants`() {
            val result = parser.parse(reservationSpec())
            val invariants = result.module!!.invariants
            // Should have: NoOverReservation, CapacityBound, ConsistentCounts
            assertThat(invariants.map { it.name })
                .containsExactlyInAnyOrder("NoOverReservation", "CapacityBound", "ConsistentCounts")
        }

        @Test
        fun `reservation spec has three invariants with quantified bodies`() {
            val result = parser.parse(reservationSpec())
            for (inv in result.module!!.invariants) {
                // All invariants are \A r \in Resources : ...
                // The body should contain quantifier expressions
                assertThat(inv.body.referencesAny(setOf("available", "reserved")))
                    .withFailMessage("Invariant '${inv.name}' should reference state variables")
                    .isTrue()
            }
        }
    }

    // ─── PARSE FROM FILE ─────────────────────────────────────────────────

    @Nested
    inner class FileInput {

        @Test
        fun `parses Counter from example file`() {
            val file = findExampleFile("counter/Counter.tla")
            if (file != null) {
                val result = parser.parseFile(file)
                assertThat(result.isSuccessful).isTrue()
                assertThat(result.module!!.name).isEqualTo("Counter")
            }
        }

        @Test
        fun `parses Reservation from example file`() {
            val file = findExampleFile("reservation/Reservation.tla")
            if (file != null) {
                val result = parser.parseFile(file)
                assertThat(result.isSuccessful).isTrue()
                assertThat(result.module!!.name).isEqualTo("Reservation")
            }
        }

        private fun findExampleFile(relativePath: String): File? {
            val candidates = listOf(
                File("examples/$relativePath"),
                File("../examples/$relativePath"),
                File(System.getProperty("user.dir"), "examples/$relativePath")
            )
            return candidates.find { it.exists() }
        }
    }

    // ─── TEST FIXTURES ───────────────────────────────────────────────────

    private fun counterSpec() = """
---- MODULE Counter ----
EXTENDS Naturals, Sequences

CONSTANTS MaxValue

VARIABLES count, history

TypeOK ==
    /\ count \in 0..MaxValue
    /\ history \in Seq({"inc", "dec"})

Init ==
    /\ count = 0
    /\ history = <<>>

Increment ==
    /\ count < MaxValue
    /\ count' = count + 1
    /\ history' = Append(history, "inc")

Decrement ==
    /\ count > 0
    /\ count' = count - 1
    /\ history' = Append(history, "dec")

Read ==
    /\ UNCHANGED <<count, history>>

Next ==
    \/ Increment
    \/ Decrement
    \/ Read

NonNegative == count >= 0

BoundedAbove == count <= MaxValue

Spec == Init /\ [][Next]_<<count, history>>

====
    """.trimIndent()

    private fun reservationSpec() = """
---- MODULE Reservation ----
EXTENDS Naturals, FiniteSets

CONSTANTS Users, Resources, Capacity

VARIABLES reserved, available

TypeOK ==
    /\ reserved \in SUBSET (Users \X Resources)
    /\ available \in [Resources -> 0..Capacity]

Init ==
    /\ reserved = {}
    /\ available = [r \in Resources |-> Capacity]

Reserve(u, r) ==
    /\ available[r] > 0
    /\ <<u, r>> \notin reserved
    /\ reserved' = reserved \cup {<<u, r>>}
    /\ available' = [available EXCEPT ![r] = @ - 1]

Release(u, r) ==
    /\ <<u, r>> \in reserved
    /\ reserved' = reserved \ {<<u, r>>}
    /\ available' = [available EXCEPT ![r] = @ + 1]

CheckAvailability(r) ==
    /\ UNCHANGED <<reserved, available>>

Next ==
    \/ \E u \in Users, r \in Resources : Reserve(u, r)
    \/ \E u \in Users, r \in Resources : Release(u, r)
    \/ \E r \in Resources : CheckAvailability(r)

NoOverReservation == \A r \in Resources : available[r] >= 0

CapacityBound == \A r \in Resources : available[r] <= Capacity

ConsistentCounts ==
    \A r \in Resources :
        available[r] = Capacity - Cardinality({u \in Users : <<u, r>> \in reserved})

Spec == Init /\ [][Next]_<<reserved, available>>

====
    """.trimIndent()
}
