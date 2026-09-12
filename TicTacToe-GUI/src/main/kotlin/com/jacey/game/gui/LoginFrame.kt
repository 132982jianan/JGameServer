package com.jacey.game.gui

import javax.swing.JFrame
import kotlin.system.exitProcess
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JButton
import javax.swing.JPasswordField
import javax.swing.JTextField
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.proto3.CommonMsg
import com.jacey.game.common.proto3.Rpc
import com.jacey.game.common.util.MD5Util

/**
 * 登录/注册窗口（原 LoginFrame）
 */
class LoginFrame : JFrame() {
    private val playerNameLab = JLabel("用户名")
    private val passwordLab = JLabel("密 码：")
    private val playerNameText = JTextField()
    private val passwordText = JPasswordField()
    private val registeredBtn = JButton("注册")
    private val loginBtn = JButton("登录")

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

        registeredBtn.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) = onRegister()
        })
        loginBtn.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) = onLogin()
        })

        val labels = arrayOf<JLabel>(playerNameLab, passwordLab)
        val texts = arrayOf<JTextField>(playerNameText, passwordText)
        val buttons = arrayOf<JButton>(loginBtn, registeredBtn)

        layout = java.awt.GridBagLayout()
        val gc = java.awt.GridBagConstraints()
        gc.insets = java.awt.Insets(8, 8, 8, 8)
        gc.gridx = 0; gc.gridy = 0
        add(labels[0], gc)
        gc.gridx = 1; gc.gridy = 0
        playerNameText.preferredSize = java.awt.Dimension(200, 28)
        add(texts[0], gc)
        gc.gridx = 0; gc.gridy = 1
        add(labels[1], gc)
        gc.gridx = 1; gc.gridy = 1
        passwordText.preferredSize = java.awt.Dimension(200, 28)
        add(texts[1], gc)
        gc.gridx = 1; gc.gridy = 2
        val btnPanel = JPanel()
        btnPanel.add(buttons[0])
        btnPanel.add(buttons[1])
        add(btnPanel, gc)

        pack()
        setLocationRelativeTo(null)
        UIUtil.init(this)
        Views.loginFrame = this
    }

    private fun onRegister() {
        if (!ServerConnection.isConnected) {
            javax.swing.JOptionPane.showMessageDialog(this,
                "Server not connected !! ${GuiConfig.serverHost}:${GuiConfig.serverPort}")
            return
        }
        val name = playerNameText.text
        val password = String(passwordText.password)
        val req = CommonMsg.RegistRequest.newBuilder()
            .setUsername(name)
            .setPassword(password)
        ServerConnection.send(NetMessage(Rpc.RpcNameEnum.Regist_VALUE, req))
    }

    private fun onLogin() {
        if (!ServerConnection.isConnected) {
            javax.swing.JOptionPane.showMessageDialog(this,
                "Server not connected !! ${GuiConfig.serverHost}:${GuiConfig.serverPort}")
            return
        }
        val name = playerNameText.text
        val password = String(passwordText.password)
        val req = CommonMsg.LoginRequest.newBuilder()
            .setUsername(name)
            .setPasswordMD5(MD5Util.md5(password))
        ServerConnection.send(NetMessage(Rpc.RpcNameEnum.Login_VALUE, req))
    }
}
