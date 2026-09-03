package me.abuzaid.lensift.test

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import me.abuzaid.lensift.domain.AssetId
import me.abuzaid.lensift.domain.LumaFrame
import me.abuzaid.lensift.domain.PhotoDescriptor
import me.abuzaid.lensift.gateway.AccessState
import me.abuzaid.lensift.gateway.LibraryChange
import me.abuzaid.lensift.gateway.PhotoLibraryGateway

class FakePhotoLibraryGateway(
    var access: AccessState = AccessState.Full,
    descriptors: List<PhotoDescriptor> = emptyList(),
) : PhotoLibraryGateway {
    var descriptors: List<PhotoDescriptor> = descriptors.toList()
    var enumerationFailure: Throwable? = null
    var decodeBehavior: suspend (AssetId) -> LumaFrame = { assetId ->
        val value = assetId.value.hashCode().toByte()
        LumaFrame(1, 1, byteArrayOf(value))
    }
    var originalBehavior: (AssetId) -> Flow<ByteArray> = { assetId ->
        flowOf(assetId.value.encodeToByteArray())
    }

    var accessCalls: Int = 0
        private set
    var decodeCalls: Int = 0
        private set
    var originalCalls: Int = 0
        private set
    var activeDecodes: Int = 0
        private set
    var maximumActiveDecodes: Int = 0
        private set
    var activeOriginalStreams: Int = 0
        private set
    var maximumActiveOriginalStreams: Int = 0
        private set

    private val changes = MutableSharedFlow<LibraryChange>(extraBufferCapacity = 4)

    override suspend fun currentAccess(): AccessState {
        accessCalls += 1
        return access
    }

    override fun enumerateAccessibleImages(): Flow<PhotoDescriptor> = flow {
        enumerationFailure?.let { throw it }
        descriptors.forEach { emit(it) }
    }

    override suspend fun decodeLuma(assetId: AssetId, targetLongestEdge: Int): LumaFrame {
        check(targetLongestEdge == 512)
        decodeCalls += 1
        activeDecodes += 1
        maximumActiveDecodes = maxOf(maximumActiveDecodes, activeDecodes)
        return try {
            decodeBehavior(assetId)
        } finally {
            activeDecodes -= 1
        }
    }

    override fun originalByteChunks(assetId: AssetId): Flow<ByteArray> = flow {
        originalCalls += 1
        activeOriginalStreams += 1
        maximumActiveOriginalStreams = maxOf(maximumActiveOriginalStreams, activeOriginalStreams)
        try {
            originalBehavior(assetId).collect { emit(it.copyOf()) }
        } finally {
            activeOriginalStreams -= 1
        }
    }

    override fun observeChanges(): Flow<LibraryChange> = changes

    fun emitChange(change: LibraryChange) {
        changes.tryEmit(change)
    }

    fun clearReadCounts() {
        decodeCalls = 0
        originalCalls = 0
        maximumActiveDecodes = 0
        maximumActiveOriginalStreams = 0
    }
}
