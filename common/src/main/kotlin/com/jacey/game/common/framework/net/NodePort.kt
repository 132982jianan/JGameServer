package com.jacey.game.common.framework.net

import kotlinx.serialization.Serializable

@Serializable
data class NodePort(
    /** akka artery 端口（节点间通信） */
    val artery: Int = 0,
    /** 客户端 WebSocket 端口（Gate） */
    val ws: Int = 0,
    /** 对外 HTTP 端口（gm） */
    val http: Int = 0,
)
