package com.jacey.game.gate.netty

import com.jacey.game.common.framework.config.AppConfig
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.framework.net.NacosService
import com.jacey.game.common.proto3.CommonEnum
import com.jacey.game.gate.service.MessageRouterService
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.gate.actor.GateActorState
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
import io.netty.handler.timeout.IdleStateHandler
import io.github.oshai.kotlinlogging.KotlinLogging
import io.netty.util.AttributeKey
import java.util.concurrent.atomic.AtomicInteger

/**
 * Netty 服务器（object 单例）
 *
 * 线协议不变：packetLength | msgId | errorCode | protobuf body
 * - TCP 端口：net.yml gate.tcp
 * - WebSocket 端口：net.yml gate.ws（路径 /websocket）
 */
object NettyServer {
    private val logger = KotlinLogging.logger {}
    const val HEADER_LENGTH = 12

    /** 客户端心跳裸字符串（GUI ClientHandler 发送，非帧格式） */
    val HEARTBEAT_BYTES = "hb_request".toByteArray(Charsets.UTF_8)

    /** channel -> session actor 缓存键（保证每连接仅创建一次 actor） */
    val GATE_ACTOR_KEY = AttributeKey.valueOf<akka.actor.ActorRef>("gateActorKey")
    private val connectionSequence = AtomicInteger()

    private val bossGroup = NioEventLoopGroup(1)
    private val workerGroup = NioEventLoopGroup()

    suspend fun start() {
        val conf = AppConfig.instance
        val ports = NacosService.netConfig.calNodePortByNodeKindAndNodeId(NodeKind.gate, NacosService.selfNodeId)
        val tcpPort = ports.tcp
        val wsPort = ports.ws

        if (tcpPort > 0) {
            startTcp(tcpPort, conf)
        }
        if (wsPort > 0) {
            startWs(wsPort, conf)
        }
    }

    private fun startTcp(port: Int, conf: AppConfig) {
        val bootstrap = ServerBootstrap()
        bootstrap.group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .option(ChannelOption.SO_BACKLOG, 1024)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    val p = ch.pipeline()
                    p.addLast(ProtocolDecoder())
                    p.addLast(ProtocolEncoder())
                    p.addLast(
                        IdleStateHandler(
                            conf.socketReaderIdleTime,
                            conf.socketWriterIdleTime,
                            conf.socketAllIdleTime
                        )
                    )
                    p.addLast(TcpBusinessHandler())
                }
            })
        bootstrap.bind(port).sync()
        logger.info { "netty tcp 服务已启动，监听端口 $port" }
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
                    p.addLast(WebSocketServerProtocolHandler("/websocket", null, true))
                    p.addLast(WebSocketBusinessHandler())
                }
            })
        bootstrap.bind(port).sync()
        logger.info { "netty websocket 服务已启动，监听端口 $port (path=/websocket)" }
    }

    /** 连接建立即创建唯一 GateActor；连接状态只保存在 GateActorState。 */
    fun onChannelActive(ctx: ChannelHandlerContext, handler: AbsBusinessHandler) {
        val channel = ctx.channel()
        if (!MessageRouterService.isAvailableForClient()) {
            val push = com.jacey.game.common.proto3.CommonMsg.ForceOfflinePush.newBuilder()
                .setForceOfflineReason(CommonEnum.ForceOfflineReasonEnum.ForceOfflineServerNotAvailable)
                .build()
            channel.writeAndFlush(NetMessage(20001, push))
            channel.close()
            return
        }
        val sessionId = Math.addExact(Math.multiplyExact(NacosService.selfNodeId, 1_000_000), connectionSequence.incrementAndGet())
        val state = GateActorState(channel, sessionId)
        val actor = handler.newGateActor(state)
        channel.attr(GATE_ACTOR_KEY).set(actor)
        logger.info { "GateActor attached: sessionId=$sessionId ip=${state.userIp}" }
    }

    fun shutdown() {
        workerGroup.shutdownGracefully()
        bossGroup.shutdownGracefully()
    }
}
