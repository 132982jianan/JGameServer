package com.jacey.game.common.framework.net

import kotlinx.serialization.Serializable

/** 节点实例 ID；零开销强类型包装，避免与 PlayerId、端口等 Int 混用。 */
@Serializable
@JvmInline
value class NodeId(val int: Int) {
    override fun toString(): String = int.toString()
}
