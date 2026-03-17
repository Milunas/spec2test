package io.github.spec2test.parser

import io.github.spec2test.ir.*
import tla2sany.drivers.SANY
import tla2sany.modanalyzer.SpecObj
import tla2sany.semantic.*
import util.SimpleFilenameToStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files

/**
 * SANY-based TLA+ parser for spec2test.
 *
 * Uses SANY (Lamport's Syntactic Analyzer) to produce a fully-typed AST,
 * then walks it to build a [TlaModule] with full expression trees.
 *
 * Key difference from tla2lincheck's parser: we produce [TlaExpr] AST nodes
 * instead of pre-classified effects (Increment/SetAdd/etc.). All semantic
 * classification happens in the generator phase.
 */
class SanyTlaParser {

    // ─── PUBLIC API ──────────────────────────────────────────────────────

    fun parse(tlaContent: String, filePath: String = ""): ParseResult {
        val warnings = mutableListOf<ParseWarning>()
        val errors = mutableListOf<ParseError>()

        if (filePath.isEmpty() && !MODULE_HEADER_RE.containsMatchIn(tlaContent)) {
            errors.add(ParseError("Missing module header: TLA+ spec must start with ---- MODULE <Name> ----"))
            return ParseResult(null, emptyList(), errors)
        }

        val tlaFile = prepareTlaFile(tlaContent, filePath)
        val module: ModuleNode
        try {
            module = runSany(tlaFile, errors)
                ?: return ParseResult(null, warnings, errors)
        } catch (e: Exception) {
            errors.add(ParseError("SANY error: ${e.message}"))
            return ParseResult(null, warnings, errors)
        } finally {
            if (filePath.isEmpty()) {
                tlaFile.delete()
                tlaFile.parentFile?.let { parent ->
                    if (parent.name.startsWith("spec2test-sany-")) {
                        parent.deleteRecursively()
                    }
                }
            }
        }

        return buildModule(module, filePath, warnings, errors)
    }

    fun parseFile(file: File): ParseResult {
        require(file.exists()) { "TLA+ file not found: ${file.absolutePath}" }
        return parse(file.readText(), file.absolutePath)
    }

    // ─── SANY INVOCATION ─────────────────────────────────────────────────

    private fun prepareTlaFile(content: String, filePath: String): File {
        if (filePath.isNotEmpty()) {
            val file = File(filePath)
            if (file.exists()) return file
        }
        val tmpDir = Files.createTempDirectory("spec2test-sany-").toFile()
        val moduleName = MODULE_HEADER_RE.find(content)?.groupValues?.get(1) ?: "Spec"
        val tmpFile = File(tmpDir, "$moduleName.tla")
        tmpFile.writeText(content)
        return tmpFile
    }

    private fun runSany(tlaFile: File, errors: MutableList<ParseError>): ModuleNode? {
        val parentPath = tlaFile.parentFile?.absolutePath ?: "."
        val resolver = SimpleFilenameToStream(parentPath)
        val specObj = SpecObj(tlaFile.absolutePath, resolver)

        val devNull = PrintStream(java.io.OutputStream.nullOutputStream())
        try {
            val exitCode = SANY.frontEndMain(specObj, tlaFile.absolutePath, devNull)
            if (exitCode != 0) {
                specObj.parseErrors?.let { pe ->
                    if (!pe.isSuccess) errors.add(ParseError("SANY parse errors: ${pe.toString().take(500)}"))
                }
                specObj.semanticErrors?.let { se ->
                    if (!se.isSuccess) errors.add(ParseError("SANY semantic errors: ${se.toString().take(500)}"))
                }
                return null
            }
        } catch (e: Exception) {
            errors.add(ParseError("SANY exception: ${e.message}"))
            return null
        }

        return specObj.externalModuleTable?.rootModule
    }

    // ─── MODULE BUILDING ─────────────────────────────────────────────────

