package com.jacey.game.gui

import com.jacey.game.common.framework.process.Log4j2
import com.jacey.game.gui.config.GuiConfig
import com.jacey.game.gui.service.MessageFactoryService
import com.jacey.game.gui.service.NetService
import com.jacey.game.gui.util.UIUtil
import com.jacey.game.gui.service.ViewManagerService
import com.jacey.game.gui.jframe.LoginFrame
import io.github.oshai.kotlinlogging.KotlinLogging
import javax.swing.SwingUtilities
import kotlinx.coroutines.launch

/**
 * GUI 客户端入口（原 TicTacToeApplication + ApplicationReadyEventListener + ViewManager.main）
 *
 * 启动序列：
 * 1. 日志目录 sys prop（无配置文件，路径 logs/gui/）
 * 2. 注册消息处理器
 * 3. Swing 初始化（非 headless），打开登录窗口
 * 4. 后台协程：GM HTTP 取网关地址 → Netty 连接
 */
suspend fun main() {
    val logger = KotlinLogging.logger {}
    Log4j2.init("gui")

    MessageFactoryService.init()

    // Swing 在 EDT 上初始化
    SwingUtilities.invokeAndWait {
        UIUtil.setUIFont()
        UIUtil.frameType()
        ViewManagerService.loginFrame = LoginFrame()
    }

    // 后台连接服务器
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
        try {
            NetService.connect()
        } catch (e: Exception) {
            logger.error(e) { "服务器连接失败: ${e.message}" }
            SwingUtilities.invokeLater {
                javax.swing.JOptionPane.showMessageDialog(
                    null,
                    "Connect failed: ${e.message}\n(GM = ${GuiConfig.serverHost}:${GuiConfig.serverPort})"
                )
            }
        }
    }
}
