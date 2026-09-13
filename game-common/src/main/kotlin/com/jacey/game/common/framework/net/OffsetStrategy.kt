package com.jacey.game.common.framework.net

import com.jacey.game.common.framework.nacos.Config
import kotlinx.serialization.Serializable

@Serializable
data class OffsetStrategy(
    val value: Int? = null,
    val envVar: String? = null,
) : Config {
    fun resolve(): Int {
        value?.let {
            return it
        }

        envVar?.let { v ->
            System.getenv(v)?.let {
                return it.toIntOrNull() ?: 0
            }
        }

        return 0
    }
}