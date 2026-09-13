package com.jacey.game.common.framework.net

import com.jacey.game.common.framework.nacos.Config
import kotlinx.serialization.Serializable

@Serializable
data class AkkaSettings(
    val loglevel: String = "INFO",
    val logDeadLetters: Int = 10,
) : Config