package com.jacey.game.gui.jframe

import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.proto3.BaseBattle
import com.jacey.game.common.proto3.CommonEnum
import com.jacey.game.common.proto3.CommonMsg
import com.jacey.game.common.proto3.Rpc
import com.jacey.game.gui.service.NetService
import com.jacey.game.gui.service.SessionService
import com.jacey.game.gui.service.ViewManagerService
import com.jacey.game.gui.util.UIUtil
import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JFrame
import kotlin.system.exitProcess
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingConstants

/**
 * 对战窗口（原 BattleFrame）
 *
 * 3x3 棋盘 + 回合信息 + 投降 + 对战聊天。
 * 落子/事件推送由 BattleEvents 驱动界面更新。
 */
class BattleFrame(battleInfo: BaseBattle.BattleInfo) : JFrame() {

    /** 棋盘信息 */
    private var allBattleCellInfo: List<Int> = List(9) { 0 }
    private val cellFields = Array(9) { JTextField() }

    /** 对手信息 */
    var opponentUserInfo: CommonMsg.UserBriefInfo? = null
    /** 我的信息 */
    private val myUserInfo: CommonMsg.UserBriefInfo?

    /** 当前回合信息 */
    private var currentTurnInfo: BaseBattle.CurrentTurnInfo? = null

    /** 最后一个事件编号（丢包判断） */
    var lastEventNum: Int = 0

    /** 我方/对手行动顺序（1=先手） */
    private val mySeq: Int
    private val opponentSeq: Int

    /** 我方/敌方棋子字符 */
    val myPiecesStr: String
    val opponentPiecesStr: String

    // ============ 组件 ============
    private val playerNameText = JTextField()
    private val opponentText = JTextField()
    private val piecesText = JTextField()
    val roundText = JTextField("回合未开始")
    private val leftTextArea = JTextArea(10, 22)
    private val inputText = JTextField()
    private val surrenderBtn = JButton("投降")
    private val sendBtn = JButton("发送")

