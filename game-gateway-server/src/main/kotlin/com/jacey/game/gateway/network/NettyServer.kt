package com.jacey.game.gateway.network

import com.jacey.game.common.framework.config.AppConfig
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.framework.net.NodeRegister
import com.jacey.game.common.proto3.CommonEnum
import com.jacey.game.gateway.MessageRouter
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.gateway.SessionManager
import com.jacey.game.gateway.actor.ClientSessionActor
import com.jacey.game.common.framework.akka.Akka
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.MessageToByteEncoder
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
import io.netty.handler.timeout.IdleState
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.launch

/**
 * Netty 服务器（object 单例）
 *
 * 线协议不变：packetLength | rpcNum | errorCode | protobuf body
 * - TCP 端口：net.yml gateway.tcp（原 10001）
 * - WebSocket 端口：net.yml gateway.ws（原 10002，路径 /websocket）
 */
object NettyServer {
    private val logger = KotlinLogging.logger {}
    private const val HEADER_LENGTH = 12

    /** 客户端心跳裸字符串（GUI ClientHandler 发送，非帧格式） */
    private val HEARTBEAT_BYTES = "hb_request".toByteArray(Charsets.UTF_8)

    /** channel -> session actor 缓存键（保证每连接仅创建一次 actor） */
    private val SESSION_ACTOR_KEY =
        io.netty.util.AttributeKey.valueOf<akka.actor.ActorRef>("sessionActorKey")

    private val bossGroup = NioEventLoopGroup(4)
    private val workerGroup = NioEventLoopGroup()

    /** TCP 自定义解码器：处理粘包/拆包 */
    class ProtocolDecoder : ByteToMessageDecoder() {
        override fun decode(ctx: ChannelHandlerContext, buf: io.netty.buffer.ByteBuf, out: MutableList<Any>) {
            // 客户端心跳是裸字符串 "hb_request"（非 12 字节帧），直接剥离
            if (buf.readableBytes() >= HEARTBEAT_BYTES.size) {
                buf.markReaderIndex()
                val possible = ByteArray(HEARTBEAT_BYTES.size)
                buf.getBytes(buf.readerIndex(), possible)
                if (possible.contentEquals(HEARTBEAT_BYTES)) {
                    buf.skipBytes(HEARTBEAT_BYTES.size)
                    return
                }
                buf.resetReaderIndex()
            }
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

    /** TCP 编码器 */
    class ProtocolEncoder : MessageToByteEncoder<NetMessage>() {
        override fun encode(ctx: ChannelHandlerContext, msg: NetMessage, out: io.netty.buffer.ByteBuf) {
            out.writeBytes(msg.toBinaryMsg())
        }
    }

    /** 公共业务入站处理：NetMessage → ClientSessionActor */
    open class BusinessHandler : ChannelInboundHandlerAdapter() {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            when (msg) {
                is NetMessage -> {
                    val session = SessionManager.sessionOf(ctx.channel())
                    if (session == null) {
                        logger.warn { "no session bound, drop msg rpcNum=${msg.rpcNum}" }
                        return
                    }
                    // 每个连接只创建一次 session actor（channel attribute 缓存）
                    var actor = ctx.channel().attr(SESSION_ACTOR_KEY).get()
                    if (actor == null) {
                        actor = actorOf(session)
                        ctx.channel().attr(SESSION_ACTOR_KEY).set(actor)
                    }
                    actor.tell(msg, null)
                }
                else -> ctx.fireChannelRead(msg)
            }
        }

        override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
            if (evt is IdleStateEvent && evt.state() == IdleState.ALL_IDLE) {
                logger.info { "idle timeout, close channel ${ctx.channel()}" }
                ctx.close()
            } else {
                super.userEventTriggered(ctx, evt)
            }
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            val channel = ctx.channel()
            val session = SessionManager.remove(channel)
            val sessionId = SessionManager.sessionIdOf(channel)
            if (session != null && sessionId != null) {
                // 异步执行断线处理（redis 清理 + 跨服通知）
                kotlinx.coroutines.runBlocking {
                    SessionManager.removeSession(sessionId)
                }
            }
            ctx.fireChannelInactive()
        }