    private fun buildModule(
        module: ModuleNode,
        filePath: String,
        warnings: MutableList<ParseWarning>,
        errors: MutableList<ParseError>
    ): ParseResult {
        val moduleName = module.name.toString()

        val constants = extractConstants(module)
        val variables = extractVariables(module)

        if (variables.isEmpty()) {
            warnings.add(ParseWarning("No VARIABLES section found"))
        }

        // Filter to locally-defined operators only (exclude EXTENDS imports)
        val opDefs = (module.opDefs ?: emptyArray())
            .filter { !it.isLocal && it.location?.source()?.toString() == moduleName }

        val extends = extractExtends(module)

        val operatorDefs = opDefs.map { convertOpDef(it, warnings) }

        // Type inference: enrich variables with types from TypeOK and Init
        val typedVariables = inferTypes(variables, operatorDefs)

        val tlaModule = TlaModule(
            name = moduleName,
            extends = extends,
            constants = constants,
            variables = typedVariables,
            operatorDefs = operatorDefs,
            source = TlaSource.File(filePath, moduleName)
        )

        return ParseResult(tlaModule, warnings, errors)
    }

    // ─── EXTENDS ─────────────────────────────────────────────────────────

    private fun extractExtends(module: ModuleNode): List<String> {
        // SANY resolves EXTENDS into the module table; we extract names from
        // the instances list. This is a simplified extraction.
        val result = mutableListOf<String>()
        val allDefs = module.opDefs ?: emptyArray()
        val moduleName = module.name.toString()
        for (def in allDefs) {
            val source = def.location?.source()?.toString() ?: continue
            if (source != moduleName && source !in result && source != "BOOLEAN") {
                result.add(source)
            }
        }
        return result
    }

    // ─── CONSTANTS & VARIABLES ───────────────────────────────────────────

    private fun extractConstants(module: ModuleNode): List<TlaConstant> {
        val decls = module.constantDecls ?: return emptyList()
        return decls.map { TlaConstant(name = it.name.toString()) }
    }

    private fun extractVariables(module: ModuleNode): List<TlaVariable> {
        val decls = module.variableDecls ?: return emptyList()
        return decls.map { TlaVariable(name = it.name.toString()) }
    }

    // ─── OPERATOR DEFINITION CONVERSION ──────────────────────────────────

    private fun convertOpDef(def: OpDefNode, warnings: MutableList<ParseWarning>): TlaOperatorDef {
        val name = def.name.toString()
        val params = (def.params ?: emptyArray()).map {
            TlaFormalParam(name = it.name.toString(), arity = it.arity)
        }

        val body = try {
            convertExpr(def.body)
        } catch (e: Exception) {
            warnings.add(ParseWarning("Failed to convert body of operator '$name': ${e.message}"))
            TlaExpr.BoolLit(true)
        }

        return TlaOperatorDef(
            name = name,
            params = params,
            body = body,
            isLocal = def.isLocal,
            rawTla = safeNodeToString(def.body)
        )
    }

    // ─── EXPRESSION CONVERSION (SANY AST → TlaExpr) ─────────────────────

    /**
     * Convert a SANY AST node to a [TlaExpr].
     *
     * This is the core of the parser — it walks every SANY node type and
     * produces a faithful [TlaExpr] representation.
     */
    private fun convertExpr(node: ExprOrOpArgNode?): TlaExpr {
        if (node == null) return TlaExpr.BoolLit(true)

        return when (node) {
            is NumeralNode -> TlaExpr.IntLit(node.`val`().toInt())
            is DecimalNode -> TlaExpr.IntLit(node.mantissa().toInt())
            is StringNode -> TlaExpr.StringLit(node.rep.toString())
            is AtNode -> TlaExpr.AtExpr
            is LetInNode -> convertLetIn(node)
            is OpApplNode -> convertOpAppl(node)
            is SubstInNode -> convertExpr(node.body)
            else -> {
                // Fallback: try to extract as name reference
                val name = nodeToName(node)
                if (name != null) TlaExpr.NameRef(name) else TlaExpr.BoolLit(true)
            }
        }
    }

    private fun convertLetIn(node: LetInNode): TlaExpr {
        val defs = node.lets.map { letDef ->
            TlaOperatorDef(
                name = letDef.name.toString(),
                params = (letDef.params ?: emptyArray()).map {
                    TlaFormalParam(it.name.toString(), it.arity)
                },
                body = convertExpr(letDef.body)
            )
        }
        val body = convertExpr(node.body)
        return TlaExpr.LetIn(defs, body)
    }

