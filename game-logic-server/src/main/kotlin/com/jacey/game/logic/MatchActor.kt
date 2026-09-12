package com.jacey.game.logic

import akka.actor.ActorRef
import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.common.msg.LocalMessage
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.msg.RemoteMessage
import com.jacey.game.common.proto3.CommonMsg
import com.jacey.game.common.proto3.LocalServer
import com.jacey.game.common.proto3.RemoteServer
import com.jacey.game.common.proto3.Rpc
import com.jacey.game.db.service.PlayUserService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.jacey.game.common.framework.process.Dispatcher

/**
 * 匹配 Actor（原 MatchActor）
 * - 每秒驱动一次匹配计算（MatchService.doMatch）
 * - 接收 battle 创建战场成功的通知，推送匹配结果给对战双方
 */
class MatchActor : BaseMessageActor() {
    private var matchJob: Job? = null

    init {
        registerHandler(LocalMessage::class.java) { msg, _ ->
            when (msg.rpcNum) {
                LocalServer.LocalRpcNameEnum.LocalRpcLogicServerMatch_VALUE -> MatchService.doMatch()
            }
        }
        registerHandler(RemoteMessage::class.java) { msg, _ -> onBattleCreated(msg) }
    }

    private suspend fun onBattleCreated(remoteMsg: RemoteMessage) {
        when (remoteMsg.rpcNum) {
            RemoteServer.RemoteRpcNameEnum.RemoteRpcNoticeBattleServerCreateNewBattle_VALUE -> {
                val response = remoteMsg.getProto<RemoteServer.NoticeBattleServerCreateNewBattleResponse>() ?: return
                val battleRoomInfo = response.battleRoomInfo
                val userIds = battleRoomInfo.userIdsList
                val pushBuilder = CommonMsg.MatchResultPush.newBuilder()
                    .setIsSuccess(true)
                    .setBattleType(battleRoomInfo.battleType)
                    .setBattleId(battleRoomInfo.battleId)
                for (userId in userIds) {
                    val brief = PlayUserService.getUserBriefInfoByUserId(userId)
                    if (brief != null) pushBuilder.addUserBriefInfos(brief)
                }
                val netMsg = NetMessage(21001, pushBuilder) // RpcMatchResultPush
                for (userId in userIds) {
                    MessageRouter.sendNetMsgToOneUser(userId, netMsg)
                }
            }
        }
    }

    override fun preStart() {
        super.preStart()
        // 每秒给自己发一次匹配计算消息
        val msg: com.jacey.game.common.msg.IMessage =
            LocalMessage(LocalServer.LocalRpcNameEnum.LocalRpcLogicServerMatch_VALUE)
        matchJob = CoroutineScope(Dispatcher.Scheduler).launch {
            while (isActive) {
                self().tell(msg, ActorRef.noSender())
                kotlinx.coroutines.delay(1000)
            }
        }
    }
}
