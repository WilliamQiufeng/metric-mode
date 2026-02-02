package org.example.app.intermediate

// ChecklistSmokeTest.kt

fun main() {
    fun assertThat(cond: Boolean, msg: String) {
        if (!cond) throw IllegalStateException("Assertion failed: $msg")
    }

    fun show(title: String, r: ModelAutoGenChecklist.CheckResult) {
        println("---- $title ----")
        println("ok = ${r.ok}")
        println("missing = ${r.missing.map { it.label }}")
        println("unconfirmed = ${r.unconfirmed.map { it.label }}")
        println()
    }

    // 1) Empty checklist => missing should include all required fields
    run {
        val cl = ModelAutoGenChecklist()
        val r = cl.check()
        show("Case 1: empty", r)

        assertThat(!r.ok, "Empty checklist should not be ok")
        assertThat(r.missing.isNotEmpty(), "Empty checklist should have missing fields")
        assertThat(r.unconfirmed.isEmpty(), "Missing fields should not be counted as unconfirmed")
    }

    // 2) Set required values but do not confirm => unconfirmed should contain required fields
    run {
        val cl = ModelAutoGenChecklist()
        cl.setInputType(InputType.IMAGE)
        cl.setOutputType(OutputType.CLASSIFICATION)
        cl.setTrainingType(TrainingType.SUPERVISED)
        cl.setDataPath("/data/projectA")
        cl.setSplitStrategy(SplitStrategy.STRATIFIED)
        cl.setMetric(Metric.F1)

        val r = cl.check()
        show("Case 2: set but not confirmed", r)

        assertThat(!r.ok, "Not confirmed should not be ok")
        assertThat(r.missing.isEmpty(), "All required fields are set, missing should be empty")
        assertThat(r.unconfirmed.size == 6, "All 6 required fields should be unconfirmed")
    }

    // 3) Confirm all required fields => ok should be true
    run {
        val cl = ModelAutoGenChecklist()
        cl.setInputType(InputType.IMAGE)
        cl.setOutputType(OutputType.CLASSIFICATION)
        cl.setTrainingType(TrainingType.SUPERVISED)
        cl.setDataPath("/data/projectA")
        cl.setSplitStrategy(SplitStrategy.STRATIFIED)
        cl.setMetric(Metric.F1)

        cl.confirm(ModelAutoGenChecklist.Field.INPUT_TYPE)
        cl.confirm(ModelAutoGenChecklist.Field.OUTPUT_TYPE)
        cl.confirm(ModelAutoGenChecklist.Field.TRAINING_TYPE)
        cl.confirm(ModelAutoGenChecklist.Field.DATA_PATH)
        cl.confirm(ModelAutoGenChecklist.Field.SPLIT_STRATEGY)
        cl.confirm(ModelAutoGenChecklist.Field.METRIC)

        val r = cl.check()
        show("Case 3: set + confirmed", r)

        assertThat(r.ok, "All required fields confirmed should be ok")
        assertThat(r.missing.isEmpty(), "No missing when ok")
        assertThat(r.unconfirmed.isEmpty(), "No unconfirmed when ok")

        println(cl.snapshot())
    }

    // 4) After confirming, update one field => it should become "Not confirmed" again
    run {
        val cl = ModelAutoGenChecklist()
        cl.setInputType(InputType.IMAGE)
        cl.setOutputType(OutputType.CLASSIFICATION)
        cl.setTrainingType(TrainingType.SUPERVISED)
        cl.setDataPath("/data/projectA")
        cl.setSplitStrategy(SplitStrategy.STRATIFIED)
        cl.setMetric(Metric.F1)

        ModelAutoGenChecklist.Field.entries
            .filter { it.required }
            .forEach { cl.confirm(it) }

        // Update metric after confirm => confirmation should be cleared for METRIC
        cl.setMetric(Metric.ACCURACY)

        val r = cl.check()
        show("Case 4: update after confirm", r)

        assertThat(!r.ok, "After update, should not be ok until re-confirmed")
        assertThat(r.missing.isEmpty(), "Still set, so missing should be empty")
        assertThat(
            r.unconfirmed == listOf(ModelAutoGenChecklist.Field.METRIC),
            "Only METRIC should become unconfirmed after update"
        )

        // Re-confirm => ok again
        cl.confirm(ModelAutoGenChecklist.Field.METRIC)
        val r2 = cl.check()
        show("Case 4b: re-confirm after update", r2)
        assertThat(r2.ok, "Re-confirm should restore ok")
    }

    // 5) CUSTOM split strategy without description => treated as missing
    run {
        val cl = ModelAutoGenChecklist()
        cl.setInputType(InputType.TABULAR)
        cl.setOutputType(OutputType.REGRESSION)
        cl.setTrainingType(TrainingType.SUPERVISED)
        cl.setDataPath("/data/tabular")
        cl.setSplitStrategy(SplitStrategy.CUSTOM) // no desc
        cl.setMetric(Metric.RMSE)

        val r = cl.check()
        show("Case 5: CUSTOM split without desc", r)

        assertThat(!r.ok, "CUSTOM split without desc should not be ok")
        assertThat(
            ModelAutoGenChecklist.Field.SPLIT_STRATEGY in r.missing,
            "SPLIT_STRATEGY should be missing when CUSTOM has no description"
        )
    }

    // 6) CUSTOM metric without description => treated as missing
    run {
        val cl = ModelAutoGenChecklist()
        cl.setInputType(InputType.IMAGE)
        cl.setOutputType(OutputType.CLASSIFICATION)
        cl.setTrainingType(TrainingType.SUPERVISED)
        cl.setDataPath("/data/img")
        cl.setSplitStrategy(SplitStrategy.RANDOM)
        cl.setMetric(Metric.CUSTOM) // no desc

        val r = cl.check()
        show("Case 6: CUSTOM metric without desc", r)

        assertThat(!r.ok, "CUSTOM metric without desc should not be ok")
        assertThat(
            ModelAutoGenChecklist.Field.METRIC in r.missing,
            "METRIC should be missing when CUSTOM has no description"
        )
    }

    println("✅ All smoke tests passed.")
}