    private fun convertOpAppl(node: OpApplNode): TlaExpr {
        val op = node.operator ?: return TlaExpr.BoolLit(true)
        val opName = op.name.toString()
        val args = node.args ?: emptyArray()

        // ── Conjunction/Disjunction Lists ────────────────────────────
        if (opName == OP_CONJ_LIST || opName == "/\\") {
            return TlaExpr.ConjList(args.map { convertExpr(it) })
        }
        if (opName == OP_DISJ_LIST || opName == "\\/") {
            return TlaExpr.DisjList(args.map { convertExpr(it) })
        }

        // ── Bounded Quantifiers ──────────────────────────────────────
        if (opName == OP_BOUNDED_FORALL || opName == OP_BOUNDED_EXISTS) {
            return convertBoundedQuantifier(node, opName)
        }

        // ── Unbounded Quantifiers ────────────────────────────────────
        if (opName == OP_UNBOUNDED_FORALL || opName == OP_UNBOUNDED_EXISTS) {
            val kind = if (opName == OP_UNBOUNDED_FORALL) QuantKind.FORALL else QuantKind.EXISTS
            val body = if (args.isNotEmpty()) convertExpr(args[0]) else TlaExpr.BoolLit(true)
            return TlaExpr.QuantExpr(kind, emptyList(), body)
        }

        // ── Prime (x') ──────────────────────────────────────────────
        if (opName == "'") {
            val innerName = if (args.isNotEmpty()) nodeToName(args[0]) else null
            return if (innerName != null) TlaExpr.PrimedRef(innerName)
            else TlaExpr.OpApp(TlaOper.PRIME, args.map { convertExpr(it) })
        }

        // ── UNCHANGED ────────────────────────────────────────────────
        if (opName == "UNCHANGED") {
            val varNames = if (args.isNotEmpty()) extractVarNames(args[0]) else emptyList()
            return TlaExpr.Unchanged(varNames)
        }

        // ── Set enumeration {a, b, c} ───────────────────────────────
        if (opName == OP_SET_ENUM) {
            return TlaExpr.SetEnumExpr(args.map { convertExpr(it) })
        }

        // ── Tuple <<a, b, c>> ────────────────────────────────────────
        if (opName == OP_TUPLE) {
            return TlaExpr.TupleExpr(args.map { convertExpr(it) })
        }

        // ── Function constructor [x \in S |-> e] ────────────────────
        if (opName == OP_FCN_CONSTRUCTOR) {
            val bounds = extractBounds(node)
            val body = if (args.isNotEmpty()) convertExpr(args[0]) else TlaExpr.BoolLit(true)
            return TlaExpr.FunExpr(bounds, body)
        }

        // ── Function application f[x] ───────────────────────────────
        if (opName == OP_FCN_APPLY) {
            if (args.size >= 2) {
                return TlaExpr.FunAppExpr(convertExpr(args[0]), convertExpr(args[1]))
            }
        }

        // ── EXCEPT [f EXCEPT ![k] = v] ──────────────────────────────
        if (opName == OP_EXCEPT) {
            return convertExcept(node)
        }

        // ── Set filter {x \in S : P(x)} ─────────────────────────────
        if (opName == OP_SET_OF_RCD || opName == OP_SUBSET_OF) {
            return convertSetFilter(node)
        }

        // ── Set map {f(x) : x \in S} ────────────────────────────────
        if (opName == OP_SET_OF_ALL) {
            return convertSetMap(node)
        }

        // ── Record constructor [a |-> 1, b |-> 2] ───────────────────
        if (opName == OP_RCD_CONSTRUCTOR) {
            return convertRecordConstructor(node)
        }

        // ── Record set [a : S, b : T] (set of records) ──────────────
        if (opName == OP_SET_OF_RCD) {
            // Treated as a type expression — convert to SetEnumExpr for now
            return TlaExpr.OpApp(TlaOper.USER_DEFINED, args.map { convertExpr(it) }, opName = "RecordSet")
        }

        // ── IF-THEN-ELSE ─────────────────────────────────────────────
        if (opName == OP_IF_THEN_ELSE) {
            if (args.size >= 3) {
                return TlaExpr.IfThenElse(
                    condition = convertExpr(args[0]),
                    thenExpr = convertExpr(args[1]),
                    elseExpr = convertExpr(args[2])
                )
            }
        }

        // ── CASE ─────────────────────────────────────────────────────
        if (opName == OP_CASE) {
            return convertCase(node)
        }

        // ── CHOOSE ───────────────────────────────────────────────────
        if (opName == OP_BC) {
            return convertChoose(node)
        }

        // ── Function set [S -> T] ────────────────────────────────────
        if (opName == OP_SET_OF_FCNS || opName == OP_FCN_SET) {
            if (args.size >= 2) {
                return TlaExpr.OpApp(TlaOper.USER_DEFINED, args.map { convertExpr(it) }, opName = "FunSet")
            }
        }

        // ── Sequence set Seq(S) ──────────────────────────────────────
        if (opName == "Seq") {
            return TlaExpr.OpApp(TlaOper.USER_DEFINED, args.map { convertExpr(it) }, opName = "Seq")
        }

        // ── Stutter [Next]_vars ──────────────────────────────────────
        if (opName == OP_STUTTER_ACTION || opName == OP_NO_STUTTER_ACTION) {
            return TlaExpr.OpApp(TlaOper.STUTTER, args.map { convertExpr(it) })
        }

        // ── Temporal: [] (always) ────────────────────────────────────
        if (opName == OP_BOX) {
            return TlaExpr.TemporalExpr(TemporalKind.ALWAYS, args.map { convertExpr(it) })
        }

        // ── Temporal: <> (eventually) ────────────────────────────────
        if (opName == OP_DIAMOND) {
            return TlaExpr.TemporalExpr(TemporalKind.EVENTUALLY, args.map { convertExpr(it) })
        }

        // ── Temporal: ~> (leads-to) ──────────────────────────────────
        if (opName == "~>") {
            return TlaExpr.TemporalExpr(TemporalKind.LEADS_TO, args.map { convertExpr(it) })
        }

        // ── Fairness: WF / SF ────────────────────────────────────────
        if (opName.startsWith("WF_") || opName == OP_WF) {
            if (args.size >= 2) {
                return TlaExpr.FairnessExpr(FairnessKind.WEAK, convertExpr(args[0]), convertExpr(args[1]))
            }
        }
        if (opName.startsWith("SF_") || opName == OP_SF) {
            if (args.size >= 2) {
                return TlaExpr.FairnessExpr(FairnessKind.STRONG, convertExpr(args[0]), convertExpr(args[1]))
            }
        }

        // ── Built-in operators → TlaOper mapping ─────────────────────
        val tlaOper = mapBuiltinOper(opName)
        if (tlaOper != null) {
            return TlaExpr.OpApp(tlaOper, args.map { convertExpr(it) })
        }

        // ── User-defined operator reference ──────────────────────────
        if (op is OpDefNode) {
            if (args.isEmpty()) {
                // Nullary operator reference: e.g., `Increment` in Next
                return TlaExpr.OperRef(opName)
            }
            return TlaExpr.OpApp(TlaOper.USER_DEFINED, args.map { convertExpr(it) }, opName = opName)
        }

        // ── Formal parameter reference ───────────────────────────────
        if (op is FormalParamNode) {
            return TlaExpr.ParamRef(opName)
        }

        // ── Constant / variable reference ────────────────────────────
        return TlaExpr.NameRef(opName)
    }

