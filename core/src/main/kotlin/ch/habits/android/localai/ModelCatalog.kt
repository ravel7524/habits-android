package ch.habits.android.localai

data class ModelDescriptor(
    val id: String,
    val label: String,
    val url: String,
    val fileName: String,
    val size: Long,
    val sha256: String,
    val licenseURL: String,
    val minRamBytes: Long = 2L * 1024 * 1024 * 1024,
    val reasoning: Boolean = false,
) {
    fun validateFileMetadata() {
        require(Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.gguf").matches(fileName) && !fileName.contains(".."))
        require(size in 4..8L * 1024 * 1024 * 1024)
        require(Regex("[0-9a-f]{64}").matches(sha256))
        require(id.isNotBlank() && id.length <= 100)
        require(minRamBytes > 0)
    }
}

/** Author Hub API + LICENSE verified 2026-10-05; no floating main revision. */
object ModelCatalog {
    val fast = ModelDescriptor(
        id = "lfm2.5-230m-qad-q4_0",
        label = "LFM2.5 230M Q4",
        url = "https://huggingface.co/LiquidAI/LFM2.5-230M-GGUF/resolve/cdf97bd8205908758f44aec508d68ac1aef98f5c/LFM2.5-230M-QAD-Q4_0.gguf?download=true",
        fileName = "LFM2.5-230M-QAD-Q4_0.gguf",
        size = 149_081_056L,
        sha256 = "e75f83268de11b2a1bcfab5f3b5c5c0c97569ddbbc0990aad88437e45b8ba292",
        licenseURL = "https://huggingface.co/LiquidAI/LFM2.5-230M-GGUF/blob/cdf97bd8205908758f44aec508d68ac1aef98f5c/LICENSE",
        minRamBytes = 2_147_483_648L,
    )
    val balanced = ModelDescriptor(
        id = "lfm2.5-2.6b-qad-q4_0",
        label = "LFM2.5 2.6B Q4 (Reasoning)",
        url = "https://huggingface.co/LiquidAI/LFM2.5-2.6B-GGUF/resolve/84022ce711b28455e8c4fc364ce68c00cf995875/LFM2.5-2.6B-QAD-Q4_0.gguf?download=true",
        fileName = "LFM2.5-2.6B-QAD-Q4_0.gguf",
        size = 1_593_894_944L,
        sha256 = "a247afd6414918eac8e520a9e6137dc271235461ecbe1180462221d5b8d40b03",
        licenseURL = "https://huggingface.co/LiquidAI/LFM2.5-2.6B-GGUF/blob/84022ce711b28455e8c4fc364ce68c00cf995875/LICENSE",
        minRamBytes = 5_368_709_120L,
        reasoning = true,
    )
    val starter = fast
    val models: List<ModelDescriptor> = listOf(fast, balanced)
}
