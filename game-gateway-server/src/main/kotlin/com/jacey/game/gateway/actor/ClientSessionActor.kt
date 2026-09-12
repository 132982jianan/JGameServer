package com.jacey.game.gateway.actor

import akka.actor.ActorRef
import akka.actor.Props
import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.proto3.Rpc
import com.jacey.game.db.service.BattleInfoService
import com.jacey.game.gateway.MessageRouter
import com.jacey.game.gateway.Session
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
class ClientSessionActor(private val session: Session) : BaseMessageActor() {
    private val log = KotlinLogging.logger {}

    /** 本会话的响应通道：backend 回包/推送的唯一入口 */
    private lateinit var responseActor: ActorRef

    init {
        registerHandler(NetMessage::class.java) { msg, _ -> onNetMessage(msg) }
    }

    override fun preStart() {
        super.preStart()
        responseActor = context().actorOf(
            Props.create(GatewayResponseActor::class.java) { GatewayResponseActor(session) },
            "response"
        )
    }

    private suspend fun onNetMessage(msg: NetMessage) {
        log.info { "【客户端消息】sessionId=${session.sessionId} rpcNum=${msg.rpcNum} errorCode=${msg.errorCode} bodyBytes=${msg.dataLength}" }
        msg.userId = session.userId
        msg.sessionId = session.sessionId

        when (msg.rpcNum) {
            Rpc.RpcNameEnum.Regist_VALUE -> {
                if (session.userId > 0) {
                    log.error { "【注册异常】已登录用户不能重复注册 userId=${session.userId}" }
                    replyError(msg, Rpc.RpcErrorCodeEnum.ServerError_VALUE)
                    return
                }
                msg.userIp = session.userIp
                if (!MessageRouter.forwardToMainLogic(msg, responseActor)) {
                    replyError(msg, Rpc.RpcErrorCodeEnum.ServerNotAvailable_VALUE)
                }
            }
            Rpc.RpcNameEnum.Login_VALUE -> {
                if (session.userId > 0) {
                    log.error { "【登录异常】已登录用户不能重复登录 userId=${session.userId}" }
                    replyError(msg, Rpc.RpcErrorCodeEnum.ServerError_VALUE)
                    return
                }
                msg.userIp = session.userIp
                if (!MessageRouter.forwardToLogic(msg, responseActor)) {
                    replyError(msg, Rpc.RpcErrorCodeEnum.ServerNotAvailable_VALUE)
                }
            }
            Rpc.RpcNameEnum.Match_VALUE, Rpc.RpcNameEnum.CancelMatch_VALUE -> {
                if (session.userId > 0) {
                    if (!MessageRouter.forwardToMainLogic(msg, responseActor)) {
                        replyError(msg, Rpc.RpcErrorCodeEnum.ServerNotAvailable_VALUE)
                    }
                } else {
                    session.close()
                }
            }
            Rpc.RpcNameEnum.GetBattleInfo_VALUE,
            Rpc.RpcNameEnum.Concede_VALUE,
            Rpc.RpcNameEnum.PlacePieces_VALUE,
            Rpc.RpcNameEnum.ReadyToStartGame_VALUE -> {
                if (session.userId > 0) {
                    val battleId = BattleInfoService.getBattleUserIdToBattleId(session.userId)
                    if (battleId != null) {
                        if (!MessageRouter.forwardToBattle(msg, responseActor)) {
                            replyError(msg, Rpc.RpcErrorCodeEnum.ServerNotAvailable_VALUE)
                        }
                    } else {
                        replyError(msg, Rpc.RpcErrorCodeEnum.UserNotInBattle_VALUE)
                    }
                } else {
                    session.close()
                }
            }
            Rpc.RpcNameEnum.BattleChatText_VALUE, Rpc.RpcNameEnum.JoinChatRoom_VALUE -> {
                if (session.userId > 0) {
                    val battleId = BattleInfoService.getBattleUserIdToBattleId(session.userId)
                    if (battleId != null) {
                        if (!MessageRouter.forwardToChat(msg, responseActor)) {
                            replyError(msg, Rpc.RpcErrorCodeEnum.ServerNotAvailable_VALUE)
                        }
                    } else {
                        replyError(msg, Rpc.RpcErrorCodeEnum.BattleChatTextErrorNotJoinBattle_VALUE)
                    }
                }
            }
            else -> {
                log.error { "【netMessage解析异常】not support rpcNum=${msg.rpcNum}" }
            }
        }
    }

    /** 错误响应直达客户端：写 netty channel，不做任何 actor 间转发（杜绝回环） */
    private fun replyError(msg: NetMessage, errorCode: Int) {
        session.write(NetMessage(msg.rpcNum, errorCode))
    }
}

/**
 * 会话响应 Actor（原 ResponseActor）
 *
 * backend（logic/battle/chat）的回包与推送以本 actor 为 sender，
 * 收到的任何 NetMessage 都只是"要写给客户端的响应"，直接落 channel：
 * - 与 ClientSessionActor（请求通道）物理分离，响应不会被再次路由
 * - 登录成功响应在此绑定 session.userId
 */
class GatewayResponseActor(private val session: Session) : BaseMessageActor() {
    private val log = KotlinLogging.logger {}

    init {
        registerHandler(NetMessage::class.java) { msg, _ ->
            if (msg.rpcNum == Rpc.RpcNameEnum.Login_VALUE &&
                msg.errorCode == Rpc.RpcErrorCodeEnum.Ok_VALUE
            ) {
                session.userId = msg.userId
            }
            session.write(msg)
        }
    }
}