    init {
        UIUtil.frameType()
        title = "[Tic-Tac-Toe] Battle"
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent?) {
                NetService.disconnect()
                exitProcess(0)
            }
        })

        // 解析对战信息
        val briefs = battleInfo.userBriefInfosList
        val myUserId = SessionService.userInfo?.userId ?: 0
        opponentUserInfo = briefs.firstOrNull { it.userId != myUserId }
        myUserInfo = briefs.firstOrNull { it.userId == myUserId }
        mySeq = seqOf(myUserId, briefs)
        opponentSeq = opponentUserInfo?.let { seqOf(it.userId, briefs) } ?: -1

        myPiecesStr = if (mySeq == 1) "X" else "O"
        opponentPiecesStr = if (mySeq == 1) "O" else "X"

        buildUi()

        // 初始化棋盘与标签
        allBattleCellInfo = battleInfo.battleCellInfoList
        firstLoadingCellInfo(allBattleCellInfo)

        playerNameText.text = myUserInfo?.nickname ?: ""
        opponentText.text = opponentUserInfo?.nickname ?: ""
        piecesText.text = myPiecesStr

        val notReadyUserIds = battleInfo.notReadyUserIdsList
        if (notReadyUserIds.isEmpty()) {
            currentTurnInfo = battleInfo.currentTurnInfo
            roundText.text = if (currentTurnInfo?.userId == myUserId) "我方回合" else "对方回合"
        } else {
            roundText.text = "游戏未开始"
            if (notReadyUserIds.contains(myUserId)) {
                // 我方未准备 → 发送准备完毕请求
                val builder = BaseBattle.ReadyToStartGameRequest.newBuilder()
                NetService.send(NetMessage(Rpc.RpcNameEnum.ReadyToStartGame_VALUE, builder))
            }
        }

        ViewManagerService.battleFrame = this
    }

    // ============ 界面构建 ============

    private fun buildUi() {
        layout = BorderLayout()

        // 顶部：玩家/对手
        val top = JPanel(GridLayout(2, 2, 6, 6))
        top.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        top.add(JLabel("用户名"))
        playerNameText.isEditable = false
        top.add(playerNameText)
        top.add(JLabel("对  手："))
        opponentText.isEditable = false
        top.add(opponentText)
        add(top, BorderLayout.NORTH)

        // 中部：棋盘 3x3
        val board = JPanel(GridLayout(3, 3, 4, 4))
        board.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        for (i in 0 until 9) {
            cellFields[i].isEditable = false
            cellFields[i].isEnabled = false
            cellFields[i].horizontalAlignment = SwingConstants.CENTER
            cellFields[i].font = cellFields[i].font.deriveFont(cellFields[i].font.size + 25f)
            cellFields[i].addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent?) = onCellClick(i)
            })
            board.add(cellFields[i])
        }
        val boardWrap = JPanel(BorderLayout())
        boardWrap.add(board, BorderLayout.CENTER)
        add(boardWrap, BorderLayout.CENTER)

        // 左侧：回合状态 + 棋子 + 聊天
        val west = JPanel(BorderLayout())
        val statusPanel = JPanel(GridLayout(2, 2, 4, 4))
        statusPanel.border = BorderFactory.createEmptyBorder(8, 8, 0, 8)
        statusPanel.add(JLabel("你的棋子："))
        piecesText.isEditable = false
        piecesText.horizontalAlignment = SwingConstants.CENTER
        statusPanel.add(piecesText)
        statusPanel.add(JLabel("回合状态："))
        roundText.isEditable = false
        roundText.horizontalAlignment = SwingConstants.CENTER
        statusPanel.add(roundText)
        // 投降按钮（独立一行，随状态面板）
        surrenderBtn.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) = onConcede()
        })
        val surrenderPanel = JPanel()
        surrenderPanel.border = BorderFactory.createEmptyBorder(0, 8, 0, 8)
        surrenderPanel.add(surrenderBtn)
        west.add(surrenderPanel, BorderLayout.SOUTH)
        west.add(statusPanel, BorderLayout.NORTH)

        leftTextArea.isEditable = false
        val chatPanel = JPanel(BorderLayout())
        chatPanel.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        chatPanel.add(JScrollPane(leftTextArea), BorderLayout.CENTER)
        sendBtn.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) = onSendChat()
        })
        chatPanel.add(sendBtn, BorderLayout.EAST)
        chatPanel.add(inputText, BorderLayout.SOUTH)
        west.add(chatPanel, BorderLayout.CENTER)
        west.preferredSize = Dimension(280, 0)
        add(west, BorderLayout.WEST)
    }

    // ============ 交互 ============

    private fun onCellClick(index: Int) {
        val req = BaseBattle.PlacePiecesRequest.newBuilder()
            .setIndex(index)
            .setLastEventNum(lastEventNum)
        NetService.send(NetMessage(Rpc.RpcNameEnum.PlacePieces_VALUE, req))
    }

    private fun onConcede() {
        logger.info { "投降...." }
        val req = BaseBattle.ConcedeRequest.newBuilder()
        NetService.send(NetMessage(Rpc.RpcNameEnum.Concede_VALUE, req))
    }

    private fun onSendChat() {
        val sendText = inputText.text
        appendChat("${myUserInfo?.nickname}: $sendText\n")
        inputText.text = ""
        val builder = CommonMsg.BattleChatTextSendRequest.newBuilder()
            .setChatRoomType(CommonEnum.ChatRoomTypeEnum.TwoPlayerBattleChatRoomType)
            .setBattleChatTextScope(CommonEnum.BattleChatTextScopeEnum.EveryoneScope)
            .setSendTimestamp(System.currentTimeMillis())
            .setText(sendText)
        NetService.send(NetMessage(Rpc.RpcNameEnum.BattleChatText_VALUE, builder))
        logger.info { "推送聊天文本: $sendText" }
    }

    // ============ 界面更新接口（供 BattleEvents / MessageRouter 调用） ============

    /** 落子渲染（EDT 调用） */
    fun setCell(index: Int, piece: String) {
        if (index in 0..8) cellFields[index].text = piece
    }

    /** 聊天追加（EDT 调用） */
    fun appendChat(line: String) {
        leftTextArea.append(line)
        leftTextArea.caretPosition = leftTextArea.text.length
    }

    /** 首次加载棋盘 */
    private fun firstLoadingCellInfo(allBattleCellInfo: List<Int>) {
        for (i in 0 until 9) {
            val cell = allBattleCellInfo.getOrNull(i) ?: 0
            cellFields[i].text = when {
                cell == 1 -> "X"
                cell == 0 -> ""
                else -> "O"
            }
        }
    }

    private fun seqOf(userId: Int, briefs: List<CommonMsg.UserBriefInfo>): Int {
        val idx = briefs.indexOfFirst { it.userId == userId }
        return if (idx >= 0) idx + 1 else -1
    }

    private val logger = KotlinLogging.logger {}
}
