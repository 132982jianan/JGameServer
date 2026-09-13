package com.jacey.game.battle.service

import akka.actor.ActorRef
import com.jacey.game.battle.actor.BaseBattleActor
import com.jacey.game.common.msg.LocalMessage
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.msg.RemoteMessage
import com.jacey.game.common.proto3.CommonEnum
import com.jacey.game.common.proto3.LocalServer
import com.jacey.game.common.proto3.RemoteServer
import com.jacey.game.common.proto3.Rpc
import com.jacey.game.common.framework.akka.AkkaService
import com.jacey.game.common.framework.net.NacosService
import com.jacey.game.db.redis.SessionIdRedis
import com.jacey.game.db.service.BattleInfoService
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

/**
 * 对战房间管理（object 单例，原 BattleRoomManagerActor + BaseBattleActor + OnlineClientManager）
 *
 * - battleId -> BaseBattleActorRef：一场对战一个 actor（协程串行处理该对战所有请求）
 * - 事件引擎：todoList 顺序消费 StartTurn/EndTurn/PlacePieces/GameOver 事件
 */
object BattleRoomActorManagerService {
    private val logger = KotlinLogging.logger {}

    //<战斗id，战斗actor>
    private val battleIdToBattleActorRefMap = ConcurrentHashMap<String, ActorRef>()

    private val sessionIdToGatewayResponseActor = ConcurrentHashMap<Int, ActorRef>()

    fun bindSessionIdWithGatewayResponseActorRef(sessionId: Int, actor: ActorRef?) {
        if (actor != null) {
            sessionIdToGatewayResponseActor[sessionId] = actor
        }
    }

    fun removeGatewayResponseActor(sessionId: Int) {
        sessionIdToGatewayResponseActor.remove(sessionId)
    }

    fun getGatewayResponseActor(sessionId: Int): ActorRef? {
        return sessionIdToGatewayResponseActor[sessionId]
    }

    /** 创建战场（原 BattleRoomManagerActor.noticeBattleServerCreateNewBattle）；失败也回包（askAwait 调用方依赖回复判定） */
    suspend fun createNewBattle(request: RemoteServer.NoticeBattleServerCreateNewBattleRequest, sender: ActorRef?) {
        val battleRoomInfo = request.battleRoomInfo
        val battleType = battleRoomInfo.battleType
        val battleId = battleRoomInfo.battleId
        val userIds = battleRoomInfo.userIdsList
        when (battleType.number) {
            CommonEnum.BattleTypeEnum.BattleTypeTwoPlayer_VALUE -> {
                // 1.标记进行中的对战
                BattleInfoService.addPlayingBattleId(battleId, CommonEnum.BattleTypeEnum.BattleTypeTwoPlayer)

                // 2.创建专属 BaseBattleActor
                val actor = AkkaService.create<BaseBattleActor>("battle-$battleId")
                battleIdToBattleActorRefMap[battleId] = actor

                // 3.userId <-> battleId、battleId <-> 本服务器绑定
                for (userId in userIds) {
                    BattleInfoService.setBattleUserIdToBattleId(userId, battleId)
                }
                BattleInfoService.setOneBattleIdToBattleServerId(battleId, NacosService.selfNodeId)

                // 4.通知 BaseBattleActor 初始化战场
                val localMessage = LocalMessage(
                    LocalServer.LocalRpcNameEnum.LocalRpcBattleServerInitBattle_VALUE,
                    battleRoomInfo
                )
                actor.tell(localMessage, ActorRef.noSender())
                // 5.响应创建成功
                val builder = RemoteServer.NoticeBattleServerCreateNewBattleResponse.newBuilder()
                    .setBattleRoomInfo(battleRoomInfo)
                sender?.tell(
                    RemoteMessage(
                        RemoteServer.RemoteRpcNameEnum.RemoteRpcNoticeBattleServerCreateNewBattle_VALUE,
                        builder
                    ),
                    ActorRef.noSender()
                )
            }

            else -> {
                logger.error { "createNewBattle: not support battleType=$battleType" }
                sender?.tell(
                    RemoteMessage(
                        RemoteServer.RemoteRpcNameEnum.RemoteRpcNoticeBattleServerCreateNewBattle_VALUE,
                        RemoteServer.RemoteRpcErrorCodeEnum.RemoteRpcServerError_VALUE
                    ),
                    ActorRef.noSender()
                )
            }
        }
    }

    /** 客户端对战请求二次分发（原 proxyNetMessageInvoke） */
    suspend fun proxyNetMessage(msg: NetMessage, sender: ActorRef?) {
        val userId = msg.userId
        val sessionId = msg.sessionId
        // 对战操作统一由 BattleActionActor 处理（房间生命周期消息走 LocalMessage/RemoteMessage）
        val actionActor = BattleAkkaRefManagerService.battleActionActor
        if (actionActor == null) {
            sender?.tell(NetMessage(msg.msgId, Rpc.RpcErrorCodeEnum.ServerError_VALUE), null)
            return
        }
        val battleId = BattleInfoService.getBattleUserIdToBattleId(userId)
        if (battleId == null) {
            sender?.tell(NetMessage(msg.msgId, Rpc.RpcErrorCodeEnum.UserNotInBattle_VALUE), null)
            return
        }
        bindSessionIdWithGatewayResponseActorRef(sessionId, sender)
        actionActor.tell(msg, sender)
    }

    /** 对战结束清理（原 removeBattleActor） */
    suspend fun removeBattle(battleId: String) {
        val actor = battleIdToBattleActorRefMap.remove(battleId)
        val userIds = BattleInfoService.getOneBattleUserIds(battleId)
        for (userId in userIds) {
            BattleInfoService.removeBattleUserIdToBattleId(userId)
        }
        BattleInfoService.removeOneBattleIdToBattleServerId(battleId)
    }

    /** 推送消息给某个用户（经 gateway ResponseActor 转发） */
    suspend fun sendNetMsgToOneUser(userId: Int, netMsg: NetMessage) {
        val sessionId = SessionIdRedis.getOneUserIdToSessionId(userId)
        if (sessionId != null) {
            getGatewayResponseActor(sessionId)?.tell(netMsg, ActorRef.noSender())
        }
    }
}

