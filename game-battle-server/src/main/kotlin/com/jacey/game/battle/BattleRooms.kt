package com.jacey.game.battle

import akka.actor.ActorRef
import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.common.msg.LocalMessage
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.msg.RemoteMessage
import com.jacey.game.common.proto3.BaseBattle
import com.jacey.game.common.proto3.CommonEnum
import com.jacey.game.common.proto3.LocalServer
import com.jacey.game.common.proto3.RemoteServer
import com.jacey.game.common.proto3.Rpc
import com.jacey.game.common.exception.RpcErrorException
import com.jacey.game.common.util.DateTimeUtil
import com.jacey.game.common.framework.akka.AkkaService
import com.jacey.game.db.entity.BattleRecordEntity
import com.jacey.game.db.service.BattleInfoService
import com.jacey.game.db.service.BattleRecordService
import com.jacey.game.db.service.PlayStateService
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.Date

/**
 * 对战房间管理（object 单例，原 BattleRoomManagerActor + BaseBattleActor + OnlineClientManager）
 *
 * - battleId -> BaseBattleActorRef：一场对战一个 actor（协程串行处理该对战所有请求）
 * - 事件引擎：todoList 顺序消费 StartTurn/EndTurn/PlacePieces/GameOver 事件
 */
object BattleRooms {
    private val logger = KotlinLogging.logger {}
    private val battleIdToBattleActor = java.util.concurrent.ConcurrentHashMap<String, ActorRef>()
    private val sessionIdToGatewayResponseActor = java.util.concurrent.ConcurrentHashMap<Int, ActorRef>()

    val battleCount: Int get() = battleIdToBattleActor.size

    fun getBattleActor(battleId: String): ActorRef? = battleIdToBattleActor[battleId]

    fun addGatewayResponseActor(sessionId: Int, actor: ActorRef?) {
        if (actor != null) sessionIdToGatewayResponseActor[sessionId] = actor
    }

    fun removeGatewayResponseActor(sessionId: Int) {
        sessionIdToGatewayResponseActor.remove(sessionId)
    }

    fun getGatewayResponseActor(sessionId: Int): ActorRef? = sessionIdToGatewayResponseActor[sessionId]

    /** 创建战场（原 BattleRoomManagerActor.noticeBattleServerCreateNewBattle） */
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
                battleIdToBattleActor[battleId] = actor
                // 3.userId <-> battleId、battleId <-> 本服务器绑定
                for (userId in userIds) {
                    BattleInfoService.setBattleUserIdToBattleId(userId, battleId)
                }
                BattleInfoService.setOneBattleIdToBattleServerId(battleId, NodeSelf.serverId)
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
                    RemoteMessage(RemoteServer.RemoteRpcNameEnum.RemoteRpcNoticeBattleServerCreateNewBattle_VALUE, builder),
                    ActorRef.noSender()
                )
            }
            else -> logger.error { "createNewBattle: not support battleType=$battleType" }
        }
    }

    /** 客户端对战请求二次分发（原 proxyNetMessageInvoke） */
    suspend fun proxyNetMessage(msg: NetMessage, sender: ActorRef?) {
        val userId = msg.userId
        val sessionId = msg.sessionId
        // 对战操作统一由 BattleActionActor 处理（房间生命周期消息走 LocalMessage/RemoteMessage）
        val actionActor = AkkaRefsB.battleActionActor
        if (actionActor == null) {
            sender?.tell(NetMessage(msg.msgId, Rpc.RpcErrorCodeEnum.ServerError_VALUE), null)
            return
        }
        val battleId = BattleInfoService.getBattleUserIdToBattleId(userId)
        if (battleId == null) {
            sender?.tell(NetMessage(msg.msgId, Rpc.RpcErrorCodeEnum.UserNotInBattle_VALUE), null)
            return
        }
        addGatewayResponseActor(sessionId, sender)
        actionActor.tell(msg, sender)
    }

    /** 对战结束清理（原 removeBattleActor） */
    suspend fun removeBattle(battleId: String) {
        val actor = battleIdToBattleActor.remove(battleId)
        val userIds = BattleInfoService.getOneBattleUserIds(battleId)
        for (userId in userIds) {
            BattleInfoService.removeBattleUserIdToBattleId(userId)
        }
        BattleInfoService.removeOneBattleIdToBattleServerId(battleId)
        if (MessageRouterB.isConnectedToGm) {
            // 负载更新由 NodeRegister 周期上报替代
        }
    }

    /** 推送消息给某个用户（经 gateway ResponseActor 转发） */
    suspend fun sendNetMsgToOneUser(userId: Int, netMsg: NetMessage) {
        val sessionId = com.jacey.game.db.redis.SessionIdRedis.getOneUserIdToSessionId(userId)
        if (sessionId != null) {
            getGatewayResponseActor(sessionId)?.tell(netMsg, ActorRef.noSender())
        }
    }
}

