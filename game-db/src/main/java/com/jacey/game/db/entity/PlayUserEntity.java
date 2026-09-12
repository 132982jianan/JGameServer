package com.jacey.game.db.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

/**
 * @Description: 玩家信息
 * @Author: JaceyRuan
 * @Email: jacey.ruan@outlook.com
 */
@Data
@Document(collection = PlayUserEntity.COLLECTION_NAME)
public class PlayUserEntity {

    public static final String COLLECTION_NAME = "play_user";

    @Id
    private int userId;

    /** 玩家名称 */
    private String username;

    /** 玩家昵称 */
    private String nickname;

    /** MD5加密密码 */
    private String passwordMD5;

    /** 注册时间戳 */
    private Date registTimestamp;

    /** 注册ip */
    private String registIp;

    /** 最后一次登录时间 */
    private Date lastLoginTimestamp;

    /** 最后一次登录ip */
    private String lastLoginIp;
}
