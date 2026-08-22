package com.distributedai.api.repository;

import com.distributedai.api.entity.JobEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface JobEventRepository extends JpaRepository<JobEvent, UUID> {
}