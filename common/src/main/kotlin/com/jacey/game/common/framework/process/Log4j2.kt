package com.jacey.game.common.framework.process

/**
 * Log4j2 日志说明（单例）
 *
 * log4j2 配置由 classpath 根的 log4j2.xml 自动发现（fat jar 根），无需代码初始化。
 * 日志目录通过 JVM 参数 -DlogDir=<kind> 注入（默认 logs/all/）。
 * Application 启动时在解析参数后立即 System.setProperty("logDir", kind)。
 *
 * 注意：必须在任何 logger 首次使用前设置 logDir —— Application.main 第一行完成。
 */
object Log4j2 {
    /** 设置日志目录（必须在首个日志调用前调用；进程生命周期内幂等） */
    fun init(kind: String) {
        System.setProperty("logDir", kind)
    }
}
