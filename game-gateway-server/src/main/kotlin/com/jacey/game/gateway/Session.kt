package com.jacey.game.gateway

import akka.actor.ActorRef
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.db.service.BattleInfoService
import com.jacey.game.db.redis.SessionIdRedis
import io.netty.channel.Channel
import io.netty.util.AttributeKey

/**
 * 每连接会话（原 ChannelActor/ResponseActor 合并精简）
 *
 * 原设计：ChannelActor + 附属 ResponseActor 两个 actor（因为 akka classic 无法区分
 * 请求来源）；现在消息处理是协程 + 显式注册，一个 session 对象即可：
 * - 绑定 netty channel / userId / userIp
 * - write() 直接回写客户端
 *
 * Session 由 SessionManager（actor 串行）创建与索引。
 */
class Session(val channel: Channel) {
    var userId: Int = 0
    val userIp: String? = (channel.remoteAddress() as? java.net.InetSocketAddress)?.address?.hostAddress

    val sessionId: Int get() = SessionManager.sessionIdOf(channel) ?: 0

    fun write(msg: NetMessage) {
        if (channel.isActive && channel.isWritable) {
            channel.writeAndFlush(msg)
        }
    }

    fun writeAndFlushBinary(msg: NetMessage) = write(msg)

    fun close() {
        channel.close()
    }
}

/**
 * 会话管理器（object 单例）
 * - channelId -> session
 * - sessionId -> channel（兼容原 OnlineClientManager 索引）
 */
object SessionManager {
    private val channelIdToSession = java.util.concurrent.ConcurrentHashMap<Int, Session>()
    private val sessionIdToChannel = java.util.concurrent.ConcurrentHashMap<Int, Channel>()

    val NETTY_CHANNEL_TO_SESSION = AttributeKey.valueOf<Session>("nettyChannelToSessionKey")
    val NETTY_CHANNEL_TO_SESSION_ID = AttributeKey.valueOf<Int>("nettyChannelToSessionIdKey")

    fun attach(channel: Channel, sessionId: Int): Session {
        val session = Session(channel)
        channel.attr(NETTY_CHANNEL_TO_SESSION).set(session)
        channel.attr(NETTY_CHANNEL_TO_SESSION_ID).setIfAbsent(sessionId)
        channelIdToSession[channel.id().hashCode()] = session
        sessionIdToChannel[sessionId] = channel
        return session
    }

    fun sessionOf(channel: Channel): Session? = channel.attr(NETTY_CHANNEL_TO_SESSION).get()
    fun sessionIdOf(channel: Channel): Int? = channel.attr(NETTY_CHANNEL_TO_SESSION_ID).get()
    fun channelOf(sessionId: Int): Channel? = sessionIdToChannel[sessionId]
    fun remove(channel: Channel): Session? {
        sessionIdToChannel.remove(sessionIdOf(channel) ?: 0)
        return channelIdToSession.remove(channel.id().hashCode())
    }

    val onlineCount: Int get() = sessionIdToChannel.size

    /** 新建 sessionId（Redis 自增，suspend） */
    suspend fun newSessionId(): Int = SessionIdRedis.addAndGetNextAvailableSessionId().toInt()

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
            com.jacey.game.db.service.PlayStateService.changeUserOnlineState(userId, false)
        }

        // 通知 logic（原 GatewayNoticeClientOfflinePush）
        val logicServerId = BattleInfoService.getOneSessionIdToLogicServerId(sessionId)
        if (logicServerId != null && logicServerId > 0) {
            val push = com.jacey.game.common.proto3.RemoteServer.GatewayNoticeClientOfflinePush.newBuilder()
                .setSessionId(sessionId)
                .setUserId(userId)
                .setIsUserOffline(isUserOffline)
                .build()
            val remoteMsg = com.jacey.game.common.msg.RemoteMessage(
                com.jacey.game.common.proto3.RemoteServer.RemoteRpcNameEnum.RemoteRpcGatewayNoticeClientOfflinePush_VALUE,
                push
            )
            val ref = com.jacey.game.common.framework.net.NodeRegister.actorRefOf(
                com.jacey.game.common.framework.net.NodeKind.logic, logicServerId)
            ref?.tell(remoteMsg, ActorRef.noSender())
        }

        // battle / chat 通知
        val battleId = BattleInfoService.getBattleUserIdToBattleId(userId)
        if (battleId != null) {
            listOf(
                BattleInfoService.getOneBattleIdToBattleServerId(battleId),
                BattleInfoService.getOneBattleIdToChatServerId(battleId)
            ).forEach { serverId ->
                if (serverId != null && serverId > 0) {
                    val push = com.jacey.game.common.proto3.RemoteServer.GatewayNoticeClientOfflinePush.newBuilder()
                        .setSessionId(sessionId)
                        .setUserId(userId)
                        .setIsUserOffline(isUserOffline)
                        .build()
                    val remoteMsg = com.jacey.game.common.msg.RemoteMessage(
                        com.jacey.game.common.proto3.RemoteServer.RemoteRpcNameEnum.RemoteRpcGatewayNoticeClientOfflinePush_VALUE,
                        push
                    )
                    val kind = if (serverId == BattleInfoService.getOneBattleIdToBattleServerId(battleId))
                        com.jacey.game.common.framework.net.NodeKind.battle
                    else com.jacey.game.common.framework.net.NodeKind.chat
                    val ref = com.jacey.game.common.framework.net.NodeRegister.actorRefOf(kind, serverId)
                    ref?.tell(remoteMsg, ActorRef.noSender())
                }
            }
        }
    }
}
