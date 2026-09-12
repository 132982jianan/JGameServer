package com.jacey.game.common.framework.process

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 进程退出管理（单例）
 *
 * - listenSignal(): 注册 TERM/INT 信号与 JVM shutdownHook
 * - addExitListener(): 注册退出清理回调（倒序执行）
 * - await(): 阻塞主协程直到收到退出信号
 * - exit(code): 主动退出
 */
object Exit {
    private val logger = KotlinLogging.logger {}
    private val listeners = CopyOnWriteArrayList<suspend () -> Unit>()
    private val latch = CountDownLatch(1)
    private val exiting = AtomicBoolean(false)

    /** 注册信号处理（SIGTERM/SIGINT）并挂 shutdownHook，触发时倒序执行清理回调 */
    fun listenSignal() {
        val handler = sun.misc.SignalHandler {
            logger.info { "signal ${it.getNumber()} received, exiting..." }
            runBlocking { fire() }
            Runtime.getRuntime().halt(0)
        }
        sun.misc.Signal.handle(sun.misc.Signal("TERM"), handler)
        sun.misc.Signal.handle(sun.misc.Signal("INT"), handler)
        Runtime.getRuntime().addShutdownHook(Thread {
            runBlocking { fire() }
        })
    }

    /** 依次执行清理回调（注册的倒序），只执行一次 */
    suspend fun fire() {
        if (!exiting.compareAndSet(false, true)) return
        logger.info { "exit listeners: ${listeners.size}" }
        listeners.reversed().forEach { fn ->
            try {
                fn()
            } catch (e: Exception) {
                logger.error(e) { "exit listener fail" }
            }
        }
        latch.countDown()
    }

    /** 注册退出清理回调 */
    fun addExitListener(fn: suspend () -> Unit) {
        listeners.add(fn)
    }

    /** 阻塞当前线程等待退出信号 */
    fun await() {
        latch.await()
    }

    /** 立即执行清理并以指定码退出 */
    fun exit(code: Int = 0) {
        runBlocking { fire() }
        kotlin.system.exitProcess(code)
    }
}
