package com.jacey.game.battle;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * @Description:
 * @Author: JaceyRuan
 * @Email: jacey.ruan@outlook.com
 */
@SpringBootApplication
@ComponentScan("com.jacey.game")
public class BattleServerApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(BattleServerApplication.class);
        app.addListeners(new ApplicationReadyEventListener());
        app.run(args);
    }

}
