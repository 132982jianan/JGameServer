package com.jacey.game.gate.netty

import akka.actor.Props
import com.jacey.game.common.framework.akka.AkkaService
import com.jacey.game.gate.actor.GateActor
import com.jacey.game.common.framework.config.AppConfig
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.framework.net.NacosService
import com.jacey.game.common.framework.net.WebSocketNetMessageCodec
import com.jacey.game.common.framework.net.WebSocketProtocol
import com.jacey.game.common.proto3.CommonEnum
import com.jacey.game.common.proto3.Rpc
import com.jacey.game.gate.service.MessageRouterService
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.proto3.CommonMsg
import com.jacey.game.gate.actor.GateActorState
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.ChannelFutureListener
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator
import io.netty.handler.timeout.IdleStateHandler
import io.github.oshai.kotlinlogging.KotlinLogging
import io.netty.util.AttributeKey
import java.util.concurrent.atomic.AtomicInteger

/**
 * Netty 服务器（object 单例）
 *
 * 线协议不变：packetLength | msgId | errorCode | protobuf body
 * 每个 WebSocket 二进制帧承载一条游戏消息。
 * 客户端唯一入口：net.yml gate.ws（路径 /websocket）。
 */
object NettyServer {
    private val logger = KotlinLogging.logger {}

    /** channel -> session actor 缓存键（保证每连接仅创建一次 actor） */
    val GATE_ACTOR_KEY = AttributeKey.valueOf<akka.actor.ActorRef>("gateActorKey")
    private val connectionSequence = AtomicInteger()

    private val bossGroup = NioEventLoopGroup(1)
    private val workerGroup = NioEventLoopGroup()

    suspend fun start() {
        val conf = AppConfig.instance
        val ports = NacosService.netConfig.calNodePortByNodeKindAndNodeId(NodeKind.gate, NacosService.selfNodeId)
        val wsPort = ports.ws
        require(wsPort in 1..65535) { "Gate requires a valid WebSocket port (portRange.gate.ws)" }
        startWs(wsPort, conf)
    }

    private fun startWs(port: Int, conf: AppConfig) {
        val bootstrap = ServerBootstrap()
        bootstrap.group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .option(ChannelOption.SO_BACKLOG, 1024)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    val p = ch.pipeline()
                    p.addLast(
                        IdleStateHandler(
                            conf.socketReaderIdleTime,
                            conf.socketWriterIdleTime,
                            conf.socketAllIdleTime
                        )
                    )
                    p.addLast(HttpServerCodec())
                    p.addLast(HttpObjectAggregator(65536))
                    p.addLast(
                        WebSocketServerProtocolHandler(
                            WebSocketServerProtocolConfig.newBuilder()
                                .websocketPath(WebSocketProtocol.PATH)
                                .allowExtensions(false)
                                .maxFramePayloadLength(WebSocketProtocol.MAX_FRAME_LENGTH)
                                .handshakeTimeoutMillis(WebSocketProtocol.HANDSHAKE_TIMEOUT_MS)
                                .build()
                        )
                    )
                    p.addLast(WebSocketFrameAggregator(WebSocketProtocol.MAX_FRAME_LENGTH))
                    p.addLast(WebSocketNetMessageCodec())
                    p.addLast(WebSocketBusinessHandler())
                }
            })
        bootstrap.bind(port).sync()
        logger.info { "netty websocket 服务已启动，监听端口 $port (path=/websocket)" }
    }

    /** WebSocket 握手成功后创建唯一 GateActor；连接状态只保存在 GateActorState。 */
    fun onWebSocketReady(ctx: ChannelHandlerContext) {
        val channel = ctx.channel()
        if (!MessageRouterService.isAvailableForClient()) {
            val push = CommonMsg.ForceOfflinePush.newBuilder()
                .setForceOfflineReason(CommonEnum.ForceOfflineReasonEnum.ForceOfflineServerNotAvailable)
                .build()
            channel.writeAndFlush(NetMessage(Rpc.RpcNameEnum.RpcForceOfflinePush_VALUE, push))
                .addListener(ChannelFutureListener.CLOSE)
            return
        }
        val sessionId =
            Math.addExact(Math.multiplyExact(NacosService.selfNodeId, 1_000_000), connectionSequence.incrementAndGet())
        val state = GateActorState(channel, sessionId)
        val gateActorRef = AkkaService.system.actorOf(
            Props.create(GateActor::class.java) { GateActor(state) },
            "ws-${channel.id().asShortText()}",
        )
        channel.attr(GATE_ACTOR_KEY).set(gateActorRef)
        logger.info { "GateActor attached: sessionId=$sessionId ip=${state.userIp}" }
    }

    fun shutdown() {
        workerGroup.shutdownGracefully()
        bossGroup.shutdownGracefully()
    }
}
