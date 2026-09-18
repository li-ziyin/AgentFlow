package com.agentflow.web;

import com.agentflow.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 第一个接口：验证 Spring Boot 能跑起来、依赖注入和统一返回结构正常。
 * 等真正的业务 Controller 有了以后这个文件会被删掉。
 */
@RestController
public class PingController {

    @GetMapping("/api/ping")
    public Result<String> ping() {
        return Result.ok("agentflow is running");
    }
}
