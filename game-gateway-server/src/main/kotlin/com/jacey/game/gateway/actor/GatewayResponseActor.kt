package com.jacey.game.gateway.actor

import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.proto3.Rpc
import com.jacey.game.gateway.session.ClientSession
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * 会话响应 Actor（原 ResponseActor）
 *
 * backend（logic/battle/chat）的回包与推送以本 actor 为 sender，
 * 收到的任何 NetMessage 都只是"要写给客户端的响应"，直接落 channel：
 * - 与 ClientSessionActor（请求通道）物理分离，响应不会被再次路由
 * - 登录成功响应在此绑定 session.userId
 */
class GatewayResponseActor(private val session: ClientSession) : BaseMessageActor() {
    private val log = KotlinLogging.logger {}

    init {
        registerHandler(NetMessage::class.java) { msg, _ ->
            if (msg.msgId == Rpc.RpcNameEnum.Login_VALUE
                && msg.errorCode == Rpc.RpcErrorCodeEnum.Ok_VALUE
            ) {
                session.userId = msg.userId
            }
            session.write(msg)
        }
    }
}