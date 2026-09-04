package com.distributedai.api.config;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

@Component
public class BusinessMetrics {

    private final Map<String, LongAdder> jobsCreatedByPriority = new ConcurrentHashMap<>();

    public void incrementJobsCreated(String priority) {
        jobsCreatedByPriority
                .computeIfAbsent(priority, ignored -> new LongAdder())
                .increment();
    }

    public String jobsCreatedPrometheusAlias() {
        if (jobsCreatedByPriority.isEmpty()) {
            return "";
        }

        StringBuilder lines = new StringBuilder();
        lines.append("# HELP jobs_created_total Jobs created by the API service\n");
        lines.append("# TYPE jobs_created_total counter\n");

        jobsCreatedByPriority.forEach((priority, count) -> lines
                .append("jobs_created_total{application=\"api-service\",priority=\"")
                .append(escapeLabelValue(priority))
                .append("\"} ")
                .append(count.sum())
                .append(".0\n"));

        return lines.toString();
    }

    private String escapeLabelValue(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\"", "\\\"");
    }
}
