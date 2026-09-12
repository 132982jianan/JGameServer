package com.jacey.game.chat

import akka.actor.ActorRef
import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.common.msg.LocalMessage
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.msg.RemoteMessage
import com.jacey.game.common.proto3.CommonEnum
import com.jacey.game.common.proto3.LocalServer
import com.jacey.game.common.proto3.RemoteServer
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.framework.net.NodeRegister
import com.jacey.game.common.framework.process.Dispatcher
import com.jacey.game.db.service.BattleInfoService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 聊天服状态（原 chat OnlineClientManager）
 * - battleId -> 聊天室 actor
 * - sessionId -> gateway ResponseActor
 */
object ChatRooms {
    private val battleIdToChatRoomActor = java.util.concurrent.ConcurrentHashMap<String, ActorRef>()
    private val sessionIdToGatewayResponseActor = java.util.concurrent.ConcurrentHashMap<Int, ActorRef>()

    val chatRoomCount: Int get() = battleIdToChatRoomActor.size

    fun getChatRoomActor(battleId: String): ActorRef? = battleIdToChatRoomActor[battleId]

    fun addChatRoomActor(battleId: String, actor: ActorRef) {
        battleIdToChatRoomActor[battleId] = actor
        // battleId <-> chatServerId 绑定
        kotlinx.coroutines.runBlocking {
            BattleInfoService.setOneBattleIdToChatServerId(battleId, NodeRegister.selfId)
        }
    }

    fun removeChatRoomActor(battleId: String) {
        battleIdToChatRoomActor.remove(battleId)
        kotlinx.coroutines.runBlocking {
            BattleInfoService.setOneBattleIdToChatServerId(battleId, 0)
        }
    }

    fun addGatewayResponseActor(sessionId: Int, actor: ActorRef?) {
        if (actor != null) sessionIdToGatewayResponseActor[sessionId] = actor
    }

    fun getGatewayResponseActor(sessionId: Int): ActorRef? = sessionIdToGatewayResponseActor[sessionId]

    fun removeGatewayResponseActor(sessionId: Int) {
        sessionIdToGatewayResponseActor.remove(sessionId)
    }
}

/**
 * 消息推送（原 chat MessageRouter 推送部分）
 */
object ChatMessageRouter {
    @Volatile
    var isConnectedToGm: Boolean = false

    /** 推送消息到 userId 对应客户端 */
    suspend fun sendNetMsgToOneUser(userId: Int, netMsg: NetMessage): Boolean {
        val sessionId = com.jacey.game.db.redis.SessionIdRedis.getOneUserIdToSessionId(userId)
        return if (sessionId != null) {
            val actor = ChatRooms.getGatewayResponseActor(sessionId)
            if (actor != null) {
                actor.tell(netMsg, ActorRef.noSender())
                true
            } else {
                false
            }
        } else {
            false
        }
    }

    suspend fun sendRemoteToGm(msg: RemoteMessage, sender: ActorRef?) {
        val ref = NodeRegister.actorRefOf(NodeKind.gm, 1)
        ref?.tell(msg, sender)
    }
}

/**
 * 聊天服主 Actor（原 ChatServerActor）
 */
class ChatServerActor : BaseMessageActor() {
    private var reconnectJob: Job? = null

    init {
        registerHandler(LocalMessage::class.java) { msg, _ -> onLocal(msg) }
        registerHandler(RemoteMessage::class.java) { msg, _ -> onRemote(msg) }
        registerHandler(NetMessage::class.java) { msg, sender ->
            ChatRoomManagerProxy.dispatchNetMessage(msg, sender())
        }
    }

    override suspend fun onTerminated(t: akka.actor.Terminated) {
        ChatMessageRouter.isConnectedToGm = false
        startReconnect()
    }

    private suspend fun onLocal(msg: LocalMessage) {
        when (msg.rpcNum) {
            LocalServer.LocalRpcNameEnum.LocalRpcRegistToGmServer_VALUE -> registerToGm()
        }
    }

    private suspend fun onRemote(msg: RemoteMessage) {
        when (msg.rpcNum) {
            RemoteServer.RemoteRpcNameEnum.RemoteRpcRegistServer_VALUE -> {
                if (msg.errorCode == RemoteServer.RemoteRpcErrorCodeEnum.RemoteRpcOk_VALUE) {
                    ChatMessageRouter.isConnectedToGm = true
                    logger.info { "【向GM服务器注册成功....】" }
                    stopReconnect()
                } else {
                    logger.error { "【GM服务器注册失败】errorCode=${msg.errorCode}" }
                    com.jacey.game.common.framework.process.Exit.exit(0)
                }
            }

            RemoteServer.RemoteRpcNameEnum.RemoteRpcGatewayNoticeClientOfflinePush_VALUE -> {
                val push = msg.getProto<RemoteServer.GatewayNoticeClientOfflinePush>()
                ChatRooms.removeGatewayResponseActor(push?.sessionId ?: 0)
            }
        }
    }

