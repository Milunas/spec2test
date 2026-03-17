package io.github.spec2test.generator

import io.github.spec2test.ir.*

/**
 * Translates [TlaExpr] AST nodes into Java source code strings.
 *
 * This is a type-directed translator: it uses the inferred [TlaType] of variables
 * and expressions to produce correct Java code. For example, set union produces
 * `new HashSet<>(s) {{ addAll(other); }}` rather than `s + other`.
 *
 * Key design: this translator handles the TLA+ → Java impedance mismatch:
 *   - TLA+ is value-based (no mutation); Java uses mutable state
 *   - TLA+ sets/sequences/functions → Java Set/List/Map
 *   - TLA+ primed variables → Java assignment statements
 *   - TLA+ guards (preconditions) → Java boolean expressions
 *   - TLA+ EXCEPT → Java Map.put / copy-and-modify
 */
class JavaExprTranslator(
    private val variables: Map<String, TlaType>,
    private val constants: Map<String, TlaType>
) {

    // ─── EXPRESSION TRANSLATION ──────────────────────────────────────────

    /**
     * Translate a [TlaExpr] to a Java expression string.
     */
    fun translateExpr(expr: TlaExpr): String = when (expr) {
        is TlaExpr.IntLit -> expr.value.toString()
        is TlaExpr.BoolLit -> expr.value.toString()
        is TlaExpr.StringLit -> "\"${expr.value}\""
        is TlaExpr.NameRef -> translateName(expr.name)
        is TlaExpr.PrimedRef -> translateName(expr.name)
        is TlaExpr.ParamRef -> expr.name
        is TlaExpr.OperRef -> expr.name
        is TlaExpr.AtExpr -> "__current__"

        is TlaExpr.OpApp -> translateOpApp(expr)

        is TlaExpr.QuantExpr -> translateQuantifier(expr)
        is TlaExpr.SetEnumExpr -> translateSetEnum(expr)
        is TlaExpr.SetFilterExpr -> translateSetFilter(expr)
        is TlaExpr.SetMapExpr -> translateSetMap(expr)

        is TlaExpr.FunExpr -> translateFunConstructor(expr)
        is TlaExpr.FunAppExpr -> "${translateExpr(expr.function)}.get(${translateExpr(expr.arg)})"
        is TlaExpr.ExceptExpr -> translateExcept(expr)

        is TlaExpr.TupleExpr -> translateTuple(expr)
        is TlaExpr.RecordExpr -> translateRecord(expr)
        is TlaExpr.RecordFieldAccess -> "${translateExpr(expr.record)}.${expr.field}"

        is TlaExpr.IfThenElse -> "(${translateExpr(expr.condition)} ? ${translateExpr(expr.thenExpr)} : ${translateExpr(expr.elseExpr)})"
        is TlaExpr.CaseExpr -> translateCase(expr)
        is TlaExpr.LetIn -> translateLetIn(expr)
        is TlaExpr.ChooseExpr -> translateChoose(expr)

        is TlaExpr.ConjList -> expr.conjuncts.joinToString(" && ") { "(${translateExpr(it)})" }
        is TlaExpr.DisjList -> expr.disjuncts.joinToString(" || ") { "(${translateExpr(it)})" }

        is TlaExpr.Unchanged -> "/* UNCHANGED ${expr.variables.joinToString(", ")} */ true"
        is TlaExpr.TemporalExpr -> "/* temporal: ${expr.kind} */ true"
        is TlaExpr.FairnessExpr -> "/* ${expr.kind} fairness */ true"
    }

    private fun translateName(name: String): String = when (name) {
        "TRUE" -> "true"
        "FALSE" -> "false"
        "Nat" -> "/* Nat */"
        "Int" -> "/* Int */"
        else -> name
    }

    // ─── OPERATOR APPLICATION ────────────────────────────────────────────

    private fun translateOpApp(expr: TlaExpr.OpApp): String {
        val args = expr.args
        return when (expr.op) {
            // Arithmetic
            TlaOper.PLUS -> binaryInfix(args, "+")
            TlaOper.MINUS -> binaryInfix(args, "-")
            TlaOper.TIMES -> binaryInfix(args, "*")
            TlaOper.DIV -> binaryInfix(args, "/")
            TlaOper.MOD -> binaryInfix(args, "%")
            TlaOper.EXP -> "Math.pow(${arg(args, 0)}, ${arg(args, 1)})"
            TlaOper.UMINUS -> "-${arg(args, 0)}"
            TlaOper.DOT_DOT -> "IntStream.rangeClosed(${arg(args, 0)}, ${arg(args, 1)}).boxed().collect(Collectors.toSet())"

            // Comparison
            TlaOper.EQ -> "${arg(args, 0)} == ${arg(args, 1)}".let { eq ->
                // Use .equals() for objects
                val lhs = args.getOrNull(0)
                if (lhs is TlaExpr.NameRef && isObjectType(lhs.name)) {
                    "${arg(args, 0)}.equals(${arg(args, 1)})"
                } else eq
            }
            TlaOper.NEQ -> "!${arg(args, 0)}.equals(${arg(args, 1)})"
            TlaOper.LT -> "${arg(args, 0)} < ${arg(args, 1)}"
            TlaOper.GT -> "${arg(args, 0)} > ${arg(args, 1)}"
            TlaOper.LEQ -> "${arg(args, 0)} <= ${arg(args, 1)}"
            TlaOper.GEQ -> "${arg(args, 0)} >= ${arg(args, 1)}"

            // Logic
            TlaOper.AND -> binaryInfix(args, "&&")
            TlaOper.OR -> binaryInfix(args, "||")
            TlaOper.NOT -> "!(${arg(args, 0)})"
            TlaOper.IMPLIES -> "(!(${arg(args, 0)}) || (${arg(args, 1)}))"
            TlaOper.EQUIV -> "(${arg(args, 0)} == ${arg(args, 1)})"
            TlaOper.CONJ_LIST -> args.joinToString(" && ") { "(${translateExpr(it)})" }
            TlaOper.DISJ_LIST -> args.joinToString(" || ") { "(${translateExpr(it)})" }

            // Set operations
            TlaOper.IN -> "${arg(args, 1)}.contains(${arg(args, 0)})"
            TlaOper.NOTIN -> "!${arg(args, 1)}.contains(${arg(args, 0)})"
            TlaOper.SUBSET_EQ -> "${arg(args, 1)}.containsAll(${arg(args, 0)})"
            TlaOper.UNION -> "Spec2TestRuntime.union(${arg(args, 0)}, ${arg(args, 1)})"
            TlaOper.INTER -> "Spec2TestRuntime.intersect(${arg(args, 0)}, ${arg(args, 1)})"
            TlaOper.SET_MINUS -> "Spec2TestRuntime.setMinus(${arg(args, 0)}, ${arg(args, 1)})"
            TlaOper.POWERSET -> "Spec2TestRuntime.powerSet(${arg(args, 0)})"
            TlaOper.SUBSET -> "Spec2TestRuntime.unionAll(${arg(args, 0)})"
            TlaOper.TIMES_SET -> "Spec2TestRuntime.cartesianProduct(${args.joinToString(", ") { translateExpr(it) }})"

            // Sequence operations
            TlaOper.APPEND -> "Spec2TestRuntime.append(${arg(args, 0)}, ${arg(args, 1)})"
            TlaOper.HEAD -> "${arg(args, 0)}.getFirst()"
            TlaOper.TAIL -> "Spec2TestRuntime.tail(${arg(args, 0)})"
            TlaOper.LEN -> "${arg(args, 0)}.size()"
            TlaOper.SUBSEQ -> "Spec2TestRuntime.subSeq(${arg(args, 0)}, ${arg(args, 1)}, ${arg(args, 2)})"
            TlaOper.SEQ_CONCAT -> "Spec2TestRuntime.seqConcat(${arg(args, 0)}, ${arg(args, 1)})"
            TlaOper.SELECT_SEQ -> "Spec2TestRuntime.selectSeq(${arg(args, 0)}, ${arg(args, 1)})"

            // FiniteSets
            TlaOper.CARDINALITY -> "${arg(args, 0)}.size()"
            TlaOper.IS_FINITE_SET -> "true /* IsFiniteSet */"

            // Function operators
            TlaOper.DOMAIN -> "${arg(args, 0)}.keySet()"
            TlaOper.FUNCTION_APP -> "${arg(args, 0)}.get(${arg(args, 1)})"
            TlaOper.EXCEPT -> arg(args, 0) // handled in ExceptExpr

            // Tuple
            TlaOper.TUPLE -> "List.of(${args.joinToString(", ") { translateExpr(it) }})"

            // Misc
            TlaOper.PRIME -> "${arg(args, 0)} /* primed */"
            TlaOper.STUTTER -> arg(args, 0)
            TlaOper.IF_THEN_ELSE -> if (args.size >= 3) "(${arg(args, 0)} ? ${arg(args, 1)} : ${arg(args, 2)})" else "true"
            TlaOper.CASE -> "/* CASE */ null"
            TlaOper.CHOOSE -> "/* CHOOSE */ null"
            TlaOper.BOOLEAN_SET -> "Set.of(true, false)"

            // Temporal (documentation only — cannot test)
            TlaOper.BOX, TlaOper.DIAMOND, TlaOper.LEADS_TO, TlaOper.WF, TlaOper.SF,
            TlaOper.ENABLED, TlaOper.UNCHANGED_OP -> "/* temporal */ true"

            // TLC module
            TlaOper.PRINT -> "System.out.println(${arg(args, 0)})"
            TlaOper.ASSERT -> "assert ${arg(args, 0)}"
            TlaOper.JAVA_TIME -> "System.currentTimeMillis()"
            TlaOper.STRING_OP -> "\"${arg(args, 0)}\""

            // User-defined
            TlaOper.USER_DEFINED -> {
                val name = expr.opName ?: "unknown"
                if (args.isEmpty()) name
                else "$name(${args.joinToString(", ") { translateExpr(it) }})"
            }
        }
    }

    // ─── QUANTIFIER TRANSLATION ──────────────────────────────────────────

    private fun translateQuantifier(expr: TlaExpr.QuantExpr): String {
        if (expr.bounds.isEmpty()) return translateExpr(expr.body)

        val bound = expr.bounds[0]
        val stream = translateExpr(bound.set)
        val body = if (expr.bounds.size > 1) {
            translateQuantifier(TlaExpr.QuantExpr(expr.kind, expr.bounds.drop(1), expr.body))
        } else {
            translateExpr(expr.body)
        }

        return when (expr.kind) {
            QuantKind.FORALL -> "$stream.stream().allMatch(${bound.name} -> $body)"
            QuantKind.EXISTS -> "$stream.stream().anyMatch(${bound.name} -> $body)"
        }
    }

    // ─── SET CONSTRUCTORS ────────────────────────────────────────────────

    private fun translateSetEnum(expr: TlaExpr.SetEnumExpr): String {
        if (expr.elements.isEmpty()) return "new HashSet<>()"
        val elems = expr.elements.joinToString(", ") { translateExpr(it) }
        return "Set.of($elems)"
    }

    private fun translateSetFilter(expr: TlaExpr.SetFilterExpr): String {
        val set = translateExpr(expr.set)
        val pred = translateExpr(expr.predicate)
        return "$set.stream().filter(${expr.variable} -> $pred).collect(Collectors.toSet())"
    }

    private fun translateSetMap(expr: TlaExpr.SetMapExpr): String {
        if (expr.bounds.isEmpty()) return "new HashSet<>()"
        val bound = expr.bounds[0]
        val set = translateExpr(bound.set)
        val mapExpr = translateExpr(expr.mapExpr)
        return "$set.stream().map(${bound.name} -> $mapExpr).collect(Collectors.toSet())"
    }

    // ─── FUNCTION CONSTRUCTORS ───────────────────────────────────────────

    private fun translateFunConstructor(expr: TlaExpr.FunExpr): String {
        if (expr.bounds.isEmpty()) return "new HashMap<>()"
        val bound = expr.bounds[0]
        val set = translateExpr(bound.set)
        val body = translateExpr(expr.body)
        return "$set.stream().collect(Collectors.toMap(${bound.name} -> ${bound.name}, ${bound.name} -> $body))"
    }

    private fun translateExcept(expr: TlaExpr.ExceptExpr): String {
        val base = translateExpr(expr.base)
        if (expr.updates.isEmpty()) return base

        val lines = mutableListOf("Spec2TestRuntime.mapWith($base")
        for (update in expr.updates) {
            val key = if (update.keys.isNotEmpty()) translateExpr(update.keys[0]) else "null"
            val value = translateExpr(update.value)
            lines.add(", $key, $value")
        }
        lines.add(")")
        return lines.joinToString("")
    }

    // ─── TUPLE & RECORD ─────────────────────────────────────────────────

    private fun translateTuple(expr: TlaExpr.TupleExpr): String {
        if (expr.elements.isEmpty()) return "List.of()"
        val elems = expr.elements.joinToString(", ") { translateExpr(it) }
        return "List.of($elems)"
    }

    private fun translateRecord(expr: TlaExpr.RecordExpr): String {
        if (expr.fields.isEmpty()) return "Map.of()"
        val entries = expr.fields.joinToString(", ") { (k, v) ->
            "\"$k\", ${translateExpr(v)}"
        }
        return "Map.of($entries)"
    }

    // ─── CONTROL FLOW ────────────────────────────────────────────────────

    private fun translateCase(expr: TlaExpr.CaseExpr): String {
        val sb = StringBuilder()
        for ((i, arm) in expr.arms.withIndex()) {
            if (i == 0) sb.append("(${translateExpr(arm.guard)} ? ${translateExpr(arm.body)}")
            else sb.append(" : ${translateExpr(arm.guard)} ? ${translateExpr(arm.body)}")
        }
        val other = expr.other?.let { translateExpr(it) } ?: "null"
        sb.append(" : $other)")
        return sb.toString()
    }

    private fun translateLetIn(expr: TlaExpr.LetIn): String {
        // For simple single-def LET/IN, generate inline. For complex ones, delegate.
        if (expr.defs.size == 1 && expr.defs[0].params.isEmpty()) {
            val def = expr.defs[0]
            return "/* LET ${def.name} == */ ((java.util.function.Supplier<Object>)(() -> { " +
                "var ${def.name} = ${translateExpr(def.body)}; " +
                "return ${translateExpr(expr.body)}; })).get()"
        }
        return translateExpr(expr.body) + " /* LET/IN */"
    }

    private fun translateChoose(expr: TlaExpr.ChooseExpr): String {
        val set = translateExpr(expr.set)
        val pred = translateExpr(expr.predicate)
        return "$set.stream().filter(${expr.variable} -> $pred).findFirst().orElseThrow()"
    }

    // ─── UTILITY ─────────────────────────────────────────────────────────

    private fun binaryInfix(args: List<TlaExpr>, op: String): String {
        if (args.size < 2) return arg(args, 0)
        return "(${arg(args, 0)} $op ${arg(args, 1)})"
    }

    private fun arg(args: List<TlaExpr>, idx: Int): String =
        if (idx < args.size) translateExpr(args[idx]) else "null"

    private fun isObjectType(name: String): Boolean {
        val type = variables[name] ?: constants[name]
        return type != null && type !is TlaType.IntType && type !is TlaType.BoolType && type !is TlaType.NatType
    }

    // ─── TYPE-TO-JAVA MAPPING ────────────────────────────────────────────

    companion object {
        fun typeToJava(type: TlaType): String = when (type) {
            is TlaType.IntType, is TlaType.NatType -> "int"
            is TlaType.BoolType -> "boolean"
            is TlaType.StringType -> "String"
            is TlaType.SetType -> "Set<${typeToJavaBoxed(type.elementType)}>"
            is TlaType.SeqType -> "List<${typeToJavaBoxed(type.elementType)}>"
            is TlaType.FunctionType -> "Map<${typeToJavaBoxed(type.domainType)}, ${typeToJavaBoxed(type.rangeType)}>"
            is TlaType.TupleType -> "List<Object>"
            is TlaType.RecordType -> "Map<String, Object>"
            is TlaType.ModelValueType -> "int /* ${type.setName} */"
            is TlaType.PowerSetType -> "Set<Set<${typeToJavaBoxed(type.baseType)}>>"
            is TlaType.IntervalType -> "int"
            is TlaType.Untyped -> "Object"
        }

        fun typeToJavaBoxed(type: TlaType): String = when (type) {
            is TlaType.IntType, is TlaType.NatType -> "Integer"
            is TlaType.BoolType -> "Boolean"
            else -> typeToJava(type)
        }

        fun typeToMutableJava(type: TlaType): String = when (type) {
            is TlaType.SetType -> "HashSet<${typeToJavaBoxed(type.elementType)}>"
            is TlaType.SeqType -> "ArrayList<${typeToJavaBoxed(type.elementType)}>"
            is TlaType.FunctionType -> "HashMap<${typeToJavaBoxed(type.domainType)}, ${typeToJavaBoxed(type.rangeType)}>"
            is TlaType.TupleType -> "ArrayList<Object>"
            is TlaType.RecordType -> "HashMap<String, Object>"
            else -> typeToJava(type)
        }

        fun defaultInitializer(type: TlaType): String = when (type) {
            is TlaType.IntType, is TlaType.NatType -> "0"
            is TlaType.BoolType -> "false"
            is TlaType.StringType -> "\"\""
            is TlaType.SetType -> "new HashSet<>()"
            is TlaType.SeqType -> "new ArrayList<>()"
            is TlaType.FunctionType -> "new HashMap<>()"
            is TlaType.TupleType -> "new ArrayList<>()"
            is TlaType.RecordType -> "new HashMap<>()"
            is TlaType.ModelValueType -> "0"
            is TlaType.PowerSetType -> "new HashSet<>()"
            is TlaType.IntervalType -> "0"
            is TlaType.Untyped -> "null"
        }
    }
}
