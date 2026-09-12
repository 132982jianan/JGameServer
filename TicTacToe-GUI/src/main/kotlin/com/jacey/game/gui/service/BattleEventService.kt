package com.jacey.game.gui.service

import com.jacey.game.common.proto3.BaseBattle
import com.jacey.game.gui.service.SessionService
import com.jacey.game.gui.service.ViewManagerService
import com.jacey.game.gui.jframe.HallFrame
import io.github.oshai.kotlinlogging.KotlinLogging
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

/**
 * 对战事件处理（object 单例，原 BaseBattleEventService）
 *
 * 消费服务器事件列表：回合开始/结束、落子、游戏结束，驱动 Swing 界面更新。
 */
object BattleEventService {
    private val logger = KotlinLogging.logger {}

    fun doEvent(eventMsgList: List<BaseBattle.EventMsg>) {
        for (eventMsg in eventMsgList) {
            // 设置事件编号。用于服务端丢包判断
            val frame = ViewManagerService.battleFrame ?: return
            frame.lastEventNum = eventMsg.eventNum
            logger.info { "lastEventNum: ${eventMsg.eventNum}" }
            when (eventMsg.eventTypeValue) {
                BaseBattle.EventTypeEnum.EventTypeStartTurn_VALUE -> {
                    logger.info { "【回合开始】..." }
                    val startTurn = eventMsg.startTurnEvent
                    val currentTurn = startTurn.currentTurnInfo
                    val myUserId = SessionService.userInfo?.userId ?: 0
                    if (currentTurn.userId == myUserId) {
                        val username = SessionService.userInfo?.nickname ?: ""
                        SwingUtilities.invokeLater {
                            JOptionPane.showMessageDialog(null, "$username Your Round")
                        }
                    }
                }
                BaseBattle.EventTypeEnum.EventTypeEndTurn_VALUE -> {
                    logger.info { "【回合结束】..." }
                    val endTurnUserId = eventMsg.endTurnEvent.endTurnUserId
                    val myUserId = SessionService.userInfo?.userId ?: 0
                    SwingUtilities.invokeLater {
                        if (endTurnUserId == myUserId) {
                            frame.roundText.text = "对方回合"
                        } else {
                            frame.roundText.text = "我方回合"
                        }
                    }
                }
                BaseBattle.EventTypeEnum.EventTypePlacePieces_VALUE -> {
                    logger.info { "【落子】..." }
                    val place = eventMsg.placePiecesEvent
                    val index = place.index
                    val piece = if (place.userId == (SessionService.userInfo?.userId ?: 0)) {
                        frame.myPiecesStr
                    } else {
                        frame.opponentPiecesStr
                    }
                    SwingUtilities.invokeLater {
                        frame.setCell(index, piece)
                    }
                }
                BaseBattle.EventTypeEnum.EventTypeGameOver_VALUE -> {
                    logger.info { "【游戏结束】..." }
                    val gameOver = eventMsg.gameOverEvent
                    val winnerUserId = gameOver.winnerUserId
                    val myUserId = SessionService.userInfo?.userId ?: 0
                    val text = when (gameOver.gameOverReasonValue) {
                        BaseBattle.GameOverReasonEnum.GameOverPlayerWin_VALUE ->
                            if (winnerUserId == myUserId) "WIN" else "FAILURE"
                        BaseBattle.GameOverReasonEnum.GameOverPlayerConcede_VALUE ->
                            if (winnerUserId == myUserId) "WIN，Opponent concede" else "FAILURE，We Concede"
                        BaseBattle.GameOverReasonEnum.GameOverDraw_VALUE -> "DRAW"
                        else -> "Unknown cause"
                    }
                    SwingUtilities.invokeLater {
                        JOptionPane.showMessageDialog(null, text)
                        ViewManagerService.battleFrame?.dispose()
                        ViewManagerService.battleFrame = null
                        SessionService.userInfo?.let {
                            ViewManagerService.hallFrame = HallFrame(it).also { f -> f.isVisible = true }
                        }
                    }
                }
                else -> logger.error { "【事件处理异常】未知事件类型 EventType=${eventMsg.eventType}" }
            }
        }
    }
}