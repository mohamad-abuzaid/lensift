package me.abuzaid.lensift.scan

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

data class LensiftDispatchers(
    val computation: CoroutineDispatcher = Dispatchers.Default,
    val database: CoroutineDispatcher = Dispatchers.Default,
)