/** 本节点注册信息便捷访问 */
object NodeSelf {
    val serverId: Int get() = com.jacey.game.common.framework.net.NacosService.selfNodeId
}

/**
 * 一场对战一个 Actor（原 BaseBattleActor）：战场初始化 + 事件引擎
 */
class BaseBattleActor : BaseMessageActor() {
    private val log = KotlinLogging.logger {}

    init {
        registerHandler(LocalMessage::class.java) { msg, _ -> onInit(msg) }
    }

    /** 战场初始化（原 initBattle → startFirstTurn） */
    private suspend fun onInit(msg: LocalMessage) {
        val battleRoomInfo = msg.lite as? RemoteServer.BattleRoomInfo ?: return
        val battleId = battleRoomInfo.battleId
        val userIds = battleRoomInfo.userIdsList
        // 初始化战场数据
        BattleInfoService.initOneBattleUserIds(battleId, userIds)
        BattleInfoService.initAllBattleCellInfo(battleId, listOf(0, 0, 0, 0, 0, 0, 0, 0, 0))
        BattleInfoService.setOneBattleStartTimestamp(battleId, DateTimeUtil.getCurrentTimestamp())
        BattleInfoService.initOneBattleNotReadyUserIds(battleId, userIds)
        // 通知聊天服务器创建聊天室
        val chatRoomInfo = RemoteServer.ChatRoomInfo.newBuilder()
            .setChatRoomType(CommonEnum.ChatRoomTypeEnum.TwoPlayerBattleChatRoomType)
            .setBattleId(battleId)
        val builder = RemoteServer.NoticeChatServerCreateNewBattleChatRoomRequest.newBuilder()
            .setChatRoomInfo(chatRoomInfo)
        val remoteMessage = RemoteMessage(
            RemoteServer.RemoteRpcNameEnum.RemoteRpcNoticeChatServerCreateNewBattleChatRoom_VALUE,
            builder
        )
        val chatRef = com.jacey.game.common.framework.net.NacosService.getRandomActorRefByNodeKind(
            com.jacey.game.common.framework.net.NodeKind.chat)
        chatRef?.tell(remoteMessage, self())
    }

