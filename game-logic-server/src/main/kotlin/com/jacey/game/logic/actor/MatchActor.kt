package com.jacey.game.logic.actor

import akka.actor.ActorRef
import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.common.msg.LocalMessage
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.msg.RemoteMessage
import com.jacey.game.common.proto3.CommonEnum
import com.jacey.game.common.proto3.CommonMsg
import com.jacey.game.db.service.PlayUserService
import com.jacey.game.common.proto3.LocalServer
import com.jacey.game.common.proto3.RemoteServer
import com.jacey.game.common.proto3.Rpc
import com.jacey.game.db.service.PlayStateService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.jacey.game.common.framework.process.Dispatcher
import com.jacey.game.common.msg.IMessage
import com.jacey.game.logic.service.MatchService
import com.jacey.game.logic.service.MessageRouterService
import kotlinx.coroutines.delay

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
        registerHandler(NetMessage::class.java) { msg, sender -> onNet(msg, sender) }
    }

    /** 客户端匹配/取消匹配请求 */
    private suspend fun onNet(msg: NetMessage, sender: ActorRef?) {
        when (msg.rpcNum) {
            Rpc.RpcNameEnum.Match_VALUE -> {
                val request = msg.getProto<CommonMsg.MatchRequest>() ?: run {
                    sender?.tell(NetMessage(msg.rpcNum, Rpc.RpcErrorCodeEnum.ServerError_VALUE), null)
                    return
                }
                val ok = MatchService.addMatchPlayer(msg.userId, request.battleType)
                sender?.tell(
                    NetMessage(
                        msg.rpcNum,
                        if (ok) Rpc.RpcErrorCodeEnum.Ok_VALUE else Rpc.RpcErrorCodeEnum.ServerError_VALUE
                    ),
                    null
                )
            }
            Rpc.RpcNameEnum.CancelMatch_VALUE -> {
                // CancelMatchRequest 为空消息：从玩家状态取当前匹配类型
                val state = PlayStateService.getPlayStateByUserId(msg.userId)
                val battleType = state?.battleType
                    ?.let { CommonEnum.BattleTypeEnum.forNumber(it) }
                val removed = MatchService.removeMatchPlayer(msg.userId, battleType)
                sender?.tell(
                    NetMessage(
                        msg.rpcNum,
                        if (removed) Rpc.RpcErrorCodeEnum.Ok_VALUE else Rpc.RpcErrorCodeEnum.CancelMatchErrorNotMatching_VALUE
                    ),
                    null
                )
            }
        }
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
                    MessageRouterService.sendNetMsgToOneUser(userId, netMsg)
                }
            }
        }
    }

    override fun preStart() {
        super.preStart()
        // 每秒给自己发一次匹配计算消息
        val msg: IMessage =
            LocalMessage(LocalServer.LocalRpcNameEnum.LocalRpcLogicServerMatch_VALUE)
        matchJob = CoroutineScope(Dispatcher.Scheduler).launch {
            while (isActive) {
                self().tell(msg, ActorRef.noSender())
                delay(1000)
            }
        }
    }
}
