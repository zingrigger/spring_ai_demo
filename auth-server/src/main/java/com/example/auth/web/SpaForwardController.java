package com.example.auth.web;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 把 SPA 的客户端路由转发到前端构建产物；外壳本身没有数据，数据接口仍然逐条鉴权。
 */
@Controller
public class SpaForwardController {

    @GetMapping({"/", "/login", "/organizations", "/consent"})
    String spa(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
        return "forward:/index.html";
    }
}
