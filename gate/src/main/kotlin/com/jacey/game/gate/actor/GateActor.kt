package com.jacey.game.gate.actor

import akka.actor.ActorRef
import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.common.msg.InternalMessageId
import com.jacey.game.common.msg.LocalMessage
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.proto3.CommonMsg
import com.jacey.game.common.proto3.Rpc
import com.jacey.game.db.AccountId
import com.jacey.game.db.LoginType
import com.jacey.game.gate.service.MessageRouterService
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * 每条客户端连接对应一个 GateActor。
 *
 * Gate 只做 LoginDebug 身份归一化、Lobby 选择与路由，不创建账号/角色，也不持有玩家业务数据。
 */
class GateActor(private val state: GateActorState) : BaseMessageActor() {
    private val log = KotlinLogging.logger {}

    init {
        registerHandler(GateClientMsg::class.java) { msg, _ -> onClientMsg(msg.netMessage) }
        registerHandler(NetMessage::class.java) { msg, _ -> onServerMsg(msg) }
        registerHandler(LocalMessage::class.java) { msg, _ ->
            if (msg.msgId == InternalMessageId.GATE_DISCONNECTED) onDisconnected()
        }
    }

    private suspend fun onClientMsg(msg: NetMessage) {
        msg.sessionId = state.sessionId
        msg.userId = state.playerId
        msg.userIp = state.userIp

        when (msg.msgId) {
            Rpc.RpcNameEnum.Login_VALUE -> processLogin(msg)
            Rpc.RpcNameEnum.Heartbeat_VALUE -> processLobby(msg)
            Rpc.RpcNameEnum.Match_VALUE,
            Rpc.RpcNameEnum.CancelMatch_VALUE -> processGlobalMatch(msg)
            in 6000..6999 -> processBattle(msg)
            in 10001..14000 -> processGlobal(msg)
            else -> replyAndClose(msg.msgId, Rpc.RpcErrorCodeEnum.ClientError_VALUE)
        }
    }

    private suspend fun processGlobalMatch(msg: NetMessage) {
        if (state.playerId <= 0) {
            replyAndClose(msg.msgId, Rpc.RpcErrorCodeEnum.ClientError_VALUE)
            return
        }
        if (!MessageRouterService.forwardToGlobal(msg, self())) {
            state.sendClient(NetMessage(msg.msgId, Rpc.RpcErrorCodeEnum.ServerNotAvailable_VALUE))
        }
    }

    private suspend fun processLogin(msg: NetMessage) {
        if (state.accountId != null) {
            replyAndClose(msg.msgId, Rpc.RpcErrorCodeEnum.LoginErrorAlreadyLogin_VALUE)
            return
        }
        val request = msg.getProto<CommonMsg.LoginRequest>()
        val loginName = request?.loginName?.trim().orEmpty()
        if (!validLoginName(loginName)) {
            replyAndClose(msg.msgId, Rpc.RpcErrorCodeEnum.ClientError_VALUE)
            return
        }

        val accountId = AccountId.createAccountIdByLoginTypeAndLoginName(LoginType.LoginDebug, loginName)
        val lobbyId = MessageRouterService.chooseLobby(accountId.toString())
        if (lobbyId == null) {
            replyAndClose(msg.msgId, Rpc.RpcErrorCodeEnum.ServerNotAvailable_VALUE)
            return
        }

        state.accountId = accountId.toString()
        state.lobbyId = lobbyId
        if (!MessageRouterService.forwardToLobby(msg, self(), lobbyId)) {
            replyAndClose(msg.msgId, Rpc.RpcErrorCodeEnum.ServerNotAvailable_VALUE)
        }
    }

    private suspend fun processLobby(msg: NetMessage) {
        val lobbyId = state.lobbyId
        if (lobbyId == null || state.playerId <= 0) {
            replyAndClose(msg.msgId, Rpc.RpcErrorCodeEnum.ClientError_VALUE)
            return
        }
        if (!MessageRouterService.forwardToLobby(msg, self(), lobbyId)) {
            replyAndClose(msg.msgId, Rpc.RpcErrorCodeEnum.ServerNotAvailable_VALUE)
        }
    }

    private suspend fun processBattle(msg: NetMessage) {
        if (state.playerId <= 0 || !MessageRouterService.forwardToGlobal(msg, self())) {
            state.sendClient(NetMessage(msg.msgId, Rpc.RpcErrorCodeEnum.UserNotInBattle_VALUE))
        }
    }

    private suspend fun processGlobal(msg: NetMessage) {
        if (state.playerId <= 0 || !MessageRouterService.forwardToGlobal(msg, self())) {
            state.sendClient(NetMessage(msg.msgId, Rpc.RpcErrorCodeEnum.ServerNotAvailable_VALUE))
        }
    }

    private fun onServerMsg(msg: NetMessage) {
        if (msg.msgId == Rpc.RpcNameEnum.Login_VALUE) {
            if (msg.errorCode != Rpc.RpcErrorCodeEnum.Ok_VALUE || msg.userId <= 0) {
                state.sendClient(msg)
                state.close()
                return
            }
            state.playerId = msg.userId
        }
        state.sendClient(msg)
        if (msg.msgId == Rpc.RpcNameEnum.RpcForceOfflinePush_VALUE) state.close()
    }

    private suspend fun onDisconnected() {
        val lobbyId = state.lobbyId
        if (lobbyId != null && state.playerId > 0) {
            val logout = NetMessage().apply {
                msgId = InternalMessageId.ACCOUNT_LOGOUT
                sessionId = state.sessionId
                userId = state.playerId
            }
            MessageRouterService.forwardToLobby(logout, self(), lobbyId)
        }
        context().stop(self())
    }

    private fun replyAndClose(msgId: Int, errorCode: Int) {
        log.warn { "gate rejects request sessionId=${state.sessionId}, msgId=$msgId, error=$errorCode" }
        state.sendClient(NetMessage(msgId, errorCode))
        state.close()
    }

    private fun validLoginName(value: String): Boolean =
        value.length in 1..64 && value.none { it.isISOControl() }
}
