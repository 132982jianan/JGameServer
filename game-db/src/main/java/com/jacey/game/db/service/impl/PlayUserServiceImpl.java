package com.jacey.game.db.service.impl;

import com.jacey.game.common.proto3.CommonEnum;
import com.jacey.game.common.proto3.CommonMsg;
import com.jacey.game.common.utils.DateTimeUtil;
import com.jacey.game.db.entity.PlayStateEntity;
import com.jacey.game.db.entity.PlayUserEntity;
import com.jacey.game.db.service.MongoSequenceGenerator;
import com.jacey.game.db.service.PlayStateService;
import com.jacey.game.db.service.PlayUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * @Description: 用户数据处理（MongoTemplate实现）
 * @Author: JaceyRuan
 * @Email: jacey.ruan@outlook.com
 */
@Service
public class PlayUserServiceImpl implements PlayUserService {

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private MongoSequenceGenerator sequenceGenerator;

    @Autowired
    private PlayStateService playStateService;

    @Override
    public boolean hasUsername(String username) {
        return findByUsername(username) != null;
    }

    @Override
    public boolean hasUserId(int userId) {
        return mongoTemplate.findById(userId, PlayUserEntity.class) != null;
    }

    @Override
    public PlayUserEntity findPlayUserByUsername(String username) {
        return findByUsername(username);
    }

    @Override
    public void createNewUser(PlayUserEntity playUserEntity) {
        // counters集合原子自增，替代Mysql AUTO_INCREMENT
        int userId = sequenceGenerator.getNextSequence(PlayUserEntity.COLLECTION_NAME);
        playUserEntity.setUserId(userId);

        mongoTemplate.save(playUserEntity);

        // 初始化UserState
        PlayStateEntity playStateEntity = new PlayStateEntity();
        playStateEntity.setUserId(userId);
        playStateEntity.setUserOnlineState(CommonEnum.UserOnlineStateEnum.Offline_VALUE);
        playStateEntity.setUserActionState(CommonEnum.UserActionStateEnum.ActionNone_VALUE);
        playStateService.create(playStateEntity);

    }

    @Override
    public Integer getUserIdByUsername(String username) {
        PlayUserEntity playUserEntity = findByUsername(username);
        return playUserEntity == null ? null : playUserEntity.getUserId();
    }

    @Override
    public CommonMsg.UserData getUserDataByUserId(int userId) throws Exception {

        PlayUserEntity playUserEntity = mongoTemplate.findById(userId, PlayUserEntity.class);
        if (playUserEntity == null) {
            return null;
        }
        // 构造user data体
        CommonMsg.UserData.Builder userDataBuilder = CommonMsg.UserData.newBuilder();
        userDataBuilder.setUserId(userId);
        userDataBuilder.setUsername(playUserEntity.getUsername());
        userDataBuilder.setNickname(playUserEntity.getNickname());
        userDataBuilder.setPasswordMD5(playUserEntity.getPasswordMD5());
        long registTimestamp = DateTimeUtil.dateToTimestamp(playUserEntity.getRegistTimestamp());
        userDataBuilder.setRegistTimestamp(registTimestamp);
        userDataBuilder.setRegistIp(playUserEntity.getRegistIp());
        if (playUserEntity.getLastLoginTimestamp() != null) {
            long lastLoginTimestamp = DateTimeUtil.dateToTimestamp(playUserEntity.getLastLoginTimestamp());
            userDataBuilder.setLastLoginTimestamp(lastLoginTimestamp);
        }
        if (playUserEntity.getLastLoginIp() != null) {
            userDataBuilder.setLastLoginIp(playUserEntity.getLastLoginIp());
        }

        return userDataBuilder.build();
    }

    @Override
    public CommonMsg.UserBriefInfo getUserBriefInfoByUserId(int userId) {
        PlayUserEntity playUserEntity = mongoTemplate.findById(userId, PlayUserEntity.class);
        if (playUserEntity == null) {
            return null;
        } else {
            CommonMsg.UserBriefInfo.Builder builder = CommonMsg.UserBriefInfo.newBuilder();
            builder.setUserId(userId);
            builder.setNickname(playUserEntity.getNickname());
            builder.setUserState(playStateService.getUserStateByUserId(userId));
            return builder.build();
        }
    }

    @Override
    public void update(CommonMsg.UserData userData) {
        // 更新玩家信息
        PlayUserEntity playUserEntity = new PlayUserEntity();
        playUserEntity.setUserId(userData.getUserId());           // 用户id
        playUserEntity.setUsername(userData.getUsername());       // 用户名
        playUserEntity.setNickname(userData.getNickname());       // 昵称
        playUserEntity.setPasswordMD5(userData.getPasswordMD5()); // MD5密码
        Date lastLoginDate = Date.from(DateTimeUtil.timestampToInstant(userData.getLastLoginTimestamp()));
        Date registTimestamp = Date.from(DateTimeUtil.timestampToInstant(userData.getRegistTimestamp()));
        playUserEntity.setRegistIp(userData.getRegistIp());       // 注册ip
        playUserEntity.setRegistTimestamp(registTimestamp);       // 注册时间
        playUserEntity.setLastLoginTimestamp(lastLoginDate);      // 最后登录时间
        playUserEntity.setLastLoginIp(userData.getLastLoginIp()); // 最后登录ip

        // 保存到MongoDB
        mongoTemplate.save(playUserEntity);
    }

    @Override
    public CommonMsg.UserInfo getUserInfoByUserId(int userId) throws Exception {
        CommonMsg.UserData userData = getUserDataByUserId(userId);
        if (userData == null) {
            return null;
        } else {
            CommonMsg.UserInfo.Builder builder = CommonMsg.UserInfo.newBuilder();
            builder.setUserId(userData.getUserId());
            builder.setUsername(userData.getUsername());
            builder.setNickname(userData.getNickname());
            builder.setUserState(playStateService.getUserStateByUserId(userData.getUserId()));     // 用户状态
            return builder.build();
        }
    }

    private PlayUserEntity findByUsername(String username) {
        Query query = new Query(Criteria.where("username").is(username));
        return mongoTemplate.findOne(query, PlayUserEntity.class);
    }

}
