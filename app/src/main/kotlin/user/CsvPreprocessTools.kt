package org.example.app.user

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.serialization.Serializable

@LLMDescription("CSV preprocessing via Python (pandas). Use scan first, then fill with user-provided defaults.")
class CsvPreprocessTools : ToolSet {
    @Serializable
    @LLMDescription("Default fill value for a column")
    data class DefaultKV(
        @LLMDescription("Column name in the CSV") val column: String,
        @LLMDescription("Default value as string") val value: String
    )
    private fun runPython(script: String, args: List<String>): String {
        val cmd = mutableListOf("python3", "-c", script).apply { addAll(args) }
        val pb = ProcessBuilder(cmd)
            .redirectErrorStream(true)

        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        if (code != 0) {
            throw IllegalStateException(
                "Python failed (exit=$code). Output:\n$out\n" +
                        "Make sure python3 + pandas are installed (pip install pandas)."
            )
        }
        return out.trim()
    }

    @Tool
    @LLMDescription("Scan CSV for nulls; returns JSON with rowCount, columns (name, dtype, nulls).")
    fun scanCsvNulls(
        @LLMDescription("Path to a CSV file") path: String
    ): String {
        val py = """
import json, sys
import pandas as pd

path = sys.argv[1]
df = pd.read_csv(path)
info = {
  "path": path,
  "rowCount": int(len(df)),
  "columns": []
}
for col in df.columns:
  s = df[col]
  nulls = int(s.isna().sum())
  dtype = str(s.dtype)
  info["columns"].append({"name": str(col), "dtype": dtype, "nulls": nulls})
print(json.dumps(info, ensure_ascii=False))
""".trimIndent()

        return runPython(py, listOf(path))
    }

    @Tool
    @LLMDescription(
        "Fill nulls in CSV using defaults and save to a new CSV. " +
                "defaults maps column -> default string. Returns output path."
    )
    fun fillCsvNulls(
        @LLMDescription("Path to input CSV") path: String,
        @LLMDescription("List of defaults: [{column:\"col1\", value:\"0\"}, ...]")
        defaults: List<DefaultKV>,
        @LLMDescription("Optional output path; if empty, auto-generate next to input")
        outputPath: String? = null
    ): String {
        val py = """
import json, os, sys
import pandas as pd

path = sys.argv[1]
defaults_json = sys.argv[2]
out_path = sys.argv[3] if len(sys.argv) > 3 and sys.argv[3] else None

pairs = json.loads(defaults_json)  # [{column:..., value:...}, ...]
df = pd.read_csv(path)

for item in pairs:
    col = item["column"]
    raw = item["value"]
    if col not in df.columns:
        continue
    s = df[col]
    if pd.api.types.is_numeric_dtype(s):
        try:
            val = float(raw)
        except Exception:
            val = 0.0
        df[col] = s.fillna(val)
    elif pd.api.types.is_bool_dtype(s):
        v = str(raw).strip().lower() in ("1","true","t","yes","y")
        df[col] = s.fillna(v)
    else:
        df[col] = s.fillna(str(raw))

if not out_path:
    base, ext = os.path.splitext(path)
    out_path = base + "_preprocessed.csv"

df.to_csv(out_path, index=False)
print(out_path)
""".trimIndent()

        val defaultsJson = kotlinx.serialization.json.Json.encodeToString(defaults)

        return runPython(py, listOf(path, defaultsJson, outputPath ?: ""))
    }
}
