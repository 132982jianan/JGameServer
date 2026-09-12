package com.jacey.game.db.service.impl;

import com.jacey.game.db.entity.BattleRecordEntity;
import com.jacey.game.db.service.BattleRecordService;
import com.jacey.game.db.service.MongoSequenceGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

/**
 * @Description: 对战数据归档处理（MongoTemplate实现）
 * @Author: JaceyRuan
 * @Email: jacey.ruan@outlook.com
 */
@Slf4j
@Service
public class BattleRecordServiceImpl implements BattleRecordService {

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private MongoSequenceGenerator sequenceGenerator;

    @Override
    public void saveBattleRecord(BattleRecordEntity battleRecordEntity) {
        if (battleRecordEntity.getId() == 0) {
            battleRecordEntity.setId(sequenceGenerator.getNextSequence(BattleRecordEntity.COLLECTION_NAME));
        }
        mongoTemplate.save(battleRecordEntity);
    }
}
