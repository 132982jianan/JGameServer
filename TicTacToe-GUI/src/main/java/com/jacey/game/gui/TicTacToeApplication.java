package com.jacey.game.gui;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * @Description: 程序启动类
 * @Author: JaceyRuan
 * @Email: jacey.ruan@outlook.com
 */
@SpringBootApplication
@ComponentScan("com.jacey.game")
public class TicTacToeApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(TicTacToeApplication.class);
        // Swing 客户端必须运行在非 headless 模式（Spring Boot 默认强制 java.awt.headless=true）
        app.setHeadless(false);
        app.addListeners(new ApplicationReadyEventListener());
        app.run(args);
    }
}
