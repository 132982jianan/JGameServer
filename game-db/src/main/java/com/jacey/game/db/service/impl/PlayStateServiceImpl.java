package com.jacey.game.db.service.impl;

import com.jacey.game.common.proto3.CommonEnum;
import com.jacey.game.common.proto3.CommonEnum.UserOnlineStateEnum;
import com.jacey.game.common.proto3.CommonMsg;
import com.jacey.game.db.entity.PlayStateEntity;
import com.jacey.game.db.service.MongoSequenceGenerator;
import com.jacey.game.db.service.PlayStateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * @Description: 用户状态修改service（MongoTemplate实现）
 * @Author: JaceyRuan
 * @Email: jacey.ruan@outlook.com
 */
@Service
public class PlayStateServiceImpl implements PlayStateService {

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private MongoSequenceGenerator sequenceGenerator;

    @Override
    public void changeUserOnlineState(int userId, boolean isOnline) {
        PlayStateEntity playStateEntity = findByUserId(userId);
        playStateEntity.setUserOnlineState(isOnline ? UserOnlineStateEnum.Online_VALUE : UserOnlineStateEnum.Offline_VALUE);
        mongoTemplate.save(playStateEntity);
    }

    @Override
    public CommonMsg.UserState getUserStateByUserId(int userId) {
        PlayStateEntity playStateEntity = findByUserId(userId);
        CommonMsg.UserState.Builder userState = CommonMsg.UserState.newBuilder();
        userState.setOnlineStateValue(playStateEntity.getUserActionState());
        userState.setActionStateValue(playStateEntity.getUserActionState());
        if (playStateEntity.getUserActionState() == CommonEnum.UserActionStateEnum.Matching_VALUE) {
            // 处于匹配中
            userState.setBattleTypeValue(playStateEntity.getBattleType());
        } else if (playStateEntity.getUserActionState() == CommonEnum.UserActionStateEnum.Playing_VALUE) {
            // 处于对战中
            userState.setBattleTypeValue(playStateEntity.getBattleType());
            userState.setBattleId(playStateEntity.getBattleId());
        }
        return userState.build();
    }

    @Override
    public PlayStateEntity getPlayStateByUserId(int userId) {
        return findByUserId(userId);
    }

    @Override
    public void create(PlayStateEntity playStateEntity) {
        playStateEntity.setId(sequenceGenerator.getNextSequence(PlayStateEntity.COLLECTION_NAME));
        mongoTemplate.save(playStateEntity);
    }

    @Override
    public void changeUserActionState(int userId, int userActionState, int battleType, String battleId) {
        PlayStateEntity playStateEntity = findByUserId(userId);
        playStateEntity.setUserActionState(userActionState);
        playStateEntity.setBattleType(battleType);
        playStateEntity.setBattleId(battleId);
        mongoTemplate.save(playStateEntity);
    }

    private PlayStateEntity findByUserId(int userId) {
        Query query = new Query(Criteria.where("userId").is(userId));
        return mongoTemplate.findOne(query, PlayStateEntity.class);
    }

}
