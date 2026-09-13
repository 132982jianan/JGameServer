package com.jacey.game.gateway.actor

import akka.actor.ActorRef
import akka.actor.Props
import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.proto3.Rpc
import com.jacey.game.db.service.BattleInfoService
import com.jacey.game.gateway.service.MessageRouterService
import com.jacey.game.gateway.service.GatewayZone
import com.jacey.game.gateway.session.ClientSession
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * 客户端会话 Actor（原 ChannelActor）
 *
 * 每个客户端连接绑定一个；只处理客户端"请求"（NetMessage）：
 * - Regist/Match/CancelMatch → main logic
 * - Login → 随机 logic
 * - 对战操作 → battle（须在对战中）
 * - 聊天 → chat（须在对战中）
 *
 * 回包/推送通道分离：转发时以子 ResponseActor 为 sender，backend 回包
 * 进入 GatewayResponseActor 直接写回客户端——绝不能再进入本 actor
 * （否则响应会被当作新客户端请求再次转发，形成 gateway↔backend 死循环）。
 */
class ClientSessionActor(private val clientSession: ClientSession) : BaseMessageActor() {
    private val log = KotlinLogging.logger {}

    /** 本会话的响应通道：backend 回包/推送的唯一入口 */
    private lateinit var responseActor: ActorRef

    init {
        registerHandler(NetMessage::class.java) { msg, _ -> onNetMessage(msg) }
    }

    override fun preStart() {
        super.preStart()
        responseActor = context().actorOf(
            Props.create(GatewayResponseActor::class.java) { GatewayResponseActor(clientSession) },
            "response"
        )
    }

    private suspend fun onNetMessage(msg: NetMessage) {
        log.info { "【客户端消息】sessionId=${clientSession.sessionId} rpcNum=${msg.msgId} errorCode=${msg.errorCode} bodyBytes=${msg.dataLength}" }
        msg.userId = clientSession.userId
        msg.sessionId = clientSession.sessionId

        when (GatewayZone.of(msg.msgId)) {
            GatewayZone.AUTH -> onAuth(msg)
            GatewayZone.LOGIC -> onLoginRequired(msg) { MessageRouterService.forwardToMainLogic(msg, responseActor) }
            GatewayZone.BATTLE -> onBattleRequired(msg, Rpc.RpcErrorCodeEnum.UserNotInBattle_VALUE) { MessageRouterService.forwardToBattle(msg, responseActor) }
            GatewayZone.CHAT -> onBattleRequired(msg, Rpc.RpcErrorCodeEnum.BattleChatTextErrorNotJoinBattle_VALUE) { MessageRouterService.forwardToChat(msg, responseActor) }
            // 区间外（含推送号段 20001+）：客户端不可主动请求
            null -> log.error { "【netMessage解析异常】not support rpcNum=${msg.msgId}" }
        }
    }

    /** 认证分区：注册/登录；已登录连接重复认证直接拒绝 */
    private suspend fun onAuth(msg: NetMessage) {
        if (clientSession.userId > 0) {
            log.error { "【认证异常】已登录用户不能重复注册/登录 userId=${clientSession.userId}" }
            replyError(msg, Rpc.RpcErrorCodeEnum.ServerError_VALUE)
            return
        }
        msg.userIp = clientSession.userIp
        val forwarded = when (msg.msgId) {
            Rpc.RpcNameEnum.Regist_VALUE -> MessageRouterService.forwardToMainLogic(msg, responseActor)
            else -> MessageRouterService.forwardToLogic(msg, responseActor)
        }
        if (!forwarded) {
            replyError(msg, Rpc.RpcErrorCodeEnum.ServerNotAvailable_VALUE)
        }
    }

    /** 须登录分区：未登录直接断开（原行为） */
    private suspend fun onLoginRequired(msg: NetMessage, forward: suspend () -> Boolean) {
        if (clientSession.userId <= 0) {
            clientSession.close()
            return
        }
        if (!forward()) {
            replyError(msg, Rpc.RpcErrorCodeEnum.ServerNotAvailable_VALUE)
        }
    }

    /** 须在对战中分区：未登录断开；不在对局回分区错误码 */
    private suspend fun onBattleRequired(msg: NetMessage, notInBattleCode: Int, forward: suspend () -> Boolean) {
        if (clientSession.userId <= 0) {
            clientSession.close()
            return
        }
        val battleId = BattleInfoService.getBattleUserIdToBattleId(clientSession.userId)
        if (battleId == null) {
            replyError(msg, notInBattleCode)
            return
        }
        if (!forward()) {
            replyError(msg, Rpc.RpcErrorCodeEnum.ServerNotAvailable_VALUE)
        }
    }

    /** 错误响应直达客户端：写 netty channel，不做任何 actor 间转发（杜绝回环） */
    private fun replyError(msg: NetMessage, errorCode: Int) {
        clientSession.write(NetMessage(msg.msgId, errorCode))
    }
}

