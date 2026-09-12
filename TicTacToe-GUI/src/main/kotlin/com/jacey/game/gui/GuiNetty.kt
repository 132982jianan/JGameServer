package com.jacey.game.gui

import com.jacey.game.common.msg.NetMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.MessageToByteEncoder
import io.netty.handler.timeout.IdleState
import io.netty.handler.timeout.IdleStateEvent
import io.netty.util.CharsetUtil

/**
 * GUI 客户端 Netty 管道（与服务端同款线协议）
 *
 * packetLength | rpcNum | errorCode | protobuf body
 */
object GuiNetty {
    private val logger = KotlinLogging.logger {}
    private const val HEADER_LENGTH = 12

    class ProtocolDecoder : ByteToMessageDecoder() {
        override fun decode(ctx: ChannelHandlerContext, buf: io.netty.buffer.ByteBuf, out: MutableList<Any>) {
            val readable = buf.readableBytes()
            if (readable < HEADER_LENGTH) return
            buf.markReaderIndex()
            val totalLength = buf.readInt()
            if (readable >= totalLength) {
                val rpcNum = buf.readInt()
                val errorCode = buf.readInt()
                val bytes = ByteArray(totalLength - HEADER_LENGTH)
                buf.readBytes(bytes)
                val message = NetMessage(rpcNum, bytes)
                message.errorCode = errorCode
                out.add(message)
            } else {
                buf.resetReaderIndex()
            }
        }
    }

    class ProtocolEncoder : MessageToByteEncoder<NetMessage>() {
        override fun encode(ctx: ChannelHandlerContext, msg: NetMessage, out: io.netty.buffer.ByteBuf) {
            out.writeBytes(msg.toBinaryMsg())
        }
    }

    class ClientHandler : ChannelInboundHandlerAdapter() {
        private val HEARTBEAT = Unpooled.unreleasableBuffer(
            Unpooled.copiedBuffer("hb_request", CharsetUtil.UTF_8))

        override fun channelActive(ctx: ChannelHandlerContext) {
            logger.info { "已连接服务器......" }
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            logger.warn { "与服务器已断开......" }
            ServerConnection.channel = null
        }

        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            if (msg is NetMessage) {
                MessageRouter.dispatch(msg)
            }
        }

        override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
            if (evt is IdleStateEvent && evt.state() == IdleState.WRITER_IDLE) {
                ctx.writeAndFlush(HEARTBEAT.duplicate())
                logger.info { "【心跳】发送心跳包结束...." }
            }
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            ctx.close()
            val msg = cause.message ?: ""
            if (!msg.contains("远程主机强迫关闭")) {
                logger.error(cause) { "【链接异常断开】" }
            }
        }
    }
}
