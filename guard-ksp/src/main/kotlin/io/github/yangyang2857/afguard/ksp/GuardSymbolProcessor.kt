package io.github.yangyang2857.afguard.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueArgument
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.validate
import java.io.OutputStream

class GuardSymbolProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        GuardSymbolProcessor(environment.codeGenerator, environment.logger)
}

/**
 * 读取 `@Guard` / `@RateLimit` / 参数校验注解，为每个被调用的函数生成校验器与元数据表。
 *
 * 生成物只有一个文件：`io.github.yangyang2857.afguard.generated.AfGuardGeneratedValidators`。
 * 运行时通过一次反射拿到它（见 ValidatorRegistry），之后全程直接调用，不在热路径上。
 */
class GuardSymbolProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {

    companion object {
        const val PKG = "io.github.yangyang2857.afguard"
        const val GEN_PKG = "$PKG.generated"
        const val GEN_NAME = "AfGuardGeneratedValidators"

        val FN_ANNOTATIONS = listOf(
            "$PKG.Guard",
            "$PKG.RateLimit"
        )
    }

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val candidates = FN_ANNOTATIONS
            .flatMap { resolver.getSymbolsWithAnnotation(it).toList() }
            .filterIsInstance<KSFunctionDeclaration>()
            .filter { it.validate() }
            .distinctBy { it.qualifiedName?.asString() }

        if (candidates.isEmpty()) return emptyList()

        val infos = candidates.mapNotNull { fn ->
            val id = fn.simpleName.asString()
            val params = fn.parameters.map { p ->
                ParamInfo(
                    name = p.name?.asString() ?: return@map null,
                    type = p.type.resolve(),
                    nullable = p.type.resolve().isMarkedNullable,
                    annotations = p.annotations.toList()
                )
            }.filterNotNull()

            FunctionInfo(
                functionId = id,
                guard = readGuard(fn),
                rate = readRate(fn),
                params = params
            )
        }

        if (infos.isEmpty()) return emptyList()

