package org.example.app.intermediate
import java.nio.file.Path
import java.nio.file.Paths

enum class InputType { IMAGE, VIDEO, TABULAR, TEXT, AUDIO, OTHER }

enum class OutputType {
    REGRESSION,          // Numeric regression
    CLASSIFICATION,      // Discrete classes
    RANKING,             // Ordering / ranking
    CLUSTERING,          // Clustering (often unsupervised)
    SEGMENTATION,        // Image/video segmentation
    GENERATION,          // Generative tasks
    OTHER
}

enum class TrainingType { SUPERVISED, UNSUPERVISED, REINFORCEMENT }

enum class SplitStrategy {
    PREDEFINED,   // You already have train/val/test split files or folder structure
    RANDOM,       // Random split
    STRATIFIED,   // Stratified split (common for classification)
    TIME_SERIES,  // Time-based split (avoid leakage)
    GROUPED,      // Group-based split (same user/site stays within one split)
    K_FOLD,       // K-fold cross validation
    CUSTOM        // Custom strategy described by the user
}

enum class Metric {
    // Regression metrics
    MAE, MSE, RMSE, R2,

    // Classification metrics
    ACCURACY, F1, ROC_AUC, PR_AUC, LOG_LOSS,

    // Ranking metrics
    NDCG, MAP, MRR, SPEARMAN, PEARSON,

    // Reinforcement learning metrics
    EPISODE_RETURN, SUCCESS_RATE,

    // Other / custom metrics
    CUSTOM
}

/**
 * Only the model family category is specified here.
 * The concrete model selection is done by a later agent.
 */
enum class ModelFamilyCategory {
    LINEAR,             // Linear / generalized linear models
    TREE_BOOSTING,      // XGBoost/LightGBM/CatBoost-like
    MLP,                // Multi-layer perceptron
    CNN,                // Convolutional vision models
    VISION_TRANSFORMER, // ViT/DeiT/Swin-like
    SEQ_TRANSFORMER,    // Text/sequence transformers
    RNN,                // RNN/LSTM/GRU
    GNN,                // Graph neural networks
    DIFFUSION,          // Diffusion / generative family
    RL_POLICY,          // RL policy/value networks
    OTHER
}

class ModelAutoGenChecklist {

    enum class Field(val label: String, val required: Boolean) {
        INPUT_TYPE("Input type", true),
        OUTPUT_TYPE("Output type", true),
        TRAINING_TYPE("Training type", true),
        DATA_PATH("Data path", true),
        SPLIT_STRATEGY("Split strategy", true),
        METRIC("Metric", true),

        // Optional: only the category is needed; can be made required if you want.
        PREDICTION_DATA_PATH("Prediction data path", true),
        MODEL_FAMILY_CATEGORY("Model family category", false)
    }

    private var inputType: InputType? = null
    private var outputType: OutputType? = null
    private var trainingType: TrainingType? = null
    private var dataPath: Path? = null
    private var splitStrategy: SplitStrategy? = null
    private var splitCustomDesc: String? = null
    private var metric: Metric? = null
    private var metricCustomDesc: String? = null
    private var modelFamilyCategory: ModelFamilyCategory? = null
    private var predictionDataPath: Path? = null
    /**
     * Confirmed fields.
     * Whenever a field is updated, its confirmation is cleared and must be re-confirmed.
     */
    private val confirmed: MutableSet<Field> = mutableSetOf()

    // ---------------- Update interfaces ----------------
    fun setInputType(v: InputType) {
        inputType = v
        confirmed.remove(Field.INPUT_TYPE)
    }

    fun setOutputType(v: OutputType) {
        outputType = v
        confirmed.remove(Field.OUTPUT_TYPE)
    }

    fun setTrainingType(v: TrainingType) {
        trainingType = v
        confirmed.remove(Field.TRAINING_TYPE)
    }

    fun setDataPath(path: String) {
        dataPath = Paths.get(path)
        confirmed.remove(Field.DATA_PATH)
    }

    fun setSplitStrategy(v: SplitStrategy, customDesc: String? = null) {
        splitStrategy = v
        splitCustomDesc = customDesc
        confirmed.remove(Field.SPLIT_STRATEGY)
    }

    fun setMetric(v: Metric, customDesc: String? = null) {
        metric = v
        metricCustomDesc = customDesc
        confirmed.remove(Field.METRIC)
    }

    fun setModelFamilyCategory(v: ModelFamilyCategory) {
        modelFamilyCategory = v
        confirmed.remove(Field.MODEL_FAMILY_CATEGORY)
    }

    fun setPredictionDataPath(path: String) {
        predictionDataPath = Paths.get(path)
        confirmed.remove(Field.PREDICTION_DATA_PATH)
    }