        protected open fun actorOf(session: com.jacey.game.gateway.Session): akka.actor.ActorRef =
            Akka.system.actorOf(
                akka.actor.Props.create(ClientSessionActor::class.java) { ClientSessionActor(session) },
                "client-" + session.channel.id().asShortText()
            )
    }

    /** WebSocket 帧适配处理 */
    class WebSocketBusinessHandler : BusinessHandler() {
        override fun channelActive(ctx: ChannelHandlerContext) {
            onChannelActive(ctx)
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

        private fun decodeFull(buf: io.netty.buffer.ByteBuf): NetMessage? {
            if (buf.readableBytes() < HEADER_LENGTH) return null
            val totalLength = buf.readInt()
            val rpcNum = buf.readInt()
            val errorCode = buf.readInt()
            val bytes = ByteArray(totalLength - HEADER_LENGTH)
            buf.readBytes(bytes)
            return NetMessage(rpcNum, bytes).also { it.errorCode = errorCode }
        }

        override fun actorOf(session: com.jacey.game.gateway.Session): akka.actor.ActorRef =
            Akka.system.actorOf(
                akka.actor.Props.create(ClientSessionActor::class.java) { ClientSessionActor(session) },
                "ws-" + session.channel.id().asShortText()
            )
    }

    suspend fun start() {
        val conf = AppConfig.instance
        val ports = com.jacey.game.common.framework.net.NodeRegister.netConf
            .portOf(com.jacey.game.common.framework.net.NodeKind.gateway, NodeRegister.selfId)
        val tcpPort = ports.tcp
        val wsPort = ports.ws

        if (tcpPort > 0) startTcp(tcpPort, conf)
        if (wsPort > 0) startWs(wsPort, conf)
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
                    p.addLast(IdleStateHandler(
                        conf.socketReaderIdleTime,
                        conf.socketWriterIdleTime,
                        conf.socketAllIdleTime
                    ))
                    p.addLast(object : BusinessHandler() {
                        override fun channelActive(ctx: ChannelHandlerContext) {
                            this@NettyServer.onChannelActive(ctx)
                        }
                    })
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
                    p.addLast(IdleStateHandler(
                        conf.socketReaderIdleTime,
                        conf.socketWriterIdleTime,
                        conf.socketAllIdleTime
                    ))
                    p.addLast(HttpServerCodec())
                    p.addLast(HttpObjectAggregator(65536))
                    p.addLast(WebSocketServerProtocolHandler("/websocket", null, true))
                    p.addLast(WebSocketBusinessHandler())
                }
            })
        bootstrap.bind(port).sync()
        logger.info { "netty websocket 服务已启动，监听端口 $port (path=/websocket)" }
    }

    /** 连接建立：不可用则拒绝；可用则分配 sessionId 并绑定 session（原 attachChannelActor） */
    private fun onChannelActive(ctx: ChannelHandlerContext) = kotlinx.coroutines.GlobalScope.launch {
        val ctxInternal = ctx
        val channel = ctxInternal.channel()
        if (!MessageRouter.isAvailableForClient()) {
            val push = com.jacey.game.common.proto3.CommonMsg.ForceOfflinePush.newBuilder()
                .setForceOfflineReason(CommonEnum.ForceOfflineReasonEnum.ForceOfflineServerNotAvailable)
                .build()
            val session = com.jacey.game.gateway.Session(channel)
            session.write(NetMessage(20001, push))
            channel.close()
            return@launch
        }
        val sessionId = SessionManager.newSessionId()
        val session = SessionManager.attach(channel, sessionId)
        // sessionId 与 gatewayId 绑定（redis）
        com.jacey.game.db.service.BattleInfoService.setOneSessionIdToGatewayId(
            sessionId, com.jacey.game.common.framework.net.NodeRegister.selfId
        )
        logger.info { "session attached: sessionId=$sessionId ip=${session.userIp}" }
    }

    fun shutdown() {
        workerGroup.shutdownGracefully()
        bossGroup.shutdownGracefully()
    }
}
