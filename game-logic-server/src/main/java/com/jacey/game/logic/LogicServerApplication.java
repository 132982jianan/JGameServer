package com.jacey.game.logic;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * @Description:
 * @Author: JaceyRuan
 * @Email: jacey.ruan@outlook.com
 */
@SpringBootApplication
@ComponentScan(value= {"com.jacey.game"})
public class LogicServerApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(LogicServerApplication.class);
        app.addListeners(new ApplicationReadyEventListener());
        app.run(args);
    }

}
