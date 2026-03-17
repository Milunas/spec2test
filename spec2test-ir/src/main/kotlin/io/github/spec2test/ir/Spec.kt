package io.github.spec2test.ir

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * spec2test — Core Intermediate Representation
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * The typed intermediate representation (IR) that serves as the semantic bridge
 * between TLA+ specifications and generated Java/JUnit test code.
 *
 *     TLA+ source  ──P──▶  TlaModule  ──G──▶  Java/JUnit source
 *          (SANY parser)    (this IR)      (generator)
 *
 * ─── FORMAL FOUNDATION ────────────────────────────────────────────────────────
 *
 *   A TLA+ specification defines a Labeled Transition System (LTS):
 *
 *       LTS = (S, S₀, Σ, →, Φ)
 *
 *   where:
 *     S   = State space (Cartesian product of variable domains)
 *     S₀  = Initial state (defined by the Init predicate)
 *     Σ   = Action alphabet (named actions in Next)
 *     →   = Transition relation (guard ∧ primed-variable effects)
 *     Φ   = Safety invariants (predicates over every reachable state)
 *
 *   Unlike tla2lincheck which pre-classifies effects (Increment/SetAdd/etc.),
 *   spec2test preserves the FULL expression AST from SANY. Classification and
 *   code-generation strategies are the generator's responsibility — the IR is
 *   a faithful representation of TLA+ semantics.
 *
 * ─── KEY DESIGN DIFFERENCES FROM tla2lincheck ────────────────────────────────
 *
 *   1. Full expression AST ([TlaExpr]) instead of pre-classified [EffectExpr].
 *   2. Rich type system ([TlaType]) with parameterized types (Set<T>, Seq<T>).
 *   3. Operator definitions preserved as-is — no information loss.
 *   4. Actions are expression trees (conjunctions of guards + primed assignments).
 *   5. Invariants are expression trees — not just names + raw strings.
 *   6. No generator-specific concerns leak into the IR.
 */

// ─────────────────────────────────────────────────────────────────────────────
//  ROOT MODULE
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The root representation of a parsed TLA+ module.
 *
 *     ---- MODULE Counter ----
 *     EXTENDS Naturals, Sequences
 *     CONSTANTS MaxValue
 *     VARIABLES count, history
 *     ...
 *     ====
 */
data class TlaModule(
    val name: String,
    val extends: List<String> = emptyList(),
    val constants: List<TlaConstant>,
    val variables: List<TlaVariable>,
    val assumptions: List<TlaExpr> = emptyList(),
    val operatorDefs: List<TlaOperatorDef>,
    val source: TlaSource = TlaSource.Manual
) {
    /** Operator definitions classified by role. */
    val typeOK: TlaOperatorDef? get() = operatorDefs.find { it.name == "TypeOK" }
    val init: TlaOperatorDef? get() = operatorDefs.find { it.name == "Init" }
    val next: TlaOperatorDef? get() = operatorDefs.find { it.name == "Next" }
    val spec: TlaOperatorDef? get() = operatorDefs.find { it.name == "Spec" }

    /** Actions: operator defs referenced from Next that are not Init/Next/Spec/TypeOK/invariants. */
    val actions: List<TlaOperatorDef> get() {
        val nextDef = next ?: return emptyList()
        val actionNames = extractActionNames(nextDef.body)
        return operatorDefs.filter { it.name in actionNames }
    }

    /** Invariants: operator defs that reference variables, contain no primed refs, and are not system defs. */
    val invariants: List<TlaOperatorDef> get() {
        val systemNames = setOf("Init", "Next", "Spec", "TypeOK") +
            actions.map { it.name }.toSet()
        val varNames = variables.map { it.name }.toSet()
        return operatorDefs.filter { def ->
            def.name !in systemNames &&
                def.params.isEmpty() &&
                def.body.referencesAny(varNames) &&
                !def.body.containsPrimedRefs()
        }
    }

    /** Liveness properties: temporal formulas (WF, SF, <>, ~>). */
    val livenessProperties: List<TlaOperatorDef> get() =
        operatorDefs.filter { it.body.containsTemporalOps() }
}

