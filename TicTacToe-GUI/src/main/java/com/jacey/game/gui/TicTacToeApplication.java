package com.jacey.game.gui;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
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
        // 客户端无 HTTP 服务，禁用内嵌 Tomcat，避免多开时 8081 端口冲突
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addListeners(new ApplicationReadyEventListener());
        app.run(args);
    }
}
