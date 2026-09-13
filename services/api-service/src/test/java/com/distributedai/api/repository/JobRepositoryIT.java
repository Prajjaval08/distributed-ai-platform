package com.distributedai.api.repository;

import com.distributedai.api.entity.Job;
import com.distributedai.api.entity.JobPriority;
import com.distributedai.api.entity.JobStatus;
import com.distributedai.api.entity.User;
import com.distributedai.api.entity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JobRepositoryIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ai_platform_test")
                    .withUsername("test_user")
                    .withPassword("test_password");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        registry.add("spring.replica-datasource.url", postgres::getJdbcUrl);
        registry.add("spring.replica-datasource.username", postgres::getUsername);
        registry.add("spring.replica-datasource.password", postgres::getPassword);
    }

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void findByUserId_returnsOnlyThatUsersJobs() {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();

        createUser(userA);
        createUser(userB);

        Job jobA = newJob(userA);
        Job jobB = newJob(userB);

        jobRepository.save(jobA);
        jobRepository.save(jobB);

        var page = jobRepository.findByUserId(
                userA,
                PageRequest.of(0, 10)
        );

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getUserId())
                .isEqualTo(userA);
    }

    @Test
    void findByIdAndUserId_forWrongUser_returnsEmpty() {
        UUID owner = UUID.randomUUID();
        UUID intruder = UUID.randomUUID();

        createUser(owner);
        createUser(intruder);

        Job job = newJob(owner);
        Job saved = jobRepository.save(job);

        var result = jobRepository.findByIdAndUserId(
                saved.getId(),
                intruder
        );

        assertThat(result).isEmpty();
    }

    private void createUser(UUID userId) {
        User user = new User();
        user.setId(userId);
        user.setEmail(userId + "@test.com");
        user.setPasswordHash("test-password-hash");
        user.setRole(UserRole.USER);

        userRepository.save(user);
    }

    private Job newJob(UUID userId) {
        Job job = new Job();
        job.setUserId(userId);
        job.setPrompt("test prompt");
        job.setModel("test-model");
        job.setStatus(JobStatus.QUEUED);
        job.setPriority(JobPriority.NORMAL);

        return job;
    }
}
