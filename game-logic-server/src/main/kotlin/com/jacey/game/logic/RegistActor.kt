package com.jacey.game.logic

import akka.actor.ActorRef
import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.common.constants.SystemConfigKey
import com.jacey.game.common.exception.RpcErrorException
import com.jacey.game.common.msg.LocalMessage
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.proto3.LocalServer
import com.jacey.game.common.proto3.RemoteServer
import com.jacey.game.common.proto3.Rpc
import com.jacey.game.common.util.MD5Util
import com.jacey.game.common.util.StringUtil
import com.jacey.game.db.entity.PlayUserEntity
import com.jacey.game.db.service.PlayUserService
import com.jacey.game.logic.TableConfig.systemInt
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.Date

/**
 * 注册 Actor（原 RegistActor）：处理用户注册协议
 */
class RegistActor : BaseMessageActor() {
    private val log = KotlinLogging.logger {}

    init {
        registerHandler(NetMessage::class.java) { msg, sender -> onRegist(msg, sender) }
    }

    private suspend fun onRegist(msg: NetMessage, sender: ActorRef?) {
        val request = msg.getProto<com.jacey.game.common.proto3.CommonMsg.RegistRequest>()
        if (request == null) {
            sendError(msg, Rpc.RpcErrorCodeEnum.ServerError_VALUE, sender)
            return
        }
        log.info { "【req Regist】username=${request.username}" }
        val username: String = request.username
        val password: String = request.password
        if (!isLegalUsername(username)) throw RpcErrorException(Rpc.RpcErrorCodeEnum.RegisErrorUsernameIllegal_VALUE)
        if (!isLegalPassword(password)) throw RpcErrorException(Rpc.RpcErrorCodeEnum.RegisErrorPasswordIllegal_VALUE)
        if (PlayUserService.hasUsername(username)) throw RpcErrorException(Rpc.RpcErrorCodeEnum.RegisErrorUsernameIsExist_VALUE)

        val entity = PlayUserEntity(
            _id = 0,
            username = username,
            nickname = username,
            passwordMD5 = MD5Util.md5(password),
            registIp = msg.userIp,
            registTimestamp = Date(),
        )
        PlayUserService.createNewUser(entity)

        val builder = com.jacey.game.common.proto3.CommonMsg.RegistResponse.newBuilder()
        sender?.tell(NetMessage(Rpc.RpcNameEnum.Regist_VALUE, builder), ActorRef.noSender())
    }

    private fun sendError(msg: NetMessage, errorCode: Int, sender: ActorRef?) {
        sender?.tell(NetMessage(msg.rpcNum, errorCode), ActorRef.noSender())
    }

    // username 长度限制 + 只能数字/字母
    private fun isLegalUsername(username: String?): Boolean {
        val max = systemInt(SystemConfigKey.USERNAME_MAX_LENGTH) ?: return false
        if (StringUtil.isNullOrEmpty(username) || username!!.length > max) return false
        return username.all { StringUtil.isLetterChar(it) || StringUtil.isDigitChar(it) }
    }

    // password 长度限制 + 只能数字/字母
    private fun isLegalPassword(password: String?): Boolean {
        val min = systemInt(SystemConfigKey.PASSWORD_MIN_LENGTH) ?: return false
        val max = systemInt(SystemConfigKey.PASSWORD_MAX_LENGTH) ?: return false
        if (StringUtil.isNullOrEmpty(password) || password!!.length < min || password.length > max) return false
        return password.all { StringUtil.isLetterChar(it) || StringUtil.isDigitChar(it) }
    }
}
