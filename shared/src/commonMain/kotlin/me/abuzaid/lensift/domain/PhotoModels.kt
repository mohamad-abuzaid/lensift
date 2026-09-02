package me.abuzaid.lensift.domain

data class AssetId(val value: String) {
    init {
        require(value.isNotBlank()) { "Asset ID must not be blank" }
    }
}

data class PhotoDescriptor(
    val id: AssetId,
    val contentSignature: String,
    val width: Int,
    val height: Int,
    val byteCount: Long?,
    val capturedAtEpochMillis: Long?,
    val isFavorite: Boolean,
    val isEdited: Boolean,
) {
    init {
        require(width > 0 && height > 0) { "Photo dimensions must be positive" }
        require(byteCount == null || byteCount >= 0) { "Photo byte count must not be negative" }
    }
}

/** A grayscale frame. Input and output buffers are copied at the boundary. */
class LumaFrame(width: Int, height: Int, pixels: ByteArray) {
    init {
        require(width > 0 && height > 0) { "Frame dimensions must be positive" }
        require(width.toLong() * height.toLong() == pixels.size.toLong()) {
            "Luma buffer must contain exactly width * height bytes"
        }
    }

    val width: Int = width
    val height: Int = height
    private val pixelData: ByteArray = pixels.copyOf()
    val pixels: ByteArray
        get() = pixelData.copyOf()
}

enum class EvidenceReason {
    IdenticalContent,
    VisuallySimilar,
    LowSharpnessSignals,
}

data class ExactDuplicate(
    val assetIds: List<AssetId>,
    val reason: EvidenceReason = EvidenceReason.IdenticalContent,
)

data class NearDuplicate(
    val assetIds: List<AssetId>,
    val reason: EvidenceReason = EvidenceReason.VisuallySimilar,
)

data class BlurCandidate(
    val assetId: AssetId,
    val reason: EvidenceReason = EvidenceReason.LowSharpnessSignals,
)