private fun extractActionNames(expr: TlaExpr): Set<String> {
    return when (expr) {
        is TlaExpr.OpApp -> when (expr.op) {
            TlaOper.DISJ_LIST, TlaOper.OR -> expr.args.flatMap { extractActionNames(it) }.toSet()
            TlaOper.USER_DEFINED -> setOfNotNull(expr.opName)
            else -> if (expr.args.isEmpty()) setOfNotNull(expr.opName) else emptySet()
        }
        is TlaExpr.DisjList -> expr.disjuncts.flatMap { extractActionNames(it) }.toSet()
        is TlaExpr.QuantExpr -> if (expr.kind == QuantKind.EXISTS) extractActionNames(expr.body) else emptySet()
        is TlaExpr.OperRef -> setOf(expr.name)
        else -> emptySet()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  VARIABLES & CONSTANTS
// ─────────────────────────────────────────────────────────────────────────────

data class TlaVariable(
    val name: String,
    val type: TlaType = TlaType.Untyped,
    val description: String = ""
)

data class TlaConstant(
    val name: String,
    val type: TlaType = TlaType.Untyped,
    val description: String = ""
)

// ─────────────────────────────────────────────────────────────────────────────
//  OPERATOR DEFINITIONS
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A named operator definition: `Name(p1, p2) == body`
 *
 * Covers actions, invariants, helper operators, temporal formulas, etc.
 * The [body] is a full expression tree — no information loss.
 */
data class TlaOperatorDef(
    val name: String,
    val params: List<TlaFormalParam> = emptyList(),
    val body: TlaExpr,
    val isLocal: Boolean = false,
    val rawTla: String = ""
)

data class TlaFormalParam(
    val name: String,
    val arity: Int = 0
)

// ─────────────────────────────────────────────────────────────────────────────
//  TYPE SYSTEM
// ─────────────────────────────────────────────────────────────────────────────

/**
 * TLA+ type, inferred from TypeOK constraints and Init expressions.
 *
 * Unlike tla2lincheck's flat [VariableType] enum, this is a recursive sealed
 * hierarchy that supports parameterized types: Set<Int>, Seq<String>,
 * [Int -> Set<Int>], <<Int, String>>, [name: String, age: Int], etc.
 */
sealed class TlaType {
    data object IntType : TlaType()
    data object BoolType : TlaType()
    data object StringType : TlaType()
    data object NatType : TlaType()
    data class SetType(val elementType: TlaType) : TlaType()
    data class SeqType(val elementType: TlaType) : TlaType()
    data class FunctionType(val domainType: TlaType, val rangeType: TlaType) : TlaType()
    data class TupleType(val fieldTypes: List<TlaType>) : TlaType()
    data class RecordType(val fields: Map<String, TlaType>) : TlaType()
    data class ModelValueType(val setName: String) : TlaType()
    data class PowerSetType(val baseType: TlaType) : TlaType()
    data class IntervalType(val low: TlaExpr, val high: TlaExpr) : TlaType()
    data object Untyped : TlaType()
}

// ─────────────────────────────────────────────────────────────────────────────
//  EXPRESSION AST
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The core expression AST — faithful representation of TLA+ expressions.
 *
 * Every TLA+ construct that SANY can parse has a corresponding node type here.
 * This is the key improvement over tla2lincheck: we preserve the full expression
 * structure instead of pre-classifying into Increment/SetAdd/etc.
 */
sealed class TlaExpr {

    // ── Literals ─────────────────────────────────────────────────────────

    data class IntLit(val value: Int) : TlaExpr()
    data class BoolLit(val value: Boolean) : TlaExpr()
    data class StringLit(val value: String) : TlaExpr()

    // ── References ───────────────────────────────────────────────────────

    /** Reference to a variable or constant: `count`, `MaxValue` */
    data class NameRef(val name: String) : TlaExpr()
    /** Primed variable reference: `count'` */
    data class PrimedRef(val name: String) : TlaExpr()
    /** Reference to a named operator (used in Next disjunctions) */
    data class OperRef(val name: String) : TlaExpr()
    /** Formal parameter reference within an operator body */
    data class ParamRef(val name: String) : TlaExpr()

    // ── Operator Application ─────────────────────────────────────────────

    /**
     * Application of a built-in or user-defined operator.
     *
     * Examples:
     *   `x + 1`        → OpApp(PLUS, [NameRef("x"), IntLit(1)])
     *   `x \in S`      → OpApp(IN, [NameRef("x"), NameRef("S")])
     *   `Append(s, e)`  → OpApp(APPEND, [NameRef("s"), NameRef("e")])
     *   `count' = count + 1` → OpApp(EQ, [PrimedRef("count"), OpApp(PLUS, ...)])
     *
     * [opName] is set for user-defined operator applications where [op] is [TlaOper.USER_DEFINED].
     */
    data class OpApp(
        val op: TlaOper,
        val args: List<TlaExpr>,
        val opName: String? = null
    ) : TlaExpr()

    // ── Quantifiers ──────────────────────────────────────────────────────

    /**
     * Bounded quantifier: `\A x \in S : P(x)` or `\E x \in S : P(x)`
     */
    data class QuantExpr(
        val kind: QuantKind,
        val bounds: List<QuantBound>,
        val body: TlaExpr
    ) : TlaExpr()

    // ── Set Constructors ─────────────────────────────────────────────────

    /** Enumerated set: `{1, 2, 3}` */
    data class SetEnumExpr(val elements: List<TlaExpr>) : TlaExpr()
    /** Set filter: `{x \in S : P(x)}` */
    data class SetFilterExpr(
        val variable: String,
        val set: TlaExpr,
        val predicate: TlaExpr
    ) : TlaExpr()
    /** Set map: `{f(x) : x \in S}` */
    data class SetMapExpr(
        val mapExpr: TlaExpr,
        val bounds: List<QuantBound>
    ) : TlaExpr()

    // ── Function Constructors ────────────────────────────────────────────

    /** Function constructor: `[x \in S |-> expr]` */
    data class FunExpr(
        val bounds: List<QuantBound>,
        val body: TlaExpr
    ) : TlaExpr()
    /** Function application: `f[x]` */
    data class FunAppExpr(val function: TlaExpr, val arg: TlaExpr) : TlaExpr()
    /** EXCEPT expression: `[f EXCEPT ![a] = v, ![b] = w]` */
    data class ExceptExpr(
        val base: TlaExpr,
        val updates: List<ExceptUpdate>
    ) : TlaExpr()

    // ── Tuple & Record ───────────────────────────────────────────────────

    /** Tuple: `<<a, b, c>>` */
    data class TupleExpr(val elements: List<TlaExpr>) : TlaExpr()
    /** Record: `[name |-> "Alice", age |-> 30]` */
    data class RecordExpr(val fields: List<Pair<String, TlaExpr>>) : TlaExpr()
    /** Record field access: `r.name` */
    data class RecordFieldAccess(val record: TlaExpr, val field: String) : TlaExpr()

    // ── Control Flow ─────────────────────────────────────────────────────

    /** IF-THEN-ELSE */
    data class IfThenElse(
        val condition: TlaExpr,
        val thenExpr: TlaExpr,
        val elseExpr: TlaExpr
    ) : TlaExpr()

    /** CASE expression: `CASE p1 -> e1 [] p2 -> e2 [] OTHER -> e3` */
    data class CaseExpr(
        val arms: List<CaseArm>,
        val other: TlaExpr? = null
    ) : TlaExpr()

    // ── LET/IN ───────────────────────────────────────────────────────────

    /** LET defs IN body */
    data class LetIn(
        val defs: List<TlaOperatorDef>,
        val body: TlaExpr
    ) : TlaExpr()

    // ── CHOOSE ───────────────────────────────────────────────────────────

    /** `CHOOSE x \in S : P(x)` */
    data class ChooseExpr(
        val variable: String,
        val set: TlaExpr,
        val predicate: TlaExpr
    ) : TlaExpr()

    // ── Conjunction/Disjunction Lists ────────────────────────────────────

    /** `/\ a /\ b /\ c` — conjunction list (distinct from binary AND) */
    data class ConjList(val conjuncts: List<TlaExpr>) : TlaExpr()
    /** `\/ a \/ b \/ c` — disjunction list */
    data class DisjList(val disjuncts: List<TlaExpr>) : TlaExpr()

    // ── UNCHANGED ────────────────────────────────────────────────────────

    /** `UNCHANGED <<x, y, z>>` or `UNCHANGED x` */
    data class Unchanged(val variables: List<String>) : TlaExpr()

    // ── Temporal Operators ───────────────────────────────────────────────

    /** `[]P` (always), `<>P` (eventually), `P ~> Q` (leads-to) */
    data class TemporalExpr(val kind: TemporalKind, val args: List<TlaExpr>) : TlaExpr()

    /** `WF_vars(action)` or `SF_vars(action)` */
    data class FairnessExpr(
        val kind: FairnessKind,
        val vars: TlaExpr,
        val action: TlaExpr
    ) : TlaExpr()

    // ── @ (EXCEPT self-reference) ────────────────────────────────────────

    /** `@` — refers to the current value in an EXCEPT expression */
    data object AtExpr : TlaExpr()

    // ── Helper methods ───────────────────────────────────────────────────

    /** Check if this expression references any of the given names. */
    fun referencesAny(names: Set<String>): Boolean = when (this) {
        is NameRef -> name in names
        is PrimedRef -> name in names
        is OpApp -> args.any { it.referencesAny(names) }
        is QuantExpr -> body.referencesAny(names) || bounds.any { it.set.referencesAny(names) }
        is ConjList -> conjuncts.any { it.referencesAny(names) }
        is DisjList -> disjuncts.any { it.referencesAny(names) }
        is SetEnumExpr -> elements.any { it.referencesAny(names) }
        is SetFilterExpr -> set.referencesAny(names) || predicate.referencesAny(names)
        is SetMapExpr -> mapExpr.referencesAny(names) || bounds.any { it.set.referencesAny(names) }
        is FunExpr -> body.referencesAny(names) || bounds.any { it.set.referencesAny(names) }
        is FunAppExpr -> function.referencesAny(names) || arg.referencesAny(names)
        is ExceptExpr -> base.referencesAny(names) || updates.any { u ->
            u.keys.any { it.referencesAny(names) } || u.value.referencesAny(names)
        }
        is TupleExpr -> elements.any { it.referencesAny(names) }
        is RecordExpr -> fields.any { it.second.referencesAny(names) }
        is RecordFieldAccess -> record.referencesAny(names)
        is IfThenElse -> condition.referencesAny(names) || thenExpr.referencesAny(names) || elseExpr.referencesAny(names)
        is CaseExpr -> arms.any { it.guard.referencesAny(names) || it.body.referencesAny(names) } ||
            (other?.referencesAny(names) ?: false)
        is LetIn -> body.referencesAny(names) || defs.any { it.body.referencesAny(names) }
        is ChooseExpr -> set.referencesAny(names) || predicate.referencesAny(names)
        is Unchanged -> variables.any { it in names }
        is TemporalExpr -> args.any { it.referencesAny(names) }
        is FairnessExpr -> vars.referencesAny(names) || action.referencesAny(names)
        is IntLit, is BoolLit, is StringLit, is ParamRef, is OperRef, is AtExpr -> false
    }

    /** Check if this expression contains any primed variable references. */
    fun containsPrimedRefs(): Boolean = when (this) {
        is PrimedRef -> true
        is OpApp -> args.any { it.containsPrimedRefs() }
        is QuantExpr -> body.containsPrimedRefs()
        is ConjList -> conjuncts.any { it.containsPrimedRefs() }
        is DisjList -> disjuncts.any { it.containsPrimedRefs() }
        is SetEnumExpr -> elements.any { it.containsPrimedRefs() }
        is SetFilterExpr -> set.containsPrimedRefs() || predicate.containsPrimedRefs()
        is SetMapExpr -> mapExpr.containsPrimedRefs() || bounds.any { it.set.containsPrimedRefs() }
        is FunExpr -> body.containsPrimedRefs() || bounds.any { it.set.containsPrimedRefs() }
        is FunAppExpr -> function.containsPrimedRefs() || arg.containsPrimedRefs()
        is ExceptExpr -> base.containsPrimedRefs() || updates.any { u ->
            u.keys.any { it.containsPrimedRefs() } || u.value.containsPrimedRefs()
        }
        is TupleExpr -> elements.any { it.containsPrimedRefs() }
        is RecordExpr -> fields.any { it.second.containsPrimedRefs() }
        is RecordFieldAccess -> record.containsPrimedRefs()
        is IfThenElse -> condition.containsPrimedRefs() || thenExpr.containsPrimedRefs() || elseExpr.containsPrimedRefs()
        is CaseExpr -> arms.any { it.guard.containsPrimedRefs() || it.body.containsPrimedRefs() } ||
            (other?.containsPrimedRefs() ?: false)
        is LetIn -> body.containsPrimedRefs() || defs.any { it.body.containsPrimedRefs() }
        is ChooseExpr -> set.containsPrimedRefs() || predicate.containsPrimedRefs()
        is Unchanged -> false // UNCHANGED is primed, but it's not a PrimedRef
        is TemporalExpr -> args.any { it.containsPrimedRefs() }
        is FairnessExpr -> vars.containsPrimedRefs() || action.containsPrimedRefs()
        is IntLit, is BoolLit, is StringLit, is NameRef, is ParamRef, is OperRef, is AtExpr -> false
    }

    /** Check if this expression contains temporal operators. */
    fun containsTemporalOps(): Boolean = when (this) {
        is TemporalExpr -> true
        is FairnessExpr -> true
        is OpApp -> op in setOf(TlaOper.BOX, TlaOper.DIAMOND, TlaOper.LEADS_TO, TlaOper.WF, TlaOper.SF) ||
            args.any { it.containsTemporalOps() }
        is ConjList -> conjuncts.any { it.containsTemporalOps() }
        is DisjList -> disjuncts.any { it.containsTemporalOps() }
        is QuantExpr -> body.containsTemporalOps()
        is LetIn -> body.containsTemporalOps() || defs.any { it.body.containsTemporalOps() }
        else -> false
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  OPERATORS
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Built-in and standard-module TLA+ operators.
 *
 * Each operator maps to a SANY internal operator code or standard module function.
 */
enum class TlaOper {
    // Arithmetic (Naturals/Integers)
    PLUS, MINUS, TIMES, DIV, MOD, EXP,
    UMINUS,
    DOT_DOT,  // a..b range

    // Comparison
    EQ, NEQ, LT, GT, LEQ, GEQ,

    // Logic
    AND, OR, NOT, IMPLIES, EQUIV,
    CONJ_LIST, DISJ_LIST,

    // Set operators
    IN, NOTIN, SUBSET_EQ, UNION, INTER, SET_MINUS, SUBSET, POWERSET,
    TIMES_SET,  // S \X T (Cartesian product)

    // Sequence operators (Sequences module)
    APPEND, HEAD, TAIL, LEN, SUBSEQ, SEQ_CONCAT, SELECT_SEQ,

    // FiniteSets module
    CARDINALITY, IS_FINITE_SET,

    // Function operators
    DOMAIN, FUNCTION_APP, EXCEPT,

    // Tuple/Record
    TUPLE,

    // Temporal
    BOX, DIAMOND, LEADS_TO, WF, SF,
    ENABLED, UNCHANGED_OP,

    // Action operators
    PRIME,
    STUTTER,  // [Next]_vars

    // User-defined operator application
    USER_DEFINED,

    // TLC module
    PRINT, ASSERT, JAVA_TIME,

    // Misc
    IF_THEN_ELSE,
    CASE,
    CHOOSE,
    STRING_OP,
    BOOLEAN_SET,  // BOOLEAN (the set {TRUE, FALSE})
}

enum class QuantKind { FORALL, EXISTS }

data class QuantBound(
    val name: String,
    val set: TlaExpr
)

data class ExceptUpdate(
    val keys: List<TlaExpr>,
    val value: TlaExpr
)

data class CaseArm(
    val guard: TlaExpr,
    val body: TlaExpr
)

enum class TemporalKind { ALWAYS, EVENTUALLY, LEADS_TO }
enum class FairnessKind { WEAK, STRONG }

// ─────────────────────────────────────────────────────────────────────────────
//  SOURCE METADATA
// ─────────────────────────────────────────────────────────────────────────────

sealed class TlaSource {
    data class File(val filePath: String, val moduleName: String = "") : TlaSource()
    data object Manual : TlaSource()
}

// ─────────────────────────────────────────────────────────────────────────────
//  PARSE RESULT
// ─────────────────────────────────────────────────────────────────────────────

data class ParseResult(
    val module: TlaModule?,
    val warnings: List<ParseWarning> = emptyList(),
    val errors: List<ParseError> = emptyList()
) {
    val isSuccessful: Boolean get() = module != null && errors.isEmpty()
}

data class ParseWarning(val message: String, val location: String = "")
data class ParseError(val message: String, val location: String = "")
