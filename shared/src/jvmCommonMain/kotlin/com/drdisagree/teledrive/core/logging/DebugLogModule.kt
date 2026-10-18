package com.drdisagree.teledrive.core.logging

import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val debugLogModule = module {
    singleOf(::DebugLogRecorder)
    singleOf(::DebugLogController)
}
