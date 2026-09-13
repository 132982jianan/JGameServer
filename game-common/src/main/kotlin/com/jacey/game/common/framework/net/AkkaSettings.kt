package com.jacey.game.common.framework.net

import com.jacey.game.common.framework.nacos.IConfig
import kotlinx.serialization.Serializable

@Serializable
data class AkkaSettings(
    val loglevel: String = "INFO",
    val logDeadLetters: Int = 10,
) : IConfig