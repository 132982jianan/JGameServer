package com.jacey.game.gateway.session

import akka.actor.ActorRef
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.framework.net.NodeRegister
import com.jacey.game.common.msg.RemoteMessage
import com.jacey.game.common.proto3.RemoteServer
import com.jacey.game.db.redis.SessionIdRedis
import com.jacey.game.db.service.BattleInfoService
import com.jacey.game.db.service.PlayStateService
import io.netty.channel.Channel
import io.netty.util.AttributeKey
import java.util.concurrent.ConcurrentHashMap

/**
 * 会话管理器（object 单例）
 * - channelId -> session
 * - sessionId -> channel（兼容原 OnlineClientManager 索引）
 */
object SessionManagerService {
    private val channelIdToSession = ConcurrentHashMap<Int, ClientSession>()
    private val sessionIdToChannel = ConcurrentHashMap<Int, Channel>()

    val NETTY_CHANNEL_TO_SESSION = AttributeKey.valueOf<ClientSession>("nettyChannelToSessionKey")
    val NETTY_CHANNEL_TO_SESSION_ID = AttributeKey.valueOf<Int>("nettyChannelToSessionIdKey")

    val onlineCount: Int
        get() {
            return sessionIdToChannel.size
        }

    fun attach(channel: Channel, sessionId: Int): ClientSession {
        val session = ClientSession(channel)
        channel.attr(NETTY_CHANNEL_TO_SESSION).set(session)
        channel.attr(NETTY_CHANNEL_TO_SESSION_ID).setIfAbsent(sessionId)
        channelIdToSession[channel.id().hashCode()] = session
        sessionIdToChannel[sessionId] = channel
        return session
    }

    fun sessionOf(channel: Channel): ClientSession? {
        return channel.attr(NETTY_CHANNEL_TO_SESSION).get()
    }

    fun sessionIdOf(channel: Channel): Int? {
        return channel.attr(NETTY_CHANNEL_TO_SESSION_ID).get()
    }

    fun channelOf(sessionId: Int): Channel? {
        return sessionIdToChannel[sessionId]
    }

    fun remove(channel: Channel): ClientSession? {
        sessionIdToChannel.remove(sessionIdOf(channel) ?: 0)
        return channelIdToSession.remove(channel.id().hashCode())
    }

    /** 新建 sessionId（Redis 自增，suspend） */
    suspend fun newSessionId(): Int {
        return SessionIdRedis.addAndGetNextAvailableSessionId().toInt()
    }

    /**
     * 会话断线处理（原 OnlineClientManager.removeSession + noticeClientOffline）：
     * - 移除 sessionId <-> gateway 绑定
     * - 已登录用户：更新离线状态并通知 logic/battle/chat
     */
    suspend fun removeSession(sessionId: Int) {
        sessionIdToChannel.remove(sessionId)
        BattleInfoService.removeOneSessionIdToGatewayId(sessionId)
        val userId = SessionIdRedis.getOneSessionIdToUserId(sessionId) ?: return
        SessionIdRedis.removeOneSessionIdToUserId(sessionId)

        // 同一账号二次登录：新 session 已绑定，不重复踢下线处理
        val currentSessionId = SessionIdRedis.getOneUserIdToSessionId(userId)
        val isUserOffline = currentSessionId == sessionId
        if (isUserOffline) {
            SessionIdRedis.removeOneSessionIdToUserId(userId)
            PlayStateService.changeUserOnlineState(userId, false)
        }

        // 通知 logic（原 GatewayNoticeClientOfflinePush）
        val logicServerId = BattleInfoService.getOneSessionIdToLogicServerId(sessionId)
        if (logicServerId != null && logicServerId > 0) {
            noticeClientOffline(NodeKind.logic, logicServerId, sessionId, userId, isUserOffline)
        }
        BattleInfoService.removeOneSessionIdToLogicServerId(sessionId)

        // 通知 battle / chat
        noticeBattleAndChatClientOffline(sessionId, userId, isUserOffline)
    }

    /** 向单个功能节点推送客户端断线通知（logic/battle/chat 通用） */
    private suspend fun noticeClientOffline(
        kind: NodeKind,
        serverId: Int,
        sessionId: Int,
        userId: Int,
        isUserOffline: Boolean,
    ) {
        val push = RemoteServer.GatewayNoticeClientOfflinePush.newBuilder()
            .setSessionId(sessionId)
            .setUserId(userId)
            .setIsUserOffline(isUserOffline)
            .build()
        val remoteMsg = RemoteMessage(
            RemoteServer.RemoteRpcNameEnum.RemoteRpcGatewayNoticeClientOfflinePush_VALUE,
            push
        )
        NodeRegister.getActorRefByNodeKindAndNodeId(kind, serverId)?.tell(remoteMsg, ActorRef.noSender())
    }

    /** 通知用户当前对局所在的 battle / chat 服务器 */
    private suspend fun noticeBattleAndChatClientOffline(sessionId: Int, userId: Int, isUserOffline: Boolean) {
        val battleId = BattleInfoService.getBattleUserIdToBattleId(userId) ?: return
        val battleServerId = BattleInfoService.getOneBattleIdToBattleServerId(battleId)
        if (battleServerId != null && battleServerId > 0) {
            noticeClientOffline(NodeKind.battle, battleServerId, sessionId, userId, isUserOffline)
        }
        val chatServerId = BattleInfoService.getOneBattleIdToChatServerId(battleId)
        if (chatServerId != null && chatServerId > 0) {
            noticeClientOffline(NodeKind.chat, chatServerId, sessionId, userId, isUserOffline)
        }
    }
}