    // ─── QUANTIFIER CONVERSION ───────────────────────────────────────────

    private fun convertBoundedQuantifier(node: OpApplNode, opName: String): TlaExpr {
        val kind = if (opName == OP_BOUNDED_FORALL) QuantKind.FORALL else QuantKind.EXISTS
        val bounds = extractBounds(node)
        val args = node.args ?: emptyArray()
        val body = if (args.isNotEmpty()) convertExpr(args[0]) else TlaExpr.BoolLit(true)
        return TlaExpr.QuantExpr(kind, bounds, body)
    }

    private fun extractBounds(node: OpApplNode): List<QuantBound> {
        val quantSymbols = node.bdedQuantSymbolLists ?: emptyArray()
        val quantBounds = node.bdedQuantBounds ?: emptyArray()
        val bounds = mutableListOf<QuantBound>()
        for (i in quantSymbols.indices) {
            if (i < quantBounds.size) {
                val symbols = quantSymbols[i] ?: continue
                val setExpr = convertExpr(quantBounds[i])
                for (sym in symbols) {
                    bounds.add(QuantBound(sym.name.toString(), setExpr))
                }
            }
        }
        return bounds
    }

    // ─── EXCEPT CONVERSION ───────────────────────────────────────────────

    private fun convertExcept(node: OpApplNode): TlaExpr {
        val args = node.args ?: return TlaExpr.OpApp(TlaOper.EXCEPT, emptyList())
        if (args.isEmpty()) return TlaExpr.OpApp(TlaOper.EXCEPT, emptyList())

        val base = convertExpr(args[0])
        val updates = mutableListOf<ExceptUpdate>()

        for (i in 1 until args.size) {
            val pair = args[i]
            if (pair is OpApplNode) {
                val pairOp = pair.operator?.name?.toString()
                if (pairOp == OP_PAIR) {
                    val pairArgs = pair.args ?: continue
                    if (pairArgs.size >= 2) {
                        val keys = extractExceptKeys(pairArgs[0])
                        val value = convertExpr(pairArgs[1])
                        updates.add(ExceptUpdate(keys, value))
                    }
                }
            }
        }

        return TlaExpr.ExceptExpr(base, updates)
    }

