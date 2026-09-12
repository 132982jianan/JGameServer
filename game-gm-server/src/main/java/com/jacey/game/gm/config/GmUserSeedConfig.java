package com.jacey.game.gm.config;

import com.jacey.game.db.entity.GmUserEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * @Description: GM账户初始数据（替代原Mysql jgame_server.sql中的admin账户导入）
 * @Author: JaceyRuan
 * @Email: jacey.ruan@outlook.com
 */
@Slf4j
@Configuration
public class GmUserSeedConfig implements ApplicationRunner {

    @Autowired
    private MongoTemplate mongoTemplate;

    @Override
    public void run(ApplicationArguments args) {
        Query query = new Query(Criteria.where("username").is("admin"));
        if (mongoTemplate.exists(query, GmUserEntity.class)) {
            return;
        }
        GmUserEntity admin = new GmUserEntity();
        admin.setUserId(1);
        admin.setUsername("admin");
        // MD5(admin) 与原 doc/sql/jgame_server.sql 中 admin 账户一致
        admin.setPasswordMD5("21232F297A57A5A743894A0E4A801FC3");
        mongoTemplate.save(admin);
        log.info("GmUser seed: created default admin account (userId=1)");
    }
}
