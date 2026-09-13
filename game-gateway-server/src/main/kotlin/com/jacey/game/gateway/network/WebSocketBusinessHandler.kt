package com.jacey.game.gateway.network

import com.jacey.game.common.msg.NetMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame

/** WebSocket 帧适配处理 */
class WebSocketBusinessHandler : AbsBusinessHandler() {
    private val logger = KotlinLogging.logger {}

    override fun channelActive(ctx: ChannelHandlerContext) {
        NettyServer.onChannelActive(ctx)
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        when (msg) {
            is TextWebSocketFrame -> {
                logger.warn { "text frame not supported" }
            }

            is PingWebSocketFrame -> {
                ctx.channel().write(PongWebSocketFrame(msg.content().retain()))
            }

            is BinaryWebSocketFrame -> {
                // 转成 ByteBuf 走 NetMessage 解码（frame.content 已是解包后的 body？不——WS 也走同 codec，
                // 由 WebSocketServerProtocolHandler 解出 BinaryWebSocketFrame，其 content 是完整 protobuf 包体）
                val buf = msg.content()
                val netMessage = decodeFull(buf)
                if (netMessage != null) {
                    super.channelRead(ctx, netMessage)
                }
            }

            is ContinuationWebSocketFrame -> {
                logger.warn { "continuation frame not supported" }
            }

            else -> ctx.fireChannelRead(msg)
        }
    }

    override fun getClientSessionActorPrefix(): String {
        return "ws-"
    }

    private fun decodeFull(buf: ByteBuf): NetMessage? {
        if (buf.readableBytes() < NettyServer.HEADER_LENGTH) return null
        val totalLength = buf.readInt()
        val rpcNum = buf.readInt()
        val errorCode = buf.readInt()
        val bytes = ByteArray(totalLength - NettyServer.HEADER_LENGTH)
        buf.readBytes(bytes)
        return NetMessage(rpcNum, bytes).also { it.errorCode = errorCode }
    }
}