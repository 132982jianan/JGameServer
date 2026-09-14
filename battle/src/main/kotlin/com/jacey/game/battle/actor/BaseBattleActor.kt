package com.jacey.game.battle.actor

import akka.actor.ActorRef
import com.google.protobuf.MessageLite
import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.common.db.Db
import com.jacey.game.common.exception.RpcErrorException
import com.jacey.game.common.msg.InternalMessageId
import com.jacey.game.common.msg.LocalMessage
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.proto3.BaseBattle
import com.jacey.game.common.proto3.CommonEnum
import com.jacey.game.common.proto3.CommonMsg
import com.jacey.game.common.proto3.RemoteServer
import com.jacey.game.common.proto3.Rpc
import com.jacey.game.common.util.DateTimeUtil
import com.jacey.game.db.service.BattleRecordService
import com.jacey.game.db.table.DbBattleRecord
import java.util.Date

data class BattleActorState(
    val battleId: String,
    val battleType: CommonEnum.BattleTypeEnum,
    val playerIds: List<Int>,
    val battleStartTimestamp: Long = DateTimeUtil.getCurrentTimestamp(),
    val cells: MutableList<Int> = MutableList(9) { 0 },
    val notReadyPlayerIds: MutableSet<Int> = playerIds.toMutableSet(),
    val gateActors: MutableMap<Int, ActorRef> = HashMap(),
    val events: MutableList<BaseBattle.EventMsg> = ArrayList(),
    var currentTurn: BaseBattle.CurrentTurnInfo? = null,
    var lastEventNum: Int = 0,
    var finished: Boolean = false,
)

data class BattleEnded(val battleId: String, val playerIds: List<Int>)

/** 一场战局一个 Actor；棋盘、回合、事件、准备状态和推送目标全部独占在 ActorState。 */
class BaseBattleActor(roomInfo: RemoteServer.BattleRoomInfo) : BaseMessageActor() {
    private val state = BattleActorState(
        battleId = roomInfo.battleId,
        battleType = roomInfo.battleType,
        playerIds = roomInfo.userIdsList.toList(),
    )

    init {
        registerHandler(NetMessage::class.java) { msg, sender -> onNetMessage(msg, sender) }
        registerHandler(LocalMessage::class.java) { msg, _ ->
            if (msg.msgId == InternalMessageId.BATTLE_PLAYER_OFFLINE) {
                (msg.lite as? Int)?.let(state.gateActors::remove)
            }
        }
    }

    private suspend fun onNetMessage(msg: NetMessage, sender: ActorRef?) {
        if (msg.userId !in state.playerIds || state.finished) {
            throw RpcErrorException(Rpc.RpcErrorCodeEnum.UserNotInBattle_VALUE)
        }
        if (sender != null) state.gateActors[msg.userId] = sender
        when (msg.msgId) {
            Rpc.RpcNameEnum.GetBattleInfo_VALUE -> onGetBattleInfo(msg.userId, sender)
            Rpc.RpcNameEnum.PlacePieces_VALUE -> onPlacePieces(msg, sender)
            Rpc.RpcNameEnum.Concede_VALUE -> onConcede(msg.userId, sender)
            Rpc.RpcNameEnum.ReadyToStartGame_VALUE -> onReady(msg.userId, sender)
            else -> throw RpcErrorException(Rpc.RpcErrorCodeEnum.ServerError_VALUE)
        }
    }

    private suspend fun onGetBattleInfo(playerId: Int, sender: ActorRef?) {
        val info = BaseBattle.BattleInfo.newBuilder()
            .setBattleStartTimestamp(state.battleStartTimestamp)
            .addAllBattleCellInfo(state.cells)
            .addAllNotReadyUserIds(state.notReadyPlayerIds)
            .setLastEventNum(state.lastEventNum)
        if (state.notReadyPlayerIds.isEmpty()) state.currentTurn?.let(info::setCurrentTurnInfo)
        for (onePlayerId in state.playerIds) {
            val player = Db.dbPlayer.findOne(onePlayerId)
            info.addUserBriefInfos(
                CommonMsg.UserBriefInfo.newBuilder()
                    .setUserId(onePlayerId)
                    .setNickname(player?.basic?.name ?: "Player$onePlayerId")
                    .setUserState(
                        CommonMsg.UserState.newBuilder()
                            .setOnlineState(CommonEnum.UserOnlineStateEnum.Online)
                            .setActionState(CommonEnum.UserActionStateEnum.Playing)
                            .setBattleType(state.battleType)
                            .setBattleId(state.battleId)
                    )
            )
        }
        reply(
            playerId,
            Rpc.RpcNameEnum.GetBattleInfo_VALUE,
            BaseBattle.GetBattleInfoResponse.newBuilder().setBattleInfo(info),
            sender,
        )
    }