    companion object {
        /** 开始第一回合（原 startFirstTurn） */
        suspend fun startFirstTurn(battleId: String) {
            val userIds = BattleInfoService.getOneBattleUserIds(battleId)
            if (userIds.isEmpty()) return
            val builder = BaseBattle.CurrentTurnInfo.newBuilder()
                .setUserId(userIds[userIds.size - 1]) // 后手先行动（与原实现一致）
                .setTurnCount(0)
            BattleInfoService.setBattleCurrentTurnInfo(battleId, builder.build())
            val startTurnBuilder = BaseBattle.StartTurnEvent.newBuilder()
            val eventMsgBuilder = buildOneEvent(battleId, BaseBattle.EventTypeEnum.EventTypeStartTurn, startTurnBuilder)
            val eventMsgList = doEvent(battleId, eventMsgBuilder)
            pushEventListToAll(battleId, eventMsgList)
        }

        /** 事件引擎（原 doEvent）：todoList 顺序消费 */
        suspend fun doEvent(battleId: String, firstEvent: BaseBattle.EventMsg.Builder): BaseBattle.EventMsgList.Builder {
            val result = BaseBattle.EventMsgList.newBuilder()
            val todoList = ArrayDeque<BaseBattle.EventMsg.Builder>()
            todoList.add(firstEvent)
            while (todoList.isNotEmpty()) {
                val event = todoList.removeFirst()
                val eventNum = BattleInfoService.addAndGetNextAvailableEventNum(battleId)
                event.eventNum = eventNum
                val nextEvents = when (event.eventTypeValue) {
                    BaseBattle.EventTypeEnum.EventTypeGameOver_VALUE -> doGameOver(battleId, event)
                    BaseBattle.EventTypeEnum.EventTypeStartTurn_VALUE -> doStartTurn(battleId, event)
                    BaseBattle.EventTypeEnum.EventTypeEndTurn_VALUE -> doEndTurn(battleId)
                    BaseBattle.EventTypeEnum.EventTypePlacePieces_VALUE -> doPlacePieces(battleId, event)
                    else -> throw RpcErrorException(Rpc.RpcErrorCodeEnum.ServerError_VALUE)
                }
                result.addMsgList(event.build())
                BattleInfoService.addOneBattleEvent(battleId, event.build())
                nextEvents?.let { todoList.addAll(0, it) }
            }
            return result
        }

        fun buildOneEvent(
            battleId: String,
            eventType: BaseBattle.EventTypeEnum,
            builder: com.google.protobuf.MessageLite.Builder
        ): BaseBattle.EventMsg.Builder {
            val msgBuilder = BaseBattle.EventMsg.newBuilder().setEventType(eventType)
            when (eventType) {
                BaseBattle.EventTypeEnum.EventTypeGameOver ->
                    msgBuilder.setGameOverEvent(builder as BaseBattle.GameOverEvent.Builder)
                BaseBattle.EventTypeEnum.EventTypeStartTurn ->
                    msgBuilder.setStartTurnEvent(builder as BaseBattle.StartTurnEvent.Builder)
                BaseBattle.EventTypeEnum.EventTypeEndTurn ->
                    msgBuilder.setEndTurnEvent(builder as BaseBattle.EndTurnEvent.Builder)
                BaseBattle.EventTypeEnum.EventTypePlacePieces ->
                    msgBuilder.setPlacePiecesEvent(builder as BaseBattle.PlacePiecesEvent.Builder)
                else -> throw RpcErrorException(Rpc.RpcErrorCodeEnum.ServerError_VALUE)
            }
            return msgBuilder
        }

        private suspend fun doStartTurn(
            battleId: String,
            event: BaseBattle.EventMsg.Builder
        ): List<BaseBattle.EventMsg.Builder>? {
            val currentInfo = BattleInfoService.getBattleCurrentTurnInfo(battleId)?.toBuilder() ?: return null
            currentInfo.turnStartTimestamp = DateTimeUtil.getCurrentTimestamp()
            val userIds = BattleInfoService.getOneBattleUserIds(battleId)
            val index = userIds.indexOf(currentInfo.userId)
            val nextIndex = (index + 1) % userIds.size
            currentInfo.userId = userIds[nextIndex]
            if (nextIndex == 0) currentInfo.turnCount = currentInfo.turnCount + 1
            BattleInfoService.setBattleCurrentTurnInfo(battleId, currentInfo.build())
            event.startTurnEvent = event.startTurnEvent.toBuilder().setCurrentTurnInfo(currentInfo).build()
            return null
        }

        private suspend fun doEndTurn(battleId: String): List<BaseBattle.EventMsg.Builder> {
            val startTurnBuilder = BaseBattle.StartTurnEvent.newBuilder()
                .setCurrentTurnInfo(BattleInfoService.getBattleCurrentTurnInfo(battleId))
            val eventMsgBuilder = buildOneEvent(battleId, BaseBattle.EventTypeEnum.EventTypeStartTurn, startTurnBuilder)
            return arrayListOf(eventMsgBuilder)
        }

        private suspend fun doPlacePieces(
            battleId: String,
            event: BaseBattle.EventMsg.Builder
        ): List<BaseBattle.EventMsg.Builder> {
            val nextEvents = arrayListOf<BaseBattle.EventMsg.Builder>()
            val placePieces = event.placePiecesEvent
            val userId = placePieces.userId
            val index = placePieces.index
            val userSeq = BattleInfoService.getOneUserSeq(battleId, userId)
            BattleInfoService.setOneBattleCellInfo(battleId, index.toLong(), userSeq)
            // 胜负检测
            val winnerUserSeq = BattleEventSupportBridge.checkWinner(battleId, index)
            if (winnerUserSeq != -1) {
                val gameOverBuilder = BaseBattle.GameOverEvent.newBuilder()
                if (winnerUserSeq == 0) {
                    gameOverBuilder.winnerUserId = 0
                    gameOverBuilder.gameOverReason = BaseBattle.GameOverReasonEnum.GameOverDraw
                } else {
                    gameOverBuilder.winnerUserId = BattleInfoService.getOneUserIdBySeq(battleId, winnerUserSeq)
                    gameOverBuilder.gameOverReason = BaseBattle.GameOverReasonEnum.GameOverPlayerWin
                }
                nextEvents.add(buildOneEvent(battleId, BaseBattle.EventTypeEnum.EventTypeGameOver, gameOverBuilder))
            } else {
                val endTurnBuilder = BaseBattle.EndTurnEvent.newBuilder().setEndTurnUserId(userId)
                nextEvents.add(buildOneEvent(battleId, BaseBattle.EventTypeEnum.EventTypeEndTurn, endTurnBuilder))
            }
            return nextEvents
        }

        private suspend fun doGameOver(
            battleId: String,
            event: BaseBattle.EventMsg.Builder
        ): List<BaseBattle.EventMsg.Builder>? {
            val gameOver = event.gameOverEvent
            val userIds = BattleInfoService.getOneBattleUserIds(battleId)
            val currentTurnInfo = BattleInfoService.getBattleCurrentTurnInfo(battleId)
            // 归档对战记录
            val record = BattleRecordEntity(
                _id = 0,
                battleType = CommonEnum.BattleTypeEnum.BattleTypeTwoPlayer_VALUE,
                battleId = battleId,
                userIdList = userIds.joinToString(","),
                battleStartTimestamp = Date(BattleInfoService.getOneBattleStartTimestamp(battleId)),
                battleEndTimestamp = Date(),
                turnCount = currentTurnInfo?.turnCount ?: 0,
                winnerUserId = gameOver.winnerUserId,
                gameOverReason = gameOver.gameOverReasonValue,
            )
            BattleRecordService.saveBattleRecord(record)
            // 清理玩家对战状态
            for (userId in userIds) {
                PlayStateService.changeUserActionState(
                    userId,
                    CommonEnum.UserActionStateEnum.ActionNone_VALUE,
                    CommonEnum.BattleTypeEnum.NoneType_VALUE,
                    battleId
                )
            }
            BattleInfoService.removePlayingBattleId(battleId, CommonEnum.BattleTypeEnum.BattleTypeTwoPlayer)
            BattleInfoService.cleanOneBattleUserIds(battleId)
            BattleInfoService.removeBattleCurrentTurnInfo(battleId)
            BattleInfoService.cleanAllBattleCellInfo(battleId)
            BattleInfoService.removeLastEventNum(battleId)
            BattleInfoService.removeOneBattleStartTimestamp(battleId)
            BattleInfoService.cleanOneBattleNotReadyUserIds(battleId)
            BattleRooms.removeBattle(battleId)
            return null
        }

        private suspend fun pushEventListToAll(battleId: String, eventMsgList: BaseBattle.EventMsgList.Builder) {
            val netMsg = BattleEventSupportBridge.buildPush(eventMsgList.build())
            for (userId in BattleInfoService.getOneBattleUserIds(battleId)) {
                BattleRooms.sendNetMsgToOneUser(userId, netMsg)
            }
        }

        suspend fun pushEventListToOne(userId: Int, eventMsgList: BaseBattle.EventMsgList.Builder) {
            BattleRooms.sendNetMsgToOneUser(userId, BattleEventSupportBridge.buildPush(eventMsgList.build()))
        }
    }
}

