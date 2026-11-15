package com.platform;

import java.nio.file.Path;
import java.sql.SQLException;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "operations.availability.enabled", havingValue = "true")
public class PlatformAvailabilityScheduler {
    private final PlatformAvailabilityService service;
    private final Path evidence;
    private final int port;
    private final long owner;
    private final int cooldown;

    public PlatformAvailabilityScheduler(PlatformAvailabilityService service,
            @Value("${operations.availability.evidence-path}") Path evidence,
            @Value("${operations.availability.port:9093}") int port,
            @Value("${operations.availability.owner-user-id}") long owner,
            @Value("${operations.availability.cooldown-seconds:300}") int cooldown) {
        if (!evidence.isAbsolute() || port < 1 || port > 65535 || owner < 1 || cooldown < 0 || cooldown > 86400) {
            throw new IllegalArgumentException("Invalid platform availability configuration");
        }
        this.service = service;
        this.evidence = evidence;
        this.port = port;
        this.owner = owner;
        this.cooldown = cooldown;
    }

    @Scheduled(fixedDelayString = "${operations.availability.delay-ms:30000}")
    public void tick() {
        try {
            service.importEvidence(evidence, port, owner, cooldown);
        } catch (SQLException | RuntimeException failure) {
            LoggerFactory.getLogger(getClass()).warn("可用性证据未导入，type={}", failure.getClass().getSimpleName());
        }
    }
}