    // ---------------- Confirm interfaces ----------------
    fun confirm(field: Field) {
        // A field must be filled before it can be confirmed.
        when (field) {
            Field.INPUT_TYPE -> requireNotNull(inputType) { "${field.label} is not set; cannot confirm" }
            Field.OUTPUT_TYPE -> requireNotNull(outputType) { "${field.label} is not set; cannot confirm" }
            Field.TRAINING_TYPE -> requireNotNull(trainingType) { "${field.label} is not set; cannot confirm" }
            Field.DATA_PATH -> requireNotNull(dataPath) { "${field.label} is not set; cannot confirm" }
            Field.SPLIT_STRATEGY -> {
                requireNotNull(splitStrategy) { "${field.label} is not set; cannot confirm" }
                if (splitStrategy == SplitStrategy.CUSTOM) {
                    require(!splitCustomDesc.isNullOrBlank()) { "SplitStrategy=CUSTOM requires customDesc" }
                }
            }
            Field.METRIC -> {
                requireNotNull(metric) { "${field.label} is not set; cannot confirm" }
                if (metric == Metric.CUSTOM) {
                    require(!metricCustomDesc.isNullOrBlank()) { "Metric=CUSTOM requires customDesc" }
                }
            }
            Field.MODEL_FAMILY_CATEGORY ->
                requireNotNull(modelFamilyCategory) { "${field.label} is not set; cannot confirm" }
            Field.PREDICTION_DATA_PATH ->
                requireNotNull(predictionDataPath) { "${field.label} is not set; cannot confirm" }
        }
        confirmed.add(field)
    }

    fun unconfirm(field: Field) {
        confirmed.remove(field)
    }

    // ---------------- Final check ----------------
    data class CheckResult(
        val ok: Boolean,
        val missing: List<Field>,
        val unconfirmed: List<Field>
    )

    fun check(): CheckResult {
        val missing = mutableListOf<Field>()
        val unconfirmed = mutableListOf<Field>()

        fun isMissing(field: Field): Boolean = when (field) {
            Field.INPUT_TYPE -> inputType == null
            Field.OUTPUT_TYPE -> outputType == null
            Field.TRAINING_TYPE -> trainingType == null
            Field.DATA_PATH -> dataPath == null
            Field.SPLIT_STRATEGY ->
                splitStrategy == null || (splitStrategy == SplitStrategy.CUSTOM && splitCustomDesc.isNullOrBlank())
            Field.METRIC ->
                metric == null || (metric == Metric.CUSTOM && metricCustomDesc.isNullOrBlank())
            Field.MODEL_FAMILY_CATEGORY -> modelFamilyCategory == null
            Field.PREDICTION_DATA_PATH -> predictionDataPath == null
        }

        // Collect missing required fields.
        for (f in Field.entries) {
            if (!f.required) continue
            if (isMissing(f)) missing += f
        }

        // Only require confirmation for required fields that are not missing.
        for (f in Field.entries) {
            if (!f.required) continue
            if (f !in missing && f !in confirmed) unconfirmed += f
        }

        return CheckResult(
            ok = missing.isEmpty() && unconfirmed.isEmpty(),
            missing = missing.distinct(),
            unconfirmed = unconfirmed.distinct()
        )
    }

    // Optional: human-readable snapshot for logging/debugging.
    fun snapshot(): String = buildString {
        appendLine("=== Checklist Snapshot ===")
        appendLine("Input type: $inputType (${status(Field.INPUT_TYPE)})")
        appendLine("Output type: $outputType (${status(Field.OUTPUT_TYPE)})")
        appendLine("Training type: $trainingType (${status(Field.TRAINING_TYPE)})")
        appendLine("Data path: $dataPath (${status(Field.DATA_PATH)})")
        appendLine(
            "Split strategy: $splitStrategy" +
                    (if (splitStrategy == SplitStrategy.CUSTOM) "($splitCustomDesc)" else "") +
                    " (${status(Field.SPLIT_STRATEGY)})"
        )
        appendLine(
            "Metric: $metric" +
                    (if (metric == Metric.CUSTOM) "($metricCustomDesc)" else "") +
                    " (${status(Field.METRIC)})"
        )
        appendLine("Model family category (optional): $modelFamilyCategory (${status(Field.MODEL_FAMILY_CATEGORY)})")
        appendLine("Prediction data path (optional): $predictionDataPath (${status(Field.PREDICTION_DATA_PATH)})")
    }

    private fun status(field: Field): String {
        val filled = when (field) {
            Field.INPUT_TYPE -> inputType != null
            Field.OUTPUT_TYPE -> outputType != null
            Field.TRAINING_TYPE -> trainingType != null
            Field.DATA_PATH -> dataPath != null
            Field.SPLIT_STRATEGY ->
                splitStrategy != null && !(splitStrategy == SplitStrategy.CUSTOM && splitCustomDesc.isNullOrBlank())
            Field.METRIC ->
                metric != null && !(metric == Metric.CUSTOM && metricCustomDesc.isNullOrBlank())
            Field.MODEL_FAMILY_CATEGORY -> modelFamilyCategory != null
            Field.PREDICTION_DATA_PATH -> predictionDataPath != null
        }
        return when {
            !filled -> "Not set"
            field in confirmed -> "Confirmed"
            else -> "Not confirmed"
        }
    }
}