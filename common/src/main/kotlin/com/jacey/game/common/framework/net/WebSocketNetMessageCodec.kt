package com.jacey.game.common.framework.net

import com.jacey.game.common.msg.NetMessage
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.CorruptedFrameException
import io.netty.handler.codec.MessageToMessageCodec
import io.netty.handler.codec.TooLongFrameException
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketFrame

/** 每个完整二进制帧承载一个 packetLength | msgId | errorCode | protobuf 消息。 */
class WebSocketNetMessageCodec : MessageToMessageCodec<WebSocketFrame, NetMessage>() {
    override fun encode(ctx: ChannelHandlerContext, msg: NetMessage, out: MutableList<Any>) {
        if (msg.totalLength > WebSocketProtocol.MAX_FRAME_LENGTH) {
            throw TooLongFrameException("game message exceeds WebSocket frame limit")
        }
        // 帧接管 ByteBuf 的所有权，交给 Netty 发送后释放。
        out.add(BinaryWebSocketFrame(msg.toByteBuf()))
    }

    override fun decode(ctx: ChannelHandlerContext, frame: WebSocketFrame, out: MutableList<Any>) {
        if (frame !is BinaryWebSocketFrame || !frame.isFinalFragment) {
            throw CorruptedFrameException("expected an aggregated binary WebSocket message")
        }
        val content = frame.content()
        val size = content.readableBytes()
        if (size < NetMessage.HEADER_LENGTH || size > WebSocketProtocol.MAX_FRAME_LENGTH) {
            throw CorruptedFrameException("invalid game message length: $size")
        }
        val totalLength = content.readInt()
        if (totalLength != size) {
            throw CorruptedFrameException("game message length $totalLength does not match frame length $size")
        }
        val msgId = content.readInt()
        val errorCode = content.readInt()
        val data = ByteArray(content.readableBytes())
        content.readBytes(data)
        out.add(NetMessage(msgId, data).also { it.errorCode = errorCode })
        // MessageToMessageCodec 自动释放入站帧。
    }
}