    private fun extractExceptKeys(node: ExprOrOpArgNode?): List<TlaExpr> {
        if (node == null) return emptyList()
        if (node is OpApplNode) {
            val opName = node.operator?.name?.toString()
            if (opName == OP_TUPLE || opName == OP_SEQ) {
                val args = node.args ?: return emptyList()
                return args.map { convertExpr(it) }
            }
        }
        return listOf(convertExpr(node))
    }

    // ─── SET FILTER / MAP CONVERSION ─────────────────────────────────────

    private fun convertSetFilter(node: OpApplNode): TlaExpr {
        val bounds = extractBounds(node)
        val args = node.args ?: return TlaExpr.SetEnumExpr(emptyList())
        val pred = if (args.isNotEmpty()) convertExpr(args[0]) else TlaExpr.BoolLit(true)

        if (bounds.isNotEmpty()) {
            return TlaExpr.SetFilterExpr(
                variable = bounds[0].name,
                set = bounds[0].set,
                predicate = pred
            )
        }
        return TlaExpr.SetEnumExpr(emptyList())
    }

    private fun convertSetMap(node: OpApplNode): TlaExpr {
        val bounds = extractBounds(node)
        val args = node.args ?: return TlaExpr.SetEnumExpr(emptyList())
        val mapExpr = if (args.isNotEmpty()) convertExpr(args[0]) else TlaExpr.BoolLit(true)
        return TlaExpr.SetMapExpr(mapExpr, bounds)
    }

    // ─── RECORD CONSTRUCTOR ──────────────────────────────────────────────

    private fun convertRecordConstructor(node: OpApplNode): TlaExpr {
        val args = node.args ?: return TlaExpr.RecordExpr(emptyList())
        val fields = mutableListOf<Pair<String, TlaExpr>>()

        val stringNames = node.bdedQuantSymbolLists
        if (stringNames != null) {
            for (i in stringNames.indices) {
                val syms = stringNames[i] ?: continue
                val valExpr = if (i < args.size) convertExpr(args[i]) else TlaExpr.BoolLit(true)
                for (sym in syms) {
                    fields.add(sym.name.toString() to valExpr)
                }
            }
        }

        return TlaExpr.RecordExpr(fields)
    }

    // ─── CASE CONVERSION ─────────────────────────────────────────────────

    private fun convertCase(node: OpApplNode): TlaExpr {
        val args = node.args ?: return TlaExpr.CaseExpr(emptyList())
        val arms = mutableListOf<CaseArm>()
        var other: TlaExpr? = null

        for (arg in args) {
            if (arg is OpApplNode) {
                val pairOp = arg.operator?.name?.toString()
                if (pairOp == OP_PAIR) {
                    val pairArgs = arg.args
                    if (pairArgs != null && pairArgs.size >= 2) {
                        arms.add(CaseArm(convertExpr(pairArgs[0]), convertExpr(pairArgs[1])))
                    }
                } else {
                    other = convertExpr(arg)
                }
            }
        }

        return TlaExpr.CaseExpr(arms, other)
    }

    // ─── CHOOSE CONVERSION ───────────────────────────────────────────────

    private fun convertChoose(node: OpApplNode): TlaExpr {
        val bounds = extractBounds(node)
        val args = node.args ?: return TlaExpr.ChooseExpr("x", TlaExpr.BoolLit(true), TlaExpr.BoolLit(true))
        val body = if (args.isNotEmpty()) convertExpr(args[0]) else TlaExpr.BoolLit(true)

        if (bounds.isNotEmpty()) {
            return TlaExpr.ChooseExpr(bounds[0].name, bounds[0].set, body)
        }
        return TlaExpr.ChooseExpr("x", TlaExpr.BoolLit(true), body)
    }

    // ─── BUILT-IN OPERATOR MAPPING ───────────────────────────────────────

    private fun mapBuiltinOper(opName: String): TlaOper? = when (opName) {
        "+", "\$RealPlus" -> TlaOper.PLUS
        "-", "\$RealMinus" -> TlaOper.MINUS
        "*", "\$RealTimes" -> TlaOper.TIMES
        "\\div" -> TlaOper.DIV
        "%", "\\mod" -> TlaOper.MOD
        "^" -> TlaOper.EXP
        ".." -> TlaOper.DOT_DOT
        "\$UnaryMinus", "\$RealUnaryMinus" -> TlaOper.UMINUS

        "=", "\$Equal" -> TlaOper.EQ
        "#", "/=", "\\neq" -> TlaOper.NEQ
        "<", "\$Less" -> TlaOper.LT
        ">", "\$Greater" -> TlaOper.GT
        "\\leq", "<=", "=<" -> TlaOper.LEQ
        "\\geq", ">=" -> TlaOper.GEQ

        "/\\", "\\land" -> TlaOper.AND
        "\\/", "\\lor" -> TlaOper.OR
        "~", "\\lnot", "\\neg" -> TlaOper.NOT
        "=>", "\$Implies" -> TlaOper.IMPLIES
        "<=>", "\\equiv" -> TlaOper.EQUIV

        "\\in" -> TlaOper.IN
        "\\notin" -> TlaOper.NOTIN
        "\\subseteq" -> TlaOper.SUBSET_EQ
        "\\cup", "\\union" -> TlaOper.UNION
        "\\cap", "\\intersect" -> TlaOper.INTER
        "\\" -> TlaOper.SET_MINUS
        "SUBSET" -> TlaOper.POWERSET
        "UNION" -> TlaOper.SUBSET  // UNION of set of sets
        "\\X" -> TlaOper.TIMES_SET

        "Append" -> TlaOper.APPEND
        "Head" -> TlaOper.HEAD
        "Tail" -> TlaOper.TAIL
        "Len" -> TlaOper.LEN
        "SubSeq" -> TlaOper.SUBSEQ
        "\\o", "\\circ" -> TlaOper.SEQ_CONCAT
        "SelectSeq" -> TlaOper.SELECT_SEQ

        "Cardinality" -> TlaOper.CARDINALITY
        "IsFiniteSet" -> TlaOper.IS_FINITE_SET

        "DOMAIN" -> TlaOper.DOMAIN
        "BOOLEAN" -> TlaOper.BOOLEAN_SET

        "ENABLED" -> TlaOper.ENABLED

        "Print" -> TlaOper.PRINT
        "Assert" -> TlaOper.ASSERT
        "JavaTime" -> TlaOper.JAVA_TIME

        else -> null
    }

    // ─── TYPE INFERENCE ──────────────────────────────────────────────────

    private fun inferTypes(
        variables: List<TlaVariable>,
        operatorDefs: List<TlaOperatorDef>
    ): List<TlaVariable> {
        val typeOKDef = operatorDefs.find { it.name == "TypeOK" }
        val initDef = operatorDefs.find { it.name == "Init" }

        val typeOKTypes = if (typeOKDef != null) inferFromTypeOK(typeOKDef.body) else emptyMap()
        val initTypes = if (initDef != null) inferFromInit(initDef.body) else emptyMap()

        return variables.map { v ->
            val type = typeOKTypes[v.name] ?: initTypes[v.name] ?: TlaType.Untyped
            v.copy(type = type)
        }
    }

