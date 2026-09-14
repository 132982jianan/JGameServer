package com.jacey.game.gui.jframe

import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.proto3.CommonMsg
import com.jacey.game.common.proto3.Rpc
import com.jacey.game.gui.config.GuiConfig
import com.jacey.game.gui.service.NetService
import com.jacey.game.gui.service.ViewManagerService
import com.jacey.game.gui.util.UIUtil
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JTextField
import kotlin.system.exitProcess

/** LoginDebug：只输入 LoginName；账号和角色不存在时由 Lobby 自动创建。 */
class LoginFrame : JFrame() {
    private val loginNameText = JTextField()

    init {
        UIUtil.frameType()
        title = "[Tic-Tac-Toe]"
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent?) {
                NetService.disconnect()
                exitProcess(0)
            }
        })

        val loginButton = JButton("登录 / 自动注册").apply {
            addActionListener { onLogin() }
        }
        layout = GridBagLayout()
        val gc = GridBagConstraints().apply { insets = Insets(8, 8, 8, 8) }
        gc.gridx = 0
        gc.gridy = 0
        add(JLabel("LoginName"), gc)
        gc.gridx = 1
        loginNameText.preferredSize = Dimension(220, 28)
        add(loginNameText, gc)
        gc.gridy = 1
        add(loginButton, gc)

        pack()
        setLocationRelativeTo(null)
        UIUtil.init(this)
        ViewManagerService.loginFrame = this
    }

    private fun onLogin() {
        if (!NetService.isConnected) {
            JOptionPane.showMessageDialog(
                this,
                "Portal/Gate not connected: ${GuiConfig.serverHost}:${GuiConfig.serverPort}",
            )
            return
        }
        val loginName = loginNameText.text.trim()
        if (loginName.isEmpty()) {
            JOptionPane.showMessageDialog(this, "LoginName 不能为空")
            return
        }
        val request = CommonMsg.LoginRequest.newBuilder().setLoginName(loginName)
        NetService.send(NetMessage(Rpc.RpcNameEnum.Login_VALUE, request))
    }
}
