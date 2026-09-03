package me.abuzaid.lensift.scan

import me.abuzaid.lensift.analysis.BlurAnalyzer
import me.abuzaid.lensift.analysis.BlurEvidence
import me.abuzaid.lensift.analysis.PerceptualHash
import me.abuzaid.lensift.domain.AnalysisPolicy
import me.abuzaid.lensift.domain.LumaFrame
import me.abuzaid.lensift.domain.PhotoDescriptor
import me.abuzaid.lensift.index.AnalysisRecord

class AssetAnalyzer(
    private val nowEpochMillis: () -> Long,
    private val perceptualHash: (LumaFrame) -> Long = PerceptualHash::compute,
    private val blurAnalysis: (LumaFrame, AnalysisPolicy) -> BlurEvidence = BlurAnalyzer::analyze,
) {
    fun analyze(
        descriptor: PhotoDescriptor,
        frame: LumaFrame,
        policy: AnalysisPolicy,
        analyzerVersion: Int,
    ): AnalysisRecord = AnalysisRecord(
        descriptor = descriptor,
        analyzerVersion = analyzerVersion,
        perceptualHash = perceptualHash(frame),
        sha256 = null,
        blurEvidence = blurAnalysis(frame, policy),
        analyzedAtEpochMillis = nowEpochMillis(),
    )
}
