package com.distributedai.worker.repository;

import com.distributedai.worker.entity.JobEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface JobEventRepository extends JpaRepository<JobEvent, UUID> {
}