    private suspend fun registerToGm() {
        logger.info { "【正在尝试连接GM服务器....】" }
        val serverInfo = RemoteServer.RemoteServerInfo.newBuilder()
            .setServerType(com.jacey.game.common.proto3.CommonEnum.RemoteServerTypeEnum.ServerTypeChat)
            .setServerId(NodeRegister.selfId)
            .setAkkaPath(NodeRegister.selfInfo.actorPath)
        val request = RemoteServer.RegistServerRequest.newBuilder()
            .setServerInfo(serverInfo)
        ChatMessageRouter.sendRemoteToGm(
            RemoteMessage(RemoteServer.RemoteRpcNameEnum.RemoteRpcRegistServer_VALUE, request),
            self()
        )
    }

    private fun startReconnect() {
        if (reconnectJob == null) {
            val scope = CoroutineScope(Dispatcher.Scheduler)
            val msg: com.jacey.game.common.msg.IMessage =
                LocalMessage(LocalServer.LocalRpcNameEnum.LocalRpcRegistToGmServer_VALUE)
            reconnectJob = scope.launch {
                while (isActive) {
                    self().tell(msg, ActorRef.noSender())
                    kotlinx.coroutines.delay(5000)
                }
            }
        }
    }

    private fun stopReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    override fun preStart() {
        super.preStart()
        startReconnect()
    }
}

/**
 * 聊天室管理（原 ChatRoomMangerActor）：创建聊天室 + 分发聊天请求
 */
class ChatRoomManagerProxy : BaseMessageActor() {

    init {
        registerHandler(RemoteMessage::class.java) { msg, _ -> onRemote(msg) }
        registerHandler(NetMessage::class.java, ::onNet)
    }

    private suspend fun onRemote(msg: RemoteMessage) {
        when (msg.rpcNum) {
            RemoteServer.RemoteRpcNameEnum.RemoteRpcNoticeChatServerCreateNewBattleChatRoom_VALUE -> {
                val request = msg.getProto<RemoteServer.NoticeChatServerCreateNewBattleChatRoomRequest>() ?: return
                val chatRoomInfo = request.chatRoomInfo
                val battleId = chatRoomInfo.battleId
                when (chatRoomInfo.chatRoomType.number) {
                    CommonEnum.ChatRoomTypeEnum.TwoPlayerBattleChatRoomType_VALUE -> {
                        val actor = com.jacey.game.common.framework.akka.Akka.create<BaseBattleChatRoomActor>(
                            "chatRoom-$battleId"
                        )
                        ChatRooms.addChatRoomActor(battleId, actor)
                        val response = RemoteServer.NoticeChatServerCreateNewBattleChatRoomResponse.newBuilder()
                        sender()?.tell(
                            RemoteMessage(
                                RemoteServer.RemoteRpcNameEnum.RemoteRpcNoticeChatServerCreateNewBattleChatRoom_VALUE,
                                response
                            ),
                            ActorRef.noSender()
                        )
                        logger.info { "对战聊天室初始化完成 battleId=$battleId" }
                    }

                    else -> logger.error { "not support chatRoomType=${chatRoomInfo.chatRoomType}" }
                }
            }
        }
    }

    private suspend fun onNet(msg: NetMessage, sender: ActorRef?) {
        when (msg.rpcNum) {
            com.jacey.game.common.proto3.Rpc.RpcNameEnum.JoinChatRoom_VALUE,
            com.jacey.game.common.proto3.Rpc.RpcNameEnum.BattleChatText_VALUE -> {
                val userId = msg.userId
                val battleId = BattleInfoService.getBattleUserIdToBattleId(userId)
                val chatRoom = battleId?.let { ChatRooms.getChatRoomActor(it) }
                if (chatRoom == null) {
                    sender()?.tell(
                        NetMessage(
                            msg.rpcNum,
                            com.jacey.game.common.proto3.Rpc.RpcErrorCodeEnum.ServerError_VALUE
                        ), null
                    )
                    return
                }
                ChatRooms.addGatewayResponseActor(msg.sessionId, sender())
                chatRoom.tell(msg, sender)
            }
        }
    }

    companion object {
        /** 由 ChatServerActor 调用的静态分发 */
        fun dispatchNetMessage(msg: NetMessage, sender: ActorRef?) {
            // 直接路由（聊天服内单级分发，无需经过 actor 延迟）
            val userId = msg.userId
            val battleId = kotlinx.coroutines.runBlocking {
                BattleInfoService.getBattleUserIdToBattleId(userId)
            }
            val chatRoom = battleId?.let { ChatRooms.getChatRoomActor(it) }
            if (chatRoom != null) {
                ChatRooms.addGatewayResponseActor(msg.sessionId, sender)
                chatRoom.tell(msg, sender)
            } else {
                sender?.tell(
                    NetMessage(
                        msg.rpcNum,
                        com.jacey.game.common.proto3.Rpc.RpcErrorCodeEnum.ServerError_VALUE
                    ), null
                )
            }
        }
    }
}