    private fun inferFromTypeOK(body: TlaExpr): Map<String, TlaType> {
        val types = mutableMapOf<String, TlaType>()

        fun processConjunct(expr: TlaExpr) {
            when (expr) {
                is TlaExpr.ConjList -> expr.conjuncts.forEach { processConjunct(it) }
                is TlaExpr.OpApp -> {
                    when (expr.op) {
                        TlaOper.IN -> {
                            if (expr.args.size >= 2) {
                                val varName = (expr.args[0] as? TlaExpr.NameRef)?.name ?: return
                                types[varName] = inferTypeFromDomain(expr.args[1])
                            }
                        }
                        TlaOper.SUBSET_EQ -> {
                            if (expr.args.size >= 2) {
                                val varName = (expr.args[0] as? TlaExpr.NameRef)?.name ?: return
                                types[varName] = TlaType.SetType(inferElementType(expr.args[1]))
                            }
                        }
                        TlaOper.AND -> expr.args.forEach { processConjunct(it) }
                        else -> {}
                    }
                }
                else -> {}
            }
        }

        processConjunct(body)
        return types
    }

    private fun inferTypeFromDomain(domainExpr: TlaExpr): TlaType = when (domainExpr) {
        is TlaExpr.NameRef -> when (domainExpr.name) {
            "Nat", "Int" -> TlaType.NatType
            "BOOLEAN" -> TlaType.BoolType
            "STRING" -> TlaType.StringType
            else -> TlaType.ModelValueType(domainExpr.name)
        }
        is TlaExpr.OpApp -> when (domainExpr.op) {
            TlaOper.DOT_DOT -> TlaType.IntType
            TlaOper.POWERSET -> if (domainExpr.args.isNotEmpty()) {
                TlaType.SetType(inferTypeFromDomain(domainExpr.args[0]))
            } else TlaType.SetType(TlaType.Untyped)
            TlaOper.TIMES_SET -> {
                val fieldTypes = domainExpr.args.map { inferTypeFromDomain(it) }
                TlaType.TupleType(fieldTypes)
            }
            TlaOper.USER_DEFINED -> when (domainExpr.opName) {
                "Seq" -> if (domainExpr.args.isNotEmpty()) {
                    TlaType.SeqType(inferTypeFromDomain(domainExpr.args[0]))
                } else TlaType.SeqType(TlaType.Untyped)
                "FunSet" -> if (domainExpr.args.size >= 2) {
                    TlaType.FunctionType(inferTypeFromDomain(domainExpr.args[0]), inferTypeFromDomain(domainExpr.args[1]))
                } else TlaType.FunctionType(TlaType.Untyped, TlaType.Untyped)
                else -> TlaType.Untyped
            }
            else -> TlaType.Untyped
        }
        is TlaExpr.SetEnumExpr -> {
            if (domainExpr.elements.all { it is TlaExpr.StringLit }) TlaType.StringType
            else TlaType.IntType
        }
        else -> TlaType.Untyped
    }

    private fun inferElementType(expr: TlaExpr): TlaType = when (expr) {
        is TlaExpr.NameRef -> TlaType.ModelValueType(expr.name)
        is TlaExpr.OpApp -> if (expr.op == TlaOper.TIMES_SET) {
            TlaType.TupleType(expr.args.map { inferTypeFromDomain(it) })
        } else inferTypeFromDomain(expr)
        else -> TlaType.Untyped
    }

    private fun inferFromInit(body: TlaExpr): Map<String, TlaType> {
        val types = mutableMapOf<String, TlaType>()

        fun processConjunct(expr: TlaExpr) {
            when (expr) {
                is TlaExpr.ConjList -> expr.conjuncts.forEach { processConjunct(it) }
                is TlaExpr.OpApp -> {
                    if (expr.op == TlaOper.EQ && expr.args.size >= 2) {
                        val varName = (expr.args[0] as? TlaExpr.NameRef)?.name ?: return
                        types[varName] = inferTypeFromValue(expr.args[1])
                    }
                    if (expr.op == TlaOper.AND) expr.args.forEach { processConjunct(it) }
                }
                else -> {}
            }
        }

        processConjunct(body)
        return types
    }

