package com.jacey.game.db.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

/**
 * @Description: 对战数据归档
 * @Author: JaceyRuan
 * @Email: jacey.ruan@outlook.com
 */
@Data
@Document(collection = BattleRecordEntity.COLLECTION_NAME)
public class BattleRecordEntity {

    public static final String COLLECTION_NAME = "battle_record";

    @Id
    private int id;

    /** 对战类型 */
    private int battleType;

    /** 对战id */
    private String battleId;

    /** 对战用户id String  逗号分隔 */
    private String userIdList;

    /** 对战开始时间 */
    private Date battleStartTimestamp;

    /** 对战结束时间 */
    private Date battleEndTimestamp;

    /** 回合数 */
    private int turnCount;

    /** 获胜方用户Id */
    private int winnerUserId;

    /** 获胜原因 */
    private int gameOverReason;
}
