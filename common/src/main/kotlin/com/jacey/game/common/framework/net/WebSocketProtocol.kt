package com.jacey.game.common.framework.net

import java.net.URI

/** 客户端与 Gate 共用的 WebSocket 协议参数。 */
object WebSocketProtocol {
    const val PATH = "/websocket"
    const val MAX_FRAME_LENGTH = 65536
    const val HANDSHAKE_TIMEOUT_MS = 5000L

    fun endpoint(host: String, port: Int): String =
        URI("ws", null, host, port, PATH, null, null).toASCIIString()
}
