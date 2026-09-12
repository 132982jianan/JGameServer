package com.jacey.game.gui

import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.proto3.CommonEnum
import com.jacey.game.common.proto3.CommonMsg
import com.jacey.game.common.proto3.Rpc
import javax.swing.JButton
import javax.swing.JFrame
import kotlin.system.exitProcess
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

/**
 * 大厅窗口（原 HallFrame）：显示昵称 + 匹配/取消匹配
 */
class HallFrame(private val userInfo: CommonMsg.UserInfo) : JFrame() {

    val matchBtn = JButton("匹配")
    val unmatchBtn = JButton("取消匹配")
    private val playerNameText = JTextField(userInfo.nickname)

    init {
        UIUtil.frameType()
        title = "[Tic-Tac-Toe]"
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent?) {
                ServerConnection.disconnect()
                exitProcess(0)
            }
        })

        matchBtn.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) = onMatch()
        })
        unmatchBtn.isEnabled = false
        unmatchBtn.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) = onUnmatch()
        })

        layout = java.awt.GridBagLayout()
        val gc = java.awt.GridBagConstraints()
        gc.insets = java.awt.Insets(10, 10, 10, 10)
        gc.gridx = 0; gc.gridy = 0
        add(JLabel("玩家昵称"), gc)
        gc.gridx = 1; gc.gridy = 0
        playerNameText.preferredSize = java.awt.Dimension(160, 28)
        playerNameText.isEditable = false
        add(playerNameText, gc)
        gc.gridx = 1; gc.gridy = 1
        val btnPanel = JPanel()
        btnPanel.add(matchBtn)
        btnPanel.add(unmatchBtn)
        add(btnPanel, gc)

        pack()
        setLocationRelativeTo(null)
        UIUtil.init(this)
    }

    private fun onMatch() {
        val req = CommonMsg.MatchRequest.newBuilder()
            .setBattleTypeValue(CommonEnum.BattleTypeEnum.BattleTypeTwoPlayer_VALUE)
        ServerConnection.send(NetMessage(Rpc.RpcNameEnum.Match_VALUE, req))
    }

    private fun onUnmatch() {
        val req = CommonMsg.CancelMatchRequest.newBuilder()
        ServerConnection.send(NetMessage(Rpc.RpcNameEnum.CancelMatch_VALUE, req))
    }
}
