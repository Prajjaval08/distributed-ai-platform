package com.distributedai.api.controller;

import com.distributedai.api.dto.UserResponse;
import com.distributedai.api.entity.User;
import com.distributedai.api.service.UserService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public UserResponse me(Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        User user = userService.getCurrentUser(userId);
        return UserResponse.from(user);
    }

    // GET /api/v1/users/me/usage deliberately deferred - see Phase 4
    // Design Decisions. Needs real job history to aggregate meaningfully.
}