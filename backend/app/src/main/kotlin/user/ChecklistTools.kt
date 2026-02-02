package org.example.app.user

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import org.example.app.intermediate.InputType
import org.example.app.intermediate.Metric
import org.example.app.intermediate.ModelAutoGenChecklist
import org.example.app.intermediate.ModelFamilyCategory
import org.example.app.intermediate.OutputType
import org.example.app.intermediate.SplitStrategy
import org.example.app.intermediate.TrainingType

@LLMDescription("Tools to update and validate the ModelAutoGenChecklist. Always call these instead of assuming state.")
class ChecklistTools(
    private val checklist: ModelAutoGenChecklist
) : ToolSet {

    // ---- helpers ----
    private inline fun <reified E : Enum<E>> parseEnum(enumName: String, raw: String): E {
        val v = raw.trim().uppercase()
        return enumValues<E>().firstOrNull { it.name == v }
            ?: throw IllegalArgumentException(
                "Invalid $enumName='$raw'. Allowed: ${enumValues<E>().joinToString { it.name }}"
            )
    }

    private fun parseField(raw: String): ModelAutoGenChecklist.Field =
        parseEnum("Field", raw)

    private inline fun <reified E : Enum<E>>withFieldParsed(enumName: String, raw: String, f: (E) -> String): String {
        val field: E
        try {
            field = parseEnum(enumName, raw)
        } catch (e: IllegalArgumentException) {
            return e.toString()
        }
        return f(field)
    }

    // ---- setters (call your checklist.setXxx) ----
    @Tool
    @LLMDescription("Set input type. Allowed: IMAGE, VIDEO, TABULAR, TEXT, AUDIO, OTHER.")
    fun setInputType(@LLMDescription("Enum name, e.g. TABULAR") value: String): String {
        return withFieldParsed<InputType>("InputType", value) {
            checklist.setInputType(it)
            return@withFieldParsed checklist.snapshot()
        }

    }

    @Tool
    @LLMDescription("Set output type. Allowed: REGRESSION, CLASSIFICATION, RANKING, CLUSTERING, SEGMENTATION, GENERATION, OTHER.")
    fun setOutputType(@LLMDescription("Enum name, e.g. REGRESSION") value: String): String {
        return withFieldParsed<OutputType>("OutputType", value) {
            checklist.setOutputType(it)
            return@withFieldParsed checklist.snapshot()
        }

    }

    @Tool
    @LLMDescription("Set training type. Allowed: SUPERVISED, UNSUPERVISED, REINFORCEMENT.")
    fun setTrainingType(@LLMDescription("Enum name, e.g. SUPERVISED") value: String): String {
        return withFieldParsed<TrainingType>("TrainingType", value) {
            checklist.setTrainingType(it)
            checklist.snapshot()
        }

    }

    @Tool
    @LLMDescription("Set data path (file or directory). Use absolute path when possible.")
    fun setDataPath(@LLMDescription("Path string") path: String): String {
        checklist.setDataPath(path)
        return checklist.snapshot()
    }

    @Tool
    @LLMDescription("Set prediction data path (file). Use absolute path when possible.")
    fun setPredictionDataPath(@LLMDescription("Path string") path: String): String {
        checklist.setPredictionDataPath(path)
        return checklist.snapshot()
    }

    @Tool
    @LLMDescription(
        "Set split strategy. Allowed: PREDEFINED, RANDOM, STRATIFIED, TIME_SERIES, GROUPED, K_FOLD, CUSTOM. " +
                "If CUSTOM, also provide customDesc."
    )
    fun setSplitStrategy(
        @LLMDescription("Enum name, e.g. STRATIFIED") strategy: String,
        @LLMDescription("Required if strategy=CUSTOM. Describe how to split.") customDesc: String? = null
    ): String {
        return withFieldParsed<SplitStrategy>("SplitStrategy", strategy) {
            checklist.setSplitStrategy(it, customDesc)
            checklist.snapshot()
        }
    }

    @Tool
    @LLMDescription(
        "Set metric. Allowed: MAE, MSE, RMSE, R2, ACCURACY, F1, ROC_AUC, PR_AUC, LOG_LOSS, " +
                "NDCG, MAP, MRR, SPEARMAN, PEARSON, EPISODE_RETURN, SUCCESS_RATE, CUSTOM. " +
                "If CUSTOM, also provide customDesc."
    )
    fun setMetric(
        @LLMDescription("Enum name, e.g. RMSE") metric: String,
        @LLMDescription("Required if metric=CUSTOM. Define the metric.") customDesc: String? = null
    ): String {
        return withFieldParsed<Metric>("Metric", metric) { m ->
            checklist.setMetric(m, customDesc)
            return@withFieldParsed checklist.snapshot()
        }

    }

    @Tool
    @LLMDescription("Set optional model family category. Allowed: LINEAR, TREE_BOOSTING, MLP, CNN, VISION_TRANSFORMER, SEQ_TRANSFORMER, RNN, GNN, DIFFUSION, RL_POLICY, OTHER.")
    fun setModelFamilyCategory(@LLMDescription("Enum name, e.g. TREE_BOOSTING") value: String): String {
        return withFieldParsed<ModelFamilyCategory>("ModelFamilyCategory", value) { cat ->
            checklist.setModelFamilyCategory(cat)
            return@withFieldParsed checklist.snapshot()
        }

    }

    // ---- confirm / unconfirm ----
    @Tool
    @LLMDescription("Confirm a field AFTER the user explicitly confirms the current value.")
    fun confirmField(@LLMDescription("Field name, e.g. DATA_PATH") field: String): String {
        try {
            val parsedField = parseField(field)
            checklist.confirm(parsedField)
            return checklist.snapshot()
        } catch (e: IllegalArgumentException) {
            return e.toString()
        }
    }

    @Tool
    @LLMDescription("Unconfirm a field (e.g. if user says the previous confirmation was wrong).")
    fun unconfirmField(@LLMDescription("Field name, e.g. DATA_PATH") field: String): String {
        try {
            val parsedField = parseField(field)
            checklist.unconfirm(parsedField)
            return checklist.snapshot()
        } catch (e: IllegalArgumentException) {
            return e.toString()
        }
    }

    // ---- status / gating ----
    @Tool
    @LLMDescription("Check missing required fields and unconfirmed required fields. Use this to decide next questions.")
    fun checkStatus(): String {
        val r = checklist.check()
        return buildString {
            appendLine("ok=${r.ok}")
            appendLine("missing=${r.missing.joinToString { it.name }}")
            appendLine("unconfirmed=${r.unconfirmed.joinToString { it.name }}")
        }
    }

    @Tool
    @LLMDescription("Render a full snapshot of the checklist for display to the user.")
    fun snapshot(): String = checklist.snapshot()
}