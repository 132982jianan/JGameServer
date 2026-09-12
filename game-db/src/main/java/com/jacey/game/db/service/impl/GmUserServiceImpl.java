package com.jacey.game.db.service.impl;

import com.jacey.game.db.dao.GmUserDAO;
import com.jacey.game.db.entity.GmUserEntity;
import com.jacey.game.db.service.GmUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * @Description: Gm用户操作（MongoTemplate实现）
 * @Author: JaceyRuan
 * @Email: jacey.ruan@outlook.com
 */
@Service
public class GmUserServiceImpl implements GmUserService {

    @Autowired
    private GmUserDAO gmUserDAO;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Override
    public void setGmUserTokenCache(String token, Integer expire) {
        gmUserDAO.setGmUserToken(token, expire);
    }

    @Override
    public String getGmUserTokenCache(String token) {
        return gmUserDAO.getGmUserToken(token);
    }

    @Override
    public GmUserEntity findGmUserByUsername(String username) {
        Query query = new Query(Criteria.where("username").is(username));
        return mongoTemplate.findOne(query, GmUserEntity.class);
    }
}
