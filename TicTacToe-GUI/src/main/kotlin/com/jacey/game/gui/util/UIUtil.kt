package com.jacey.game.gui.util

import java.awt.Font
import javax.swing.JFrame
import javax.swing.UIManager

/** Swing UI 工具（原 UIUtil） */
object UIUtil {
    /** Nimbus 主题 */
    fun frameType() {
        try {
            for (info in UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus" == info.name) {
                    UIManager.setLookAndFeel(info.className)
                    break
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 统一字体 */
    fun setUIFont() {
        val f = Font("宋体", Font.PLAIN, 18)
        val names = arrayOf(
            "Label", "CheckBox", "PopupMenu", "MenuItem", "CheckBoxMenuItem",
            "JRadioButtonMenuItem", "ComboBox", "Button", "Tree", "ScrollPane",
            "TabbedPane", "EditorPane", "TitledBorder", "Menu", "TextArea",
            "OptionPane", "MenuBar", "ToolBar", "ToggleButton", "ToolTip",
            "ProgressBar", "TableHeader", "Panel", "List", "ColorChooser",
            "PasswordField", "TextField", "Table", "Label", "Viewport",
            "RadioButtonMenuItem", "RadioButton", "DesktopPane", "InternalFrame"
        )
        for (item in names) {
            UIManager.put("$item.font", f)
        }
    }

    /** 通用窗口设置 */
    fun init(jf: JFrame) {
        jf.title = "[Tic-Tac-Toe]-ByJacey"
        jf.isResizable = false
        jf.isVisible = true
    }
}