package com.jacey.game.common.exception

/** 自定义 RPC 业务异常：携带协议错误码，上层据此构造错误响应 */
class RpcErrorException(val errorCode: Int) :
    RuntimeException(errorCode.toString())
