package com.agentflow.common;

/**
 * 统一接口返回结构。code=0 表示成功，非 0 为错误码。
 * 用 record 而不是 class + Lombok：不可变、样板代码为零，也是 JDK 21 时代的主流写法。
 */
public record Result<T>(int code, String message, T data) {

    public static <T> Result<T> ok(T data) {
        return new Result<>(0, "ok", data);
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null);
    }
}
