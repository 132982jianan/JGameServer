package com.jacey.game.db.service;

import java.util.Map;

/**
 * @Description: sessionId操作
 * @Author: JaceyRuan
 * @Email: jacey.ruan@outlook.com
 */
public interface SessionIdService {

    /**
     * 添加和获取下一个可用的sessionId(redis提供的自增id)
     *
     * @return
     */
    int addAndGetNextAvailableSessionId();

    /**
     *
     * @param userId
     * @return
     */
    Integer getOneUserIdToSessionId(int userId);

    /**
     *
     * @return
     */
    Map<Integer, Integer> getAllUserIdToSessionId();

    /**
     *
     * @param userId
     * @param sessionId
     */
    void setOneUserIdToSessionId(int userId, int sessionId);

    /**
     *
     * @param userId
     */
    void removeOneUserIdToSessionId(int userId);

    /**
     *
     * @param sessionId
     * @return
     */
    Integer getOneSessionIdToUserId(int sessionId);

    /**
     *
     * @param sessionId
     * @param userId
     */
    void setOneSessionIdToUserId(int sessionId, int userId);

    /**
     *
     * @param sessionId
     */
    void removeOneSessionIdToUserId(int sessionId);

}
