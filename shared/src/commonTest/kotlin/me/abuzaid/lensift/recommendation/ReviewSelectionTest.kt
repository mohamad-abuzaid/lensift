package me.abuzaid.lensift.recommendation

import me.abuzaid.lensift.domain.AssetId
import me.abuzaid.lensift.domain.BlurCandidate
import me.abuzaid.lensift.domain.ExactDuplicate
import me.abuzaid.lensift.domain.NearDuplicate
import me.abuzaid.lensift.domain.PhotoDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewSelectionTest {
    @Test
    fun exactDuplicatesPreselectOnlyNonFavoriteNonKeeperAssets() {
        val keeper = photo("keeper")
        val redundant = photo("redundant")
        val favorite = photo("favorite", favorite = true)
        val finding = ExactDuplicate(listOf(keeper.id, redundant.id, favorite.id))

        assertEquals(
            setOf(redundant.id),
            ReviewSelection.initialFor(
                finding,
                keeper.id,
                mapOf(keeper.id to keeper, redundant.id to redundant, favorite.id to favorite),
            ),
        )
    }

    @Test
    fun exactDuplicatesDoNotPreselectAssetsWithoutFavoriteMetadata() {
        val keeper = photo("keeper")
        val unknown = AssetId("unknown")
        val finding = ExactDuplicate(listOf(keeper.id, unknown))

        assertEquals(
            emptySet(),
            ReviewSelection.initialFor(finding, keeper.id, mapOf(keeper.id to keeper)),
        )
    }

    @Test
    fun nearDuplicatesNeverPreselectRemoval() {
        assertEquals(
            emptySet(),
            ReviewSelection.initialFor(NearDuplicate(listOf(AssetId("first"), AssetId("second")))),
        )
    }

    @Test
    fun blurFindingsNeverPreselectRemoval() {
        assertEquals(emptySet(), ReviewSelection.initialFor(BlurCandidate(AssetId("blurred"))))
    }

    private fun photo(id: String, favorite: Boolean = false): PhotoDescriptor = PhotoDescriptor(
        id = AssetId(id),
        contentSignature = "signature-$id",
        width = 1_000,
        height = 1_000,
        byteCount = null,
        capturedAtEpochMillis = null,
        isFavorite = favorite,
        isEdited = false,
    )
}
