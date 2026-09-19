package com.example.auth.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the custom login page; the credential check itself is performed by
 * {@link com.example.auth.security.IdentityAuthenticationProvider}.
 */
@Controller
public class LoginController {

    @GetMapping("/login")
    public String login() {
        return "login";
    }
}
