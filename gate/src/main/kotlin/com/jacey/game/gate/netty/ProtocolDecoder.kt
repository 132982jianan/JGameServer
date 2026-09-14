package com.jacey.game.gate.netty

import com.jacey.game.common.msg.NetMessage
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder

/** TCP 自定义解码器：处理粘包/拆包 */
class ProtocolDecoder : ByteToMessageDecoder() {
    override fun decode(ctx: ChannelHandlerContext, buf: ByteBuf, out: MutableList<Any>) {
        // 客户端心跳是裸字符串 "hb_request"（非 12 字节帧），直接剥离
        if (buf.readableBytes() >= NettyServer.HEARTBEAT_BYTES.size) {
            buf.markReaderIndex()
            val possible = ByteArray(NettyServer.HEARTBEAT_BYTES.size)
            buf.getBytes(buf.readerIndex(), possible)
            if (possible.contentEquals(NettyServer.HEARTBEAT_BYTES)) {
                buf.skipBytes(NettyServer.HEARTBEAT_BYTES.size)
                return
            }
            buf.resetReaderIndex()
        }
        val readable = buf.readableBytes()
        if (readable < NettyServer.HEADER_LENGTH) return
        buf.markReaderIndex()
        val totalLength = buf.readInt()
        if (readable >= totalLength) {
            val msgId = buf.readInt()
            val errorCode = buf.readInt()
            val bytes = ByteArray(totalLength - NettyServer.HEADER_LENGTH)
            buf.readBytes(bytes)
            val message = NetMessage(msgId, bytes)
            message.errorCode = errorCode
            out.add(message)
        } else {
            buf.resetReaderIndex()
        }
    }
}