    private suspend fun onReady(playerId: Int, sender: ActorRef?) {
        if (!state.notReadyPlayerIds.remove(playerId)) {
            throw RpcErrorException(Rpc.RpcErrorCodeEnum.ReadyToStartGameErrorAlreadyReady_VALUE)
        }
        if (state.notReadyPlayerIds.isEmpty()) startFirstTurn()
        reply(
            playerId,
            Rpc.RpcNameEnum.ReadyToStartGame_VALUE,
            BaseBattle.ReadyToStartGameResponse.newBuilder(),
            sender,
        )
    }

    private suspend fun onPlacePieces(msg: NetMessage, sender: ActorRef?) {
        if (state.notReadyPlayerIds.isNotEmpty()) throw RpcErrorException(Rpc.RpcErrorCodeEnum.BattleNotStart_VALUE)
        val request = msg.getProto<BaseBattle.PlacePiecesRequest>()
            ?: throw RpcErrorException(Rpc.RpcErrorCodeEnum.ServerError_VALUE)
        val turn = state.currentTurn
        if (turn == null || turn.userId != msg.userId) {
            throw RpcErrorException(Rpc.RpcErrorCodeEnum.IsNotUserTurn_VALUE)
        }
        if (request.lastEventNum != state.lastEventNum) {
            throw RpcErrorException(Rpc.RpcErrorCodeEnum.InputLastEventNumError_VALUE)
        }
        if (request.index !in 0..8) {
            throw RpcErrorException(Rpc.RpcErrorCodeEnum.PlacePiecesErrorIndexError_VALUE)
        }
        if (state.cells[request.index] != 0) {
            throw RpcErrorException(Rpc.RpcErrorCodeEnum.PlacePiecesErrorIndexIsNotEmpty_VALUE)
        }

        val event = buildEvent(
            BaseBattle.EventTypeEnum.EventTypePlacePieces,
            BaseBattle.PlacePiecesEvent.newBuilder().setUserId(msg.userId).setIndex(request.index),
        )
        val eventList = doEvent(event)
        opponentOf(msg.userId)?.let { pushEvents(it, eventList) }
        reply(
            msg.userId,
            Rpc.RpcNameEnum.PlacePieces_VALUE,
            BaseBattle.PlacePiecesResponse.newBuilder().setEventList(eventList),
            sender,
        )
        finishIfNeeded()
    }

    private suspend fun onConcede(playerId: Int, sender: ActorRef?) {
        if (state.notReadyPlayerIds.isNotEmpty()) throw RpcErrorException(Rpc.RpcErrorCodeEnum.BattleNotStart_VALUE)
        val winner = opponentOf(playerId)
            ?: throw RpcErrorException(Rpc.RpcErrorCodeEnum.UserNotInBattle_VALUE)
        val event = buildEvent(
            BaseBattle.EventTypeEnum.EventTypeGameOver,
            BaseBattle.GameOverEvent.newBuilder()
                .setGameOverReason(BaseBattle.GameOverReasonEnum.GameOverPlayerConcede)
                .setWinnerUserId(winner),
        )
        val eventList = doEvent(event)
        pushEvents(winner, eventList)
        reply(
            playerId,
            Rpc.RpcNameEnum.Concede_VALUE,
            BaseBattle.ConcedeResponse.newBuilder().setEventList(eventList),
            sender,
        )
        finishIfNeeded()
    }

    private suspend fun startFirstTurn() {
        state.currentTurn = BaseBattle.CurrentTurnInfo.newBuilder()
            .setUserId(state.playerIds.last())
            .setTurnCount(0)
            .build()
        val events = doEvent(
            buildEvent(
                BaseBattle.EventTypeEnum.EventTypeStartTurn,
                BaseBattle.StartTurnEvent.newBuilder(),
            )
        )
        state.playerIds.forEach { pushEvents(it, events) }
    }

    private suspend fun doEvent(first: BaseBattle.EventMsg.Builder): BaseBattle.EventMsgList {
        val result = BaseBattle.EventMsgList.newBuilder()
        val todo = ArrayDeque<BaseBattle.EventMsg.Builder>()
        todo.addLast(first)
        while (todo.isNotEmpty()) {
            val event = todo.removeFirst()
            event.eventNum = ++state.lastEventNum
            val next = when (event.eventType) {
                BaseBattle.EventTypeEnum.EventTypeGameOver -> doGameOver(event)
                BaseBattle.EventTypeEnum.EventTypeStartTurn -> doStartTurn(event)
                BaseBattle.EventTypeEnum.EventTypeEndTurn -> listOf(
                    buildEvent(
                        BaseBattle.EventTypeEnum.EventTypeStartTurn,
                        BaseBattle.StartTurnEvent.newBuilder(),
                    )
                )
                BaseBattle.EventTypeEnum.EventTypePlacePieces -> doPlacePieces(event)
                else -> throw RpcErrorException(Rpc.RpcErrorCodeEnum.ServerError_VALUE)
            }
            val built = event.build()
            state.events.add(built)
            result.addMsgList(built)
            next.forEach(todo::addLast)
        }
        return result.build()
    }

    private fun doStartTurn(event: BaseBattle.EventMsg.Builder): List<BaseBattle.EventMsg.Builder> {
        val current = state.currentTurn?.toBuilder()
            ?: throw RpcErrorException(Rpc.RpcErrorCodeEnum.ServerError_VALUE)
        val nextIndex = (state.playerIds.indexOf(current.userId) + 1) % state.playerIds.size
        current.userId = state.playerIds[nextIndex]
        if (nextIndex == 0) current.turnCount = current.turnCount + 1
        current.turnStartTimestamp = DateTimeUtil.getCurrentTimestamp()
        state.currentTurn = current.build()
        event.startTurnEvent = BaseBattle.StartTurnEvent.newBuilder().setCurrentTurnInfo(current).build()
        return emptyList()
    }

    private fun doPlacePieces(event: BaseBattle.EventMsg.Builder): List<BaseBattle.EventMsg.Builder> {
        val placed = event.placePiecesEvent
        val playerSeq = state.playerIds.indexOf(placed.userId) + 1
        state.cells[placed.index] = playerSeq
        val winnerSeq = checkWinner(placed.index)
        return if (winnerSeq != -1) {
            val gameOver = BaseBattle.GameOverEvent.newBuilder()
            if (winnerSeq == 0) {
                gameOver.gameOverReason = BaseBattle.GameOverReasonEnum.GameOverDraw
            } else {
                gameOver.gameOverReason = BaseBattle.GameOverReasonEnum.GameOverPlayerWin
                gameOver.winnerUserId = state.playerIds[winnerSeq - 1]
            }
            listOf(buildEvent(BaseBattle.EventTypeEnum.EventTypeGameOver, gameOver))
        } else {
            listOf(
                buildEvent(
                    BaseBattle.EventTypeEnum.EventTypeEndTurn,
                    BaseBattle.EndTurnEvent.newBuilder().setEndTurnUserId(placed.userId),
                )
            )
        }
    }

    private suspend fun doGameOver(event: BaseBattle.EventMsg.Builder): List<BaseBattle.EventMsg.Builder> {
        val gameOver = event.gameOverEvent
        BattleRecordService.saveBattleRecord(
            DbBattleRecord(
                _id = 0,
                battleType = state.battleType.number,
                battleId = state.battleId,
                userIdList = state.playerIds.joinToString(","),
                battleStartTimestamp = Date(state.battleStartTimestamp),
                battleEndTimestamp = Date(),
                turnCount = state.currentTurn?.turnCount ?: 0,
                winnerUserId = gameOver.winnerUserId,
                gameOverReason = gameOver.gameOverReasonValue,
            )
        )
        state.finished = true
        return emptyList()
    }

    private fun checkWinner(lastIndex: Int): Int {
        val seq = state.cells[lastIndex]
        val rowStart = (lastIndex / 3) * 3
        if ((rowStart until rowStart + 3).all { state.cells[it] == seq }) return seq
        val col = lastIndex % 3
        if ((col..col + 6 step 3).all { state.cells[it] == seq }) return seq
        if (lastIndex in setOf(2, 4, 6) && (2..6 step 2).all { state.cells[it] == seq }) return seq
        if (lastIndex in setOf(0, 4, 8) && (0..8 step 4).all { state.cells[it] == seq }) return seq
        return if (state.cells.any { it == 0 }) -1 else 0
    }

    private fun buildEvent(type: BaseBattle.EventTypeEnum, body: MessageLite.Builder): BaseBattle.EventMsg.Builder {
        val event = BaseBattle.EventMsg.newBuilder().setEventType(type)
        when (type) {
            BaseBattle.EventTypeEnum.EventTypeGameOver -> event.setGameOverEvent(body as BaseBattle.GameOverEvent.Builder)
            BaseBattle.EventTypeEnum.EventTypeStartTurn -> event.setStartTurnEvent(body as BaseBattle.StartTurnEvent.Builder)
            BaseBattle.EventTypeEnum.EventTypeEndTurn -> event.setEndTurnEvent(body as BaseBattle.EndTurnEvent.Builder)
            BaseBattle.EventTypeEnum.EventTypePlacePieces -> event.setPlacePiecesEvent(body as BaseBattle.PlacePiecesEvent.Builder)
            else -> throw RpcErrorException(Rpc.RpcErrorCodeEnum.ServerError_VALUE)
        }
        return event
    }

    private fun pushEvents(playerId: Int, events: BaseBattle.EventMsgList) {
        val push = BaseBattle.BattleEventMsgListPush.newBuilder().setEventMsgList(events)
        state.gateActors[playerId]?.tell(
            NetMessage(Rpc.RpcNameEnum.RpcBattleEventMsgListPush_VALUE, push),
            self(),
        )
    }

    private fun reply(playerId: Int, msgId: Int, body: MessageLite.Builder, sender: ActorRef?) {
        sender?.tell(NetMessage(msgId, body).apply { userId = playerId }, self())
    }

    private fun opponentOf(playerId: Int): Int? = state.playerIds.firstOrNull { it != playerId }

    private fun finishIfNeeded() {
        if (state.finished) {
            context().parent().tell(
                LocalMessage(InternalMessageId.BATTLE_ENDED, BattleEnded(state.battleId, state.playerIds)),
                self(),
            )
        }
    }
}