    private fun inferTypeFromValue(expr: TlaExpr): TlaType = when (expr) {
        is TlaExpr.IntLit -> TlaType.IntType
        is TlaExpr.BoolLit -> TlaType.BoolType
        is TlaExpr.StringLit -> TlaType.StringType
        is TlaExpr.SetEnumExpr -> {
            if (expr.elements.isEmpty()) TlaType.SetType(TlaType.Untyped)
            else TlaType.SetType(inferTypeFromValue(expr.elements[0]))
        }
        is TlaExpr.TupleExpr -> {
            if (expr.elements.isEmpty()) TlaType.SeqType(TlaType.Untyped)
            else TlaType.TupleType(expr.elements.map { inferTypeFromValue(it) })
        }
        is TlaExpr.FunExpr -> {
            if (expr.bounds.isNotEmpty()) {
                val domType = inferTypeFromDomain(expr.bounds[0].set)
                val rangeType = inferTypeFromValue(expr.body)
                TlaType.FunctionType(domType, rangeType)
            } else TlaType.FunctionType(TlaType.Untyped, TlaType.Untyped)
        }
        is TlaExpr.RecordExpr -> {
            val fieldTypes = expr.fields.associate { it.first to inferTypeFromValue(it.second) }
            TlaType.RecordType(fieldTypes)
        }
        else -> TlaType.Untyped
    }

    // ─── UTILITY ─────────────────────────────────────────────────────────

    private fun nodeToName(node: ExprOrOpArgNode?): String? {
        if (node == null) return null
        if (node is OpApplNode) {
            val op = node.operator
            if (op != null && (node.args == null || node.args.isEmpty())) {
                return op.name.toString()
            }
        }
        return null
    }

    private fun extractVarNames(node: ExprOrOpArgNode?): List<String> {
        if (node == null) return emptyList()
        val name = nodeToName(node)
        if (name != null) return listOf(name)
        if (node is OpApplNode) {
            val opName = node.operator?.name?.toString()
            if (opName == OP_TUPLE) {
                return (node.args ?: emptyArray()).mapNotNull { nodeToName(it) }
            }
        }
        return emptyList()
    }

    private fun safeNodeToString(node: ExprOrOpArgNode?): String {
        return try {
            node?.toString() ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    // ─── SANY INTERNAL OPERATOR NAME CONSTANTS ───────────────────────────

    companion object {
        private val MODULE_HEADER_RE = Regex("""-{4,}\s*MODULE\s+(\w+)\s*-{4,}""")

        // SANY internal operator codes
        private const val OP_CONJ_LIST = "\$ConjList"
        private const val OP_DISJ_LIST = "\$DisjList"
        private const val OP_BOUNDED_FORALL = "\$BoundedForall"
        private const val OP_BOUNDED_EXISTS = "\$BoundedExists"
        private const val OP_UNBOUNDED_FORALL = "\$UnboundedForall"
        private const val OP_UNBOUNDED_EXISTS = "\$UnboundedExists"
        private const val OP_SET_ENUM = "\$SetEnumerate"
        private const val OP_TUPLE = "\$Tuple"
        private const val OP_PAIR = "\$Pair"
        private const val OP_SEQ = "\$Seq"
        private const val OP_FCN_CONSTRUCTOR = "\$FcnConstructor"
        private const val OP_FCN_APPLY = "\$FcnApply"
        private const val OP_EXCEPT = "\$Except"
        private const val OP_SET_OF_RCD = "\$SetOfRcds"
        private const val OP_SUBSET_OF = "\$SubsetOf"
        private const val OP_SET_OF_ALL = "\$SetOfAll"
        private const val OP_RCD_CONSTRUCTOR = "\$RcdConstructor"
        private const val OP_SET_OF_FCNS = "\$SetOfFcns"
        private const val OP_FCN_SET = "\$FcnSet"
        private const val OP_IF_THEN_ELSE = "\$IfThenElse"
        private const val OP_CASE = "\$Case"
        private const val OP_BC = "\$BoundedChoose"
        private const val OP_STUTTER_ACTION = "\$SquareAct"
        private const val OP_NO_STUTTER_ACTION = "\$AngleAct"
        private const val OP_BOX = "\$Temporal\$Box"
        private const val OP_DIAMOND = "\$Temporal\$Diamond"
        private const val OP_WF = "\$WF"
        private const val OP_SF = "\$SF"
    }
}
