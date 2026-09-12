package com.jacey.game.db.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * @Description: GM账户
 * @Author: JaceyRuan
 * @Email: jacey.ruan@outlook.com
 */
@Data
@Document(collection = "gm_user")
public class GmUserEntity {

    @Id
    private int userId;

    /** 玩家名称 */
    private String username;

    /** MD5加密密码 */
    private String passwordMD5;

}
