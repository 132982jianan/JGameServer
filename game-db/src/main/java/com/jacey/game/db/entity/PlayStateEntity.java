package com.jacey.game.db.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * @Description: 玩家状态
 * @Author: JaceyRuan
 * @Email: jacey.ruan@outlook.com
 */
@Data
@Document(collection = PlayStateEntity.COLLECTION_NAME)
public class PlayStateEntity {

    public static final String COLLECTION_NAME = "play_state";
    @Id
    private int id;

    private int userId;

    /** 在线状态（在线or离线）  */
    private int userOnlineState;

    /** 行为状态（none or 匹配中 or 对战中 等） */
    private int userActionState;

    /** 对战类型（1v1 or ..） */
    private int battleType;

    /** 对战id */
    private String battleId;
}