/** 桥接 object（避免循环依赖的薄封装） */
object BattleEventSupportBridge {
    suspend fun checkWinner(battleId: String, index: Int): Int =
        checkAndGetWinnerUserSeq(battleId, index)

    fun buildPush(eventMsgList: BaseBattle.EventMsgList): NetMessage {
        val pushBuilder = BaseBattle.BattleEventMsgListPush.newBuilder()
            .setEventMsgList(eventMsgList)
        return NetMessage(22001, pushBuilder) // RpcBattleEventMsgListPush
    }

    /** 【游戏核心逻辑】井字棋胜负检测：-1 未完 / 0 平局 / 其它=获胜方行动顺序 */
    suspend fun checkAndGetWinnerUserSeq(battleId: String, justPlacePiecesIndex: Int): Int {
        val allBattleCellInfo = BattleInfoService.getAllBattleCellInfo(battleId)
        val justPlacePiecesUserSeq = allBattleCellInfo[justPlacePiecesIndex]
        val rowNum = justPlacePiecesIndex / 3
        val rowStart = rowNum * 3
        if ((rowStart until rowStart + 3).all { allBattleCellInfo[it] == justPlacePiecesUserSeq }) {
            return justPlacePiecesUserSeq
        }
        val colNum = justPlacePiecesIndex % 3
        if ((colNum..colNum + 6 step 3).all { allBattleCellInfo[it] == justPlacePiecesUserSeq }) {
            return justPlacePiecesUserSeq
        }
        if (justPlacePiecesIndex == 2 || justPlacePiecesIndex == 4 || justPlacePiecesIndex == 6) {
            if ((2..6 step 2).all { allBattleCellInfo[it] == justPlacePiecesUserSeq }) {
                return justPlacePiecesUserSeq
            }
        }
        if (justPlacePiecesIndex == 0 || justPlacePiecesIndex == 4 || justPlacePiecesIndex == 8) {
            if ((0..8 step 4).all { allBattleCellInfo[it] == justPlacePiecesUserSeq }) {
                return justPlacePiecesUserSeq
            }
        }
        return if (allBattleCellInfo.any { it == 0 }) -1 else 0
    }
}
