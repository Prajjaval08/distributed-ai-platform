package com.distributedai.api.service;

import com.distributedai.api.dto.AuthResponse;
import com.distributedai.api.dto.LoginRequest;
import com.distributedai.api.dto.RegisterRequest;
import com.distributedai.api.entity.User;
import com.distributedai.api.entity.UserRole;
import com.distributedai.api.exception.EmailAlreadyExistsException;
import com.distributedai.api.exception.InvalidCredentialsException;
import com.distributedai.api.repository.UserRepository;
import com.distributedai.api.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyExistsException(request.email());
        }

        User user = new User();
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(UserRole.USER);

        User saved = userRepository.save(user);

        String token = jwtService.generateToken(saved.getId(), saved.getEmail(), saved.getRole().name());
        return AuthResponse.of(token, jwtService.getExpirationSeconds(), saved.getEmail(), saved.getRole().name());
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        String token = jwtService.generateToken(user.getId(), user.getEmail(), user.getRole().name());
        return AuthResponse.of(token, jwtService.getExpirationSeconds(), user.getEmail(), user.getRole().name());
    }
}