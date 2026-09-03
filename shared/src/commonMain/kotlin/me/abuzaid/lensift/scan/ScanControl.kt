package me.abuzaid.lensift.scan

import me.abuzaid.lensift.domain.AnalysisPolicy

interface ScanControl {
    fun start(policy: AnalysisPolicy)

    fun pause()

    fun resume()

    fun cancel()
}
