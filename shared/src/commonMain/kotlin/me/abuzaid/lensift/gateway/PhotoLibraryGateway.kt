package me.abuzaid.lensift.gateway

import kotlinx.coroutines.flow.Flow
import me.abuzaid.lensift.domain.AssetId
import me.abuzaid.lensift.domain.LumaFrame
import me.abuzaid.lensift.domain.PhotoDescriptor

enum class AccessState { Full, Partial, Denied, Restricted, NotDetermined }

sealed interface LibraryChange {
    class Changed(assetIds: Set<AssetId>) : LibraryChange {
        private val ownedAssetIds = assetIds.toSet()

        /** A snapshot, so platform-owned mutable collections never cross this boundary. */
        val assetIds: Set<AssetId>
            get() = ownedAssetIds.toSet()

        override fun equals(other: Any?): Boolean = other is Changed && ownedAssetIds == other.ownedAssetIds

        override fun hashCode(): Int = ownedAssetIds.hashCode()

        override fun toString(): String = "Changed(assetIds=$ownedAssetIds)"
    }

    data object AccessMayHaveChanged : LibraryChange
}

/**
 * Value-only shared boundary for the device photo library.
 *
 * Platform adapters retain URIs, PhotoKit objects, cursors, and decoding resources internally.
 */
interface PhotoLibraryGateway {
    suspend fun currentAccess(): AccessState

    fun enumerateAccessibleImages(): Flow<PhotoDescriptor>

    suspend fun decodeLuma(assetId: AssetId, targetLongestEdge: Int = 512): LumaFrame

    fun originalByteChunks(assetId: AssetId): Flow<ByteArray>

    fun observeChanges(): Flow<LibraryChange>
}
