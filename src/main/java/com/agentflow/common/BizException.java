package com.agentflow.common;

/**
 * 业务异常：抛出时 message 就是给用户/调用方看的，全局异常处理器会原样返回。
 * 与之相对，未知异常（NPE、IO 错误等）细节只进日志，对外隐藏——避免泄露内部实现。
 */
public class BizException extends RuntimeException {

    public BizException(String message) {
        super(message);
    }
}
