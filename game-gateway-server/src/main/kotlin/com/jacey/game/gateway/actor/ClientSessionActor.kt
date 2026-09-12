package com.jacey.game.gateway.actor

import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.proto3.CommonEnum
import com.jacey.game.common.proto3.CommonMsg
import com.jacey.game.common.proto3.Rpc
import com.jacey.game.db.service.BattleInfoService
import com.jacey.game.db.redis.SessionIdRedis
import com.jacey.game.gateway.MessageRouter
import com.jacey.game.gateway.Session
import com.jacey.game.gateway.SessionManager
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * 客户端会话 Actor（原 ChannelActor）
 *
 * 每个客户端连接绑定一个；处理客户端 NetMessage：
 * - Regist/Login → logic
 * - Match/CancelMatch → main logic
 * - 对战操作 → battle（须在对战中）
 * - 聊天 → chat（须在对战中）
 *
 * 全部 handler 为 suspend：转发等待、Redis 查询不阻塞线程。
 */
class ClientSessionActor(private val session: Session) : BaseMessageActor() {
    private val log = KotlinLogging.logger {}

    init {
        registerHandler(NetMessage::class.java) { msg, _ -> onNetMessage(msg) }
        registerHandler(ResponseMessage::class.java) { msg, _ -> onResponse(msg) }
    }

    private suspend fun onNetMessage(msg: NetMessage) {
        log.info { "【客户端消息】sessionId=${session.sessionId} rpcNum=${msg.rpcNum} errorCode=${msg.errorCode} bodyBytes=${msg.dataLength}" }
        val sessionId = session.sessionId
        msg.userId = session.userId
        msg.sessionId = sessionId

        when (msg.rpcNum) {
            Rpc.RpcNameEnum.Regist_VALUE -> {
                if (session.userId > 0) {
                    log.error { "【注册异常】已登录用户不能重复注册 userId=${session.userId}" }
                    MessageRouter.sendErrorToClient(msg, Rpc.RpcErrorCodeEnum.ServerError_VALUE, self())
                    return
                }
                msg.userIp = session.userIp
                if (!MessageRouter.forwardToMainLogic(msg, self())) {
                    MessageRouter.sendErrorToClient(msg, Rpc.RpcErrorCodeEnum.ServerNotAvailable_VALUE, self())
                }
            }
            Rpc.RpcNameEnum.Login_VALUE -> {
                if (session.userId > 0) {
                    log.error { "【登录异常】已登录用户不能重复登录 userId=${session.userId}" }
                    MessageRouter.sendErrorToClient(msg, Rpc.RpcErrorCodeEnum.ServerError_VALUE, self())
                    return
                }
                msg.userIp = session.userIp
                if (!MessageRouter.forwardToLogic(msg, self())) {
                    MessageRouter.sendErrorToClient(msg, Rpc.RpcErrorCodeEnum.ServerNotAvailable_VALUE, self())
                }
            }
            Rpc.RpcNameEnum.Match_VALUE, Rpc.RpcNameEnum.CancelMatch_VALUE -> {
                if (session.userId > 0) {
                    if (!MessageRouter.forwardToMainLogic(msg, self())) {
                        MessageRouter.sendErrorToClient(msg, Rpc.RpcErrorCodeEnum.ServerNotAvailable_VALUE, self())
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
                        if (!MessageRouter.forwardToBattle(msg, self())) {
                            MessageRouter.sendErrorToClient(msg, Rpc.RpcErrorCodeEnum.ServerNotAvailable_VALUE, self())
                        }
                    } else {
                        MessageRouter.sendErrorToClient(msg, Rpc.RpcErrorCodeEnum.UserNotInBattle_VALUE, self())
                    }
                } else {
                    session.close()
                }
            }
            Rpc.RpcNameEnum.BattleChatText_VALUE, Rpc.RpcNameEnum.JoinChatRoom_VALUE -> {
                if (session.userId > 0) {
                    val battleId = BattleInfoService.getBattleUserIdToBattleId(session.userId)
                    if (battleId != null) {
                        if (!MessageRouter.forwardToChat(msg, self())) {
                            MessageRouter.sendErrorToClient(msg, Rpc.RpcErrorCodeEnum.ServerNotAvailable_VALUE, self())
                        }
                    } else {
                        MessageRouter.sendErrorToClient(msg, Rpc.RpcErrorCodeEnum.BattleChatTextErrorNotJoinBattle_VALUE, self())
                    }
                }
            }
            else -> {
                log.error { "【netMessage解析异常】not support rpcNum=${msg.rpcNum}" }
            }
        }
    }

    /** 处理远端服务器响应：写回客户端；登录成功同时绑定 userId */
    private suspend fun onResponse(msg: ResponseMessage) {
        val net = msg.netMessage
        if (net.rpcNum == Rpc.RpcNameEnum.Login_VALUE &&
            net.errorCode == Rpc.RpcErrorCodeEnum.Ok_VALUE) {
            session.userId = net.userId
        }
        session.write(net)
    }
}

/** 远端响应信封（原 NetResponseMessage 的 gateway 侧用法） */
class ResponseMessage(val netMessage: NetMessage)
