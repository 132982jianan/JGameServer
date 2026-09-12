package com.jacey.game.db.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * @Description: Mongo 自增id序列生成器（counters集合原子自增，替代Mysql AUTO_INCREMENT）
 * @Author: JaceyRuan
 * @Email: jacey.ruan@outlook.com
 */
@Service
public class MongoSequenceGenerator {

    public static final String COUNTERS_COLLECTION = "counters";

    @Autowired
    private MongoTemplate mongoTemplate;

    /**
     * 获取下一个自增id（counter不存在时从1开始）
     * @param counterName 计数器名称（一般传集合名）
     * @return 下一个可用id
     */
    public int getNextSequence(String counterName) {
        Query query = new Query(Criteria.where("_id").is(counterName));
        Update update = new Update().inc("seq", 1);
        Counter counter = mongoTemplate.findAndModify(query, update,
                FindAndModifyOptions.options().upsert(true).returnNew(true), Counter.class, COUNTERS_COLLECTION);
        return counter.getSeq();
    }

    /**
     * counters集合文档结构
     */
    public static class Counter {
        private String id;
        private int seq;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public int getSeq() { return seq; }
        public void setSeq(int seq) { this.seq = seq; }
    }
}
