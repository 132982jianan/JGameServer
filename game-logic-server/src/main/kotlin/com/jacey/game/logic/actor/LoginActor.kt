package com.jacey.game.logic.actor

import akka.actor.ActorRef
import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.common.exception.RpcErrorException
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.msg.RemoteMessage
import com.jacey.game.common.proto3.CommonEnum
import com.jacey.game.common.proto3.CommonMsg
import com.jacey.game.common.proto3.RemoteServer
import com.jacey.game.common.proto3.Rpc
import com.jacey.game.common.util.DateTimeUtil
import com.jacey.game.common.util.StringUtil
import com.jacey.game.db.service.PlayUserService
import com.jacey.game.db.redis.SessionIdRedis
import com.jacey.game.db.service.BattleInfoService
import com.jacey.game.common.framework.net.NodeRegister
import com.jacey.game.db.service.PlayStateService
import com.jacey.game.logic.service.MessageRouterService
import com.jacey.game.logic.service.OnlineClientService
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * 登录 Actor（原 LoginActor）
 * 校验账号密码 → 同账号二次登录踢旧 session → 绑定会话 → 更新状态 → 响应
 * 全流程 suspend：DB 查询、远端通知均非阻塞。
 */
class LoginActor : BaseMessageActor() {
    private val log = KotlinLogging.logger {}

    init {
        registerHandler(NetMessage::class.java) { msg, sender -> onLogin(msg, sender) }
    }

    private suspend fun onLogin(msg: NetMessage, sender: ActorRef?) {
        val sessionId = msg.sessionId
        val request = msg.getProto<CommonMsg.LoginRequest>()
        if (request == null) {
            throw RpcErrorException(Rpc.RpcErrorCodeEnum.ServerError_VALUE)
        }
        log.info { "【登录请求】username=${request.username}" }
        val username = request.username
        val passwordMD5 = request.passwordMD5

        // 1. 用户名密码非空
        if (StringUtil.isNullOrEmpty(username) || StringUtil.isNullOrEmpty(passwordMD5)) {
            throw RpcErrorException(Rpc.RpcErrorCodeEnum.ClientError_VALUE)
        }
        // 2. 用户存在
        val userId = PlayUserService.getUserIdByUsername(username)
            ?: throw RpcErrorException(Rpc.RpcErrorCodeEnum.LoginErrorUsernameIsNotExist_VALUE)
        // 3. 密码正确
        val userData = PlayUserService.getUserDataByUserId(userId)
            ?: throw RpcErrorException(Rpc.RpcErrorCodeEnum.LoginErrorUsernameIsNotExist_VALUE)
        if (userData.passwordMD5 != passwordMD5.uppercase()) {
            throw RpcErrorException(Rpc.RpcErrorCodeEnum.LoginErrorPasswordWrong_VALUE)
        }
        val userDataBuilder = userData.toBuilder()
        // 4. 封禁检查
        if (userData.hasForbidInfo()) {
            val forbidInfo = userData.forbidInfo
            if (DateTimeUtil.getCurrentTimestamp() < forbidInfo.forbidEndTimestamp) {
                throw RpcErrorException(Rpc.RpcErrorCodeEnum.LoginErrorForbid_VALUE)
            } else {
                userDataBuilder.clearForbidInfo()
            }
        }
        // 5. 同一账号二次登录：通知旧 session 所在 gateway 强制下线
        val oldSessionId = SessionIdRedis.getOneUserIdToSessionId(userId)
        if (oldSessionId != null) {
            val gatewayId = BattleInfoService.getOneSessionIdToGatewayId(oldSessionId)
            if (gatewayId == null) {
                log.error { "【登录异常】找不到 old session 对应 gatewayId, userId=$userId, oldSessionId=$oldSessionId" }
            } else {
                val pushBuilder = RemoteServer.LogicServerNoticeGatewayForceOfflineClientPush.newBuilder()
                    .setSessionId(oldSessionId)
                    .setForceOfflineReason(CommonEnum.ForceOfflineReasonEnum.ForceOfflineSameUserLogin)
                val remoteMessage = RemoteMessage(
                    RemoteServer.RemoteRpcNameEnum.RemoteRpcLogicServerNoticeGatewayForceOfflineClient_VALUE,
                    pushBuilder
                )
                if (!MessageRouterService.sendRemoteToGateway(remoteMessage, gatewayId)) {
                    log.error { "【消息推送异常】无法推送到 gateway, userId=$userId, gatewayId=$gatewayId" }
                }
            }
        }
        // 6. 绑定 sessionId <-> userId，并记录会话路由到本 logic（gateway 断线通知依据）
        SessionIdRedis.setOneUserIdToSessionId(userId, sessionId)
        SessionIdRedis.setOneSessionIdToUserId(sessionId, userId)
        BattleInfoService.setOneSessionIdToLogicServerId(sessionId, NodeRegister.selfId)
        // 7. 记录该玩家的 gateway ResponseActor
        OnlineClientService.addSessionIdToGatewayResponseActor(sessionId, sender)
        // 8. 修改玩家在线状态
        PlayStateService.changeUserOnlineState(userId, true)
        // 9. 更新登录信息
        userDataBuilder.setLastLoginIp(msg.userIp)
        userDataBuilder.setLastLoginTimestamp(DateTimeUtil.getCurrentTimestamp())
        PlayUserService.update(userDataBuilder.build())
        // 10. 构造响应
        val builder = CommonMsg.LoginResponse.newBuilder()
            .setUserInfo(PlayUserService.getUserInfoByUserId(userId))
        val respMsg = NetMessage(Rpc.RpcNameEnum.Login_VALUE, builder)
        respMsg.userId = userId
        sender?.tell(respMsg, ActorRef.noSender())
    }
}
