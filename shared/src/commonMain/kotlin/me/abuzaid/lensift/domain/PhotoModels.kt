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

class ExactDuplicate(assetIds: List<AssetId>) {
    val assetIds: List<AssetId> = ownedAssetIds(assetIds)
    val reason: EvidenceReason get() = EvidenceReason.IdenticalContent

    init {
        require(this.assetIds.size >= 2 && this.assetIds.toSet().size == this.assetIds.size) {
            "Exact duplicate evidence requires at least two distinct assets"
        }
    }

    override fun equals(other: Any?): Boolean = other is ExactDuplicate && assetIds == other.assetIds

    override fun hashCode(): Int = assetIds.hashCode()

    override fun toString(): String = "ExactDuplicate(assetIds=$assetIds, reason=$reason)"
}

class NearDuplicate(assetIds: List<AssetId>) {
    val assetIds: List<AssetId> = ownedAssetIds(assetIds)
    val reason: EvidenceReason get() = EvidenceReason.VisuallySimilar

    init {
        require(this.assetIds.size >= 2 && this.assetIds.toSet().size == this.assetIds.size) {
            "Near duplicate evidence requires at least two distinct assets"
        }
    }

    override fun equals(other: Any?): Boolean = other is NearDuplicate && assetIds == other.assetIds

    override fun hashCode(): Int = assetIds.hashCode()

    override fun toString(): String = "NearDuplicate(assetIds=$assetIds, reason=$reason)"
}

data class BlurCandidate(val assetId: AssetId) {
    val reason: EvidenceReason get() = EvidenceReason.LowSharpnessSignals
}

private fun ownedAssetIds(assetIds: List<AssetId>): List<AssetId> = AssetIdList(assetIds.toTypedArray())

/** A List implementation that never exposes the caller-owned mutable source. */
private class AssetIdList(private val values: Array<AssetId>) : AbstractList<AssetId>() {
    override val size: Int get() = values.size

    override fun get(index: Int): AssetId = values[index]
}
