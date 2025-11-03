package com.iadmin.correlator.impl;

import com.iadmin.config.ConfigConnector;
import com.iadmin.k8s.K8sConnector;
import com.iadmin.model.ConfigSnapshot;
import com.iadmin.model.DeploymentSummary;
import com.iadmin.model.K8sEvent;
import com.iadmin.model.LogChunk;
import com.iadmin.model.PodSummary;
import com.iadmin.report.Report;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RuleBasedCorrelationServiceTest {

    RuleBasedCorrelationService service;

    @BeforeEach
    void setUp() {
        service = new RuleBasedCorrelationService();
        service.k8s = new StubK8sConnector();
        service.cfg = new StubConfigConnector();
    }

    @Test
    void detectProbeAndPoolSignals() {
        Instant from = Instant.parse("2024-01-01T00:00:00Z");
        Instant to = Instant.parse("2024-01-01T00:15:00Z");

        Report report = service.analyze("demo", from, to, 5);

        assertFalse(report.findings().isEmpty());
        assertNotNull(report.summary());
        assertTrue(report.findings().size() > 1);
        Report.Finding main = report.findings().get(0);
        assertEquals("svc-a", main.service());
        assertTrue(main.severityScore() >= report.findings().get(1).severityScore());
        assertTrue(main.causeLikely().equals("Probe") || main.causeLikely().equals("ThreadPoolExhaustion"));
        assertTrue(main.confidence() > 0.6);
        assertTrue(main.signals().contains("Liveness probe failed") || main.signals().contains("RejectedExecution"));
    }

    static class StubK8sConnector implements K8sConnector {

        private final List<DeploymentSummary> deployments;
        private final List<PodSummary> pods;
        private final List<K8sEvent> events;
        private final Map<String, List<LogChunk>> logs;

        StubK8sConnector() {
            deployments = List.of(
                    new DeploymentSummary("svc-a", "demo", 3, 1, Map.of(), Map.of()),
                    new DeploymentSummary("svc-b", "demo", 2, 2, Map.of(), Map.of()));

            pods = List.of(
                    new PodSummary("svc-a-123", "demo", "CrashLoopBackOff", 5,
                            List.of(new PodSummary.ContainerState("main", false, null, null))),
                    new PodSummary("svc-a-456", "demo", "Running", 4,
                            List.of(new PodSummary.ContainerState("main", true, null, null))),
                    new PodSummary("svc-b-111", "demo", "Running", 0,
                            List.of(new PodSummary.ContainerState("main", true, null, null))));

            events = List.of(
                    new K8sEvent(Instant.parse("2024-01-01T00:05:00Z"), "Warning", "Liveness probe failed",
                            "Liveness probe failed: HTTP probe", "Pod", "svc-a-123"),
                    new K8sEvent(Instant.parse("2024-01-01T00:06:00Z"), "Warning", "BackOff",
                            "Back-off restarting failed container", "Pod", "svc-a-123"));

            LogChunk chunk = new LogChunk(
                    "svc-a-123",
                    "main",
                    Instant.parse("2024-01-01T00:04:00Z"),
                    Instant.parse("2024-01-01T00:05:00Z"),
                    new ArrayList<>(List.of(
                            "2024-01-01 00:04:30 ERROR pool thread RejectedExecution",
                            "2024-01-01 00:04:31 ERROR RANDOM_FAIL:exit")));
            logs = Map.of("svc-a-123:main", List.of(chunk));
        }

        @Override
        public List<DeploymentSummary> listDeployments(String namespace) {
            return deployments;
        }

        @Override
        public List<PodSummary> listPods(String namespace, Map<String, String> selector) {
            return pods;
        }

        @Override
        public List<K8sEvent> getEvents(String namespace, Instant from, Instant to) {
            return events;
        }

        @Override
        public List<LogChunk> getPodLogs(String namespace, String pod, String container,
                Instant from, Instant to, int tailLines) {
            return logs.getOrDefault(pod + ":" + container, List.of());
        }
    }

    static class StubConfigConnector implements ConfigConnector {

        @Override
        public ConfigSnapshot getDeploymentConfig(String namespace, String deploymentName) {
            return new ConfigSnapshot(Map.of(
                    "QUARKUS_DATASOURCE_JDBC_URL", "jdbc:postgresql://demo-db:5432/app",
                    "APP_THREAD_POOL_SIZE", "32"),
                    List.of(),
                    Map.of());
        }
    }
}