        writeFile(infos)
        return emptyList()
    }

    // -----------------------------------------------------------------------

    private data class ParamInfo(
        val name: String,
        val type: KSType,
        val nullable: Boolean,
        val annotations: List<KSAnnotation>
    )

    private data class GuardData(
        val policy: String,
        val risk: String,
        val redactOutput: Boolean,
        val idempotent: Boolean
    )

    private data class RateData(
        val calls: Int,
        val perSeconds: Int,
        val scope: String,
        val burst: Int
    )

    private data class FunctionInfo(
        val functionId: String,
        val guard: GuardData?,
        val rate: RateData?,
        val params: List<ParamInfo>
    )

    private fun readGuard(fn: KSFunctionDeclaration): GuardData? {
        val ann = fn.annotations.firstOrNull { it.shortName.asString() == "Guard" } ?: return null
        return GuardData(
            policy = enumName(ann, "policy") ?: "CONFIRM_ONCE",
            risk = enumName(ann, "risk") ?: "MEDIUM",
            redactOutput = ann.boolValue("redactOutput") ?: false,
            idempotent = ann.boolValue("idempotent") ?: false
        )
    }

    private fun readRate(fn: KSFunctionDeclaration): RateData? {
        val ann = fn.annotations.firstOrNull { it.shortName.asString() == "RateLimit" } ?: return null
        return RateData(
            calls = ann.intValue("calls") ?: 10,
            perSeconds = ann.intValue("perSeconds") ?: 60,
            scope = enumName(ann, "scope") ?: "PER_CALLER",
            burst = ann.intValue("burst") ?: 3
        )
    }

    private fun KSAnnotation.arg(name: String): KSValueArgument? =
        arguments.firstOrNull { it.name?.asString() == name }

    private fun KSAnnotation.intValue(name: String): Int? =
        arg(name)?.value?.let { (it as? Number)?.toInt() }

    private fun KSAnnotation.boolValue(name: String): Boolean? =
        arg(name)?.value as? Boolean

    private fun KSAnnotation.stringValue(name: String): String? =
        arg(name)?.value as? String

    private fun enumName(ann: KSAnnotation, name: String): String? {
        val v = ann.arg(name)?.value ?: return null
        return when (v) {
            is KSType -> v.declaration.simpleName.asString()
            is KSClassDeclaration -> v.simpleName.asString()
            else -> v.toString()
        }
    }

    // -----------------------------------------------------------------------

    private fun writeFile(infos: List<FunctionInfo>) {
        val fileName = GEN_NAME
        val deps = Dependencies(aggregating = true)

        val out: OutputStream = codeGenerator.createNewFile(deps, GEN_PKG, fileName)
        out.bufferedWriter().use { w ->
            w.write(header())
            w.write(metadataBody(infos))
            w.write(validateBody(infos))
            w.write("}\n")
        }
        logger.info("afguard: generated $GEN_NAME for ${infos.size} function(s): ${infos.joinToString { it.functionId }}")
    }

    private fun header(): String = buildString {
        appendLine("// 由 afguard-ksp 自动生成，请勿手工修改。")
        appendLine("package $GEN_PKG")
        appendLine()
        appendLine("import android.os.Bundle")
        appendLine("import $PKG.GuardError")
        appendLine("import $PKG.Policy")
        appendLine("import $PKG.RateScope")
        appendLine("import $PKG.Risk")
        appendLine("import $PKG.internal.FunctionMeta")
        appendLine("import $PKG.internal.GeneratedGuardTable")
        appendLine("import $PKG.internal.RateSpec")
        appendLine()
        appendLine("object $GEN_NAME : GeneratedGuardTable {")
    }

    private fun metadataBody(infos: List<FunctionInfo>): String = buildString {
        appendLine("    override fun metadataOf(functionId: String): FunctionMeta? = when (functionId) {")
        for (f in infos) {
            val policy = f.guard?.policy ?: "CONFIRM_ONCE"
            val risk = f.guard?.risk ?: "MEDIUM"
            val redact = f.guard?.redactOutput ?: false
            val idem = f.guard?.idempotent ?: false
            val rateExpr = f.rate?.let {
                "RateSpec(calls = ${it.calls}, perSeconds = ${it.perSeconds}, scope = RateScope.${it.scope}, burst = ${it.burst})"
            } ?: "null"

            appendLine("        \"${f.functionId}\" -> FunctionMeta(")
            appendLine("            functionId = \"${f.functionId}\",")
            appendLine("            policy = Policy.$policy,")
            appendLine("            risk = Risk.$risk,")
            appendLine("            redactOutput = $redact,")
            appendLine("            idempotent = $idem,")
            appendLine("            rateSpec = $rateExpr")
            appendLine("        )")
        }
        appendLine("        else -> null")
        appendLine("    }")
        appendLine()
    }

    private fun validateBody(infos: List<FunctionInfo>): String = buildString {
        appendLine("    override fun validate(functionId: String, args: Bundle): GuardError? = when (functionId) {")
        for (f in infos) {
            appendLine("        \"${f.functionId}\" -> validate_${safeName(f.functionId)}(args)")
        }
        appendLine("        else -> null")
        appendLine("    }")
        appendLine()

        for (f in infos) {
            appendLine("    private fun validate_${safeName(f.functionId)}(args: Bundle): GuardError? {")
            for (p in f.params) {
                appendLine(validateParam(f.functionId, p))
            }
            appendLine("        return null")
            appendLine("    }")
            appendLine()
        }
    }

    private fun safeName(id: String): String =
        id.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")

    private fun validateParam(functionId: String, p: ParamInfo): String {
        val typeName = p.type.declaration.simpleName.asString()
        val getter = when (typeName) {
            "String" -> "args.getString(\"${p.name}\")"
            "Int" -> "if (args.containsKey(\"${p.name}\")) args.getInt(\"${p.name}\") else null"
            "Long" -> "if (args.containsKey(\"${p.name}\")) args.getLong(\"${p.name}\") else null"
            "Boolean" -> "if (args.containsKey(\"${p.name}\")) args.getBoolean(\"${p.name}\") else null"
            "Double" -> "if (args.containsKey(\"${p.name}\")) args.getDouble(\"${p.name}\") else null"
            else -> null
        } ?: return "        // 跳过不支持自动校验的类型: ${p.name}: $typeName\n"

        val sb = StringBuilder()
        val varName = "v_${safeName(p.name)}"

        if (!p.nullable) {
            sb.appendLine("        val $varName = $getter ?: return GuardError.missing(\"$functionId\", \"${p.name}\")")
        } else {
            sb.appendLine("        val ${varName}Raw = $getter")
        }

        val textLimit = p.annotations.firstOrNull { it.shortName.asString() == "TextLimit" }
        val intRange = p.annotations.firstOrNull { it.shortName.asString() == "IntRange" }
        val pattern = p.annotations.firstOrNull { it.shortName.asString() == "Pattern" }
        val oneOf = p.annotations.firstOrNull { it.shortName.asString() == "OneOfVal" }

        if (textLimit != null && typeName == "String") {
            val max = textLimit.intValue("max") ?: 256
            val min = textLimit.intValue("min") ?: 0
            sb.appendLine("        if ($varName.length < $min || $varName.length > $max) {")
            sb.appendLine("            return GuardError.invalidArgument(\"$functionId\", \"${p.name}\", \"length ${'$'}{$varName.length} out of range\", \"length $min..$max\")")
            sb.appendLine("        }")
        }

        if (pattern != null && typeName == "String") {
            val regex = pattern.stringValue("regex")?.escapeKotlin() ?: ""
            sb.appendLine("        if (!Regex(\"$regex\").matches($varName)) {")
            sb.appendLine("            return GuardError.invalidArgument(\"$functionId\", \"${p.name}\", \"format mismatch\", \"regex $regex\")")
            sb.appendLine("        }")
        }

        if (oneOf != null && typeName == "String") {
            val values = oneOf.stringValues("values")
            if (values.isNotEmpty()) {
                val csv = values.joinToString(", ") { "\"" + it.escapeKotlin() + "\"" }
                sb.append("        if (").append(varName).append(" !in listOf(").append(csv).append(")) {\n")
                sb.append("            return GuardError.invalidArgument(\"").append(functionId)
                    .append("\", \"").append(p.name)
                    .append("\", \"value not allowed\", \"one of ").append(csv).append("\")\n")
                sb.append("        }\n")
            }
        }

        if (intRange != null && (typeName == "Int" || typeName == "Long")) {
            val min = (intRange.arg("min")?.value as? Number)?.toLong() ?: Long.MIN_VALUE
            val max = (intRange.arg("max")?.value as? Number)?.toLong() ?: Long.MAX_VALUE
            sb.appendLine("        if ($varName < $min || $varName > $max) {")
            sb.appendLine("            return GuardError.invalidArgument(\"$functionId\", \"${p.name}\", \"value out of range\", \"range $min..$max\")")
            sb.appendLine("        }")
        }

        return sb.toString()
    }

    /** 注解里的 Array<String> 在 KSP 里可能是 List<*>，也可能是 Array<*>，两种都兜住。 */
    private fun KSAnnotation.stringValues(name: String): List<String> {
        val raw = arg(name)?.value ?: return emptyList()
        val items = when (raw) {
            is List<*> -> raw
            is Array<*> -> raw.toList()
            else -> return emptyList()
        }
        return items.mapNotNull { it as? String }
    }

    private fun String.escapeKotlin(): String =
        replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\u0024", "\\\u0024")
}
