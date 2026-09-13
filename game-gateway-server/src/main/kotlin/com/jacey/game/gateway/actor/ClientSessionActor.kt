package com.jacey.game.gateway.actor

import akka.actor.ActorRef
import akka.actor.Props
import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.proto3.Rpc
import com.jacey.game.db.service.BattleInfoService
import com.jacey.game.gateway.service.MessageRouterService
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

        when (msg.msgId) {
            Rpc.RpcNameEnum.Regist_VALUE -> {
                if (clientSession.userId > 0) {
                    log.error { "【注册异常】已登录用户不能重复注册 userId=${clientSession.userId}" }
                    replyError(msg, Rpc.RpcErrorCodeEnum.ServerError_VALUE)
                    return
                }
                msg.userIp = clientSession.userIp
                if (!MessageRouterService.forwardToMainLogic(msg, responseActor)) {
                    replyError(msg, Rpc.RpcErrorCodeEnum.ServerNotAvailable_VALUE)
                }
            }
            Rpc.RpcNameEnum.Login_VALUE -> {
                if (clientSession.userId > 0) {
                    log.error { "【登录异常】已登录用户不能重复登录 userId=${clientSession.userId}" }
                    replyError(msg, Rpc.RpcErrorCodeEnum.ServerError_VALUE)
                    return
                }
                msg.userIp = clientSession.userIp
                if (!MessageRouterService.forwardToLogic(msg, responseActor)) {
                    replyError(msg, Rpc.RpcErrorCodeEnum.ServerNotAvailable_VALUE)
                }
            }
            Rpc.RpcNameEnum.Match_VALUE, Rpc.RpcNameEnum.CancelMatch_VALUE -> {
                if (clientSession.userId > 0) {
                    if (!MessageRouterService.forwardToMainLogic(msg, responseActor)) {
                        replyError(msg, Rpc.RpcErrorCodeEnum.ServerNotAvailable_VALUE)
                    }
                } else {
                    clientSession.close()
                }
            }
            Rpc.RpcNameEnum.GetBattleInfo_VALUE,
            Rpc.RpcNameEnum.Concede_VALUE,
            Rpc.RpcNameEnum.PlacePieces_VALUE,
            Rpc.RpcNameEnum.ReadyToStartGame_VALUE -> {
                if (clientSession.userId > 0) {
                    val battleId = BattleInfoService.getBattleUserIdToBattleId(clientSession.userId)
                    if (battleId != null) {
                        if (!MessageRouterService.forwardToBattle(msg, responseActor)) {
                            replyError(msg, Rpc.RpcErrorCodeEnum.ServerNotAvailable_VALUE)
                        }
                    } else {
                        replyError(msg, Rpc.RpcErrorCodeEnum.UserNotInBattle_VALUE)
                    }
                } else {
                    clientSession.close()
                }
            }
            Rpc.RpcNameEnum.BattleChatText_VALUE, Rpc.RpcNameEnum.JoinChatRoom_VALUE -> {
                if (clientSession.userId > 0) {
                    val battleId = BattleInfoService.getBattleUserIdToBattleId(clientSession.userId)
                    if (battleId != null) {
                        if (!MessageRouterService.forwardToChat(msg, responseActor)) {
                            replyError(msg, Rpc.RpcErrorCodeEnum.ServerNotAvailable_VALUE)
                        }
                    } else {
                        replyError(msg, Rpc.RpcErrorCodeEnum.BattleChatTextErrorNotJoinBattle_VALUE)
                    }
                }
            }
            else -> {
                log.error { "【netMessage解析异常】not support rpcNum=${msg.msgId}" }
            }
        }
    }

    /** 错误响应直达客户端：写 netty channel，不做任何 actor 间转发（杜绝回环） */
    private fun replyError(msg: NetMessage, errorCode: Int) {
        clientSession.write(NetMessage(msg.msgId, errorCode))
    }
}

