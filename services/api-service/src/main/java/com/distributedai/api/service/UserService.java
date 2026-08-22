package com.distributedai.api.service;

import com.distributedai.api.entity.User;
import com.distributedai.api.exception.JobNotFoundException;
import com.distributedai.api.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public User getCurrentUser(UUID userId) {
        // Reusing JobNotFoundException's shape is wrong here semantically -
        // a dedicated UserNotFoundException would be cleaner, but a
        // authenticated user's own ID failing to resolve to a row is an
        // internal-consistency violation (their JWT is valid, but their
        // row is gone), not a normal client error path. Falling through to
        // the generic 500 handler is arguably more honest here than
        // inventing a 404 for a scenario that indicates something is wrong
        // server-side, not a bad request.
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found: " + userId));
    }
}