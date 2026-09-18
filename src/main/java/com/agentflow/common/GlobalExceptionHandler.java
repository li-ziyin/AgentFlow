package com.agentflow.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：Controller 里只写正常逻辑，错误统一在这里转成 Result 结构。
 * 注意：后面做 SSE 流式接口时，流已开始的错误不能再走这里（HTTP 状态码已发出），
 * 那时要在流内发 error 事件——这是流式架构的一个经典考点，到时候回来看这个注释。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst()
                .orElse("Invalid request");
        return Result.error(400, msg);
    }

    /** 请求体本身不是合法 JSON（常见：客户端编码不是 UTF-8、格式写错）：属于调用方的问题，返回 400。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleUnreadable(HttpMessageNotReadableException e) {
        return Result.error(400, "请求体不是合法的 JSON（注意文件/终端编码需为 UTF-8）");
    }

    /** 路径存在但 HTTP 方法不对（如用 GET 访问 POST 接口）：返回 405。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return Result.error(405, "请求方法不支持：请检查接口要求的 HTTP 方法（如 POST）");
    }

    /** 路径不存在：不该被兜底处理器吞成 500，明确返回 404。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public Result<Void> handleNotFound(NoResourceFoundException e) {
        return Result.error(404, "接口不存在");
    }

    /** 业务异常：message 本来就是给人看的，原样返回。 */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e) {
        log.warn("Biz exception: {}", e.getMessage());
        return Result.error(500, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return Result.error(500, "Internal server error");
    }
}
