package com.jacey.game.common

import com.jacey.game.common.framework.net.WebSocketNetMessageCodec
import com.jacey.game.common.framework.net.WebSocketProtocol
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.proto3.Rpc
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.CorruptedFrameException
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebSocketNetMessageCodecTest {
    @Test
    fun `binary frame preserves game header payload and releases input`() {
        val channel = EmbeddedChannel(WebSocketNetMessageCodec())
        try {
            val message = NetMessage(Rpc.RpcNameEnum.Heartbeat_VALUE, byteArrayOf(1, 2, 3))
                .also { it.errorCode = Rpc.RpcErrorCodeEnum.ClientError_VALUE }
            assertTrue(channel.writeOutbound(message))
            val frame = channel.readOutbound<BinaryWebSocketFrame>()
            assertEquals(15, frame.content().getInt(0))
            assertEquals(message.msgId, frame.content().getInt(4))
            assertEquals(message.errorCode, frame.content().getInt(8))
            assertTrue(channel.writeInbound(frame))
            val decoded = channel.readInbound<NetMessage>()
            assertEquals(message.msgId, decoded.msgId)
            assertEquals(message.errorCode, decoded.errorCode)
            assertContentEquals(message.data, decoded.data)
            assertEquals(0, frame.refCnt())
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `fragmented binary message is aggregated before decoding`() {
        val channel = EmbeddedChannel(
            WebSocketFrameAggregator(WebSocketProtocol.MAX_FRAME_LENGTH),
            WebSocketNetMessageCodec(),
        )
        val payload = byteArrayOf(7, 8, 9)
        val packet = NetMessage(Rpc.RpcNameEnum.PlacePieces_VALUE, payload).toByteBuf()
        try {
            val first = BinaryWebSocketFrame(false, 0, packet.readRetainedSlice(5))
            val last = ContinuationWebSocketFrame(true, 0, packet.readRetainedSlice(packet.readableBytes()))
            assertFalse(channel.writeInbound(first))
            assertTrue(channel.writeInbound(last))
            val decoded = channel.readInbound<NetMessage>()
            assertEquals(Rpc.RpcNameEnum.PlacePieces_VALUE, decoded.msgId)
            assertContentEquals(payload, decoded.data)
        } finally {
            packet.release()
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `invalid declared length is rejected without retaining frame`() {
        for (declared in listOf(-1, 0, 11, 13, Int.MAX_VALUE)) {
            val channel = EmbeddedChannel(WebSocketNetMessageCodec())
            val frame = BinaryWebSocketFrame(Unpooled.buffer().writeInt(declared).writeInt(1).writeInt(0))
            try {
                assertFailsWith<CorruptedFrameException> { channel.writeInbound(frame) }
                assertEquals(0, frame.refCnt())
            } finally {
                channel.finishAndReleaseAll()
            }
        }
    }

    @Test
    fun `text frames and incomplete headers are rejected`() {
        for (frame in listOf(TextWebSocketFrame("hello"), BinaryWebSocketFrame(Unpooled.buffer().writeInt(4)))) {
            val channel = EmbeddedChannel(WebSocketNetMessageCodec())
            try {
                assertFailsWith<CorruptedFrameException> { channel.writeInbound(frame) }
                assertEquals(0, frame.refCnt())
            } finally {
                channel.finishAndReleaseAll()
            }
        }
    }
}
