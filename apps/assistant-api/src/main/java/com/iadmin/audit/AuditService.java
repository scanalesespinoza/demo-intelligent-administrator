package com.iadmin.audit;

import com.iadmin.report.Report;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

@ApplicationScoped
public class AuditService {

    private static final int MAX_ENTRIES = 100;
    private final Deque<AuditEntry> history = new ArrayDeque<>();

    public synchronized void recordInteraction(String message,
            String namespace,
            Instant from,
            Instant to,
            Report report,
            String narrative) {
        if (history.size() >= MAX_ENTRIES) {
            history.removeFirst();
        }
        history.addLast(new AuditEntry(Instant.now(), message, namespace, from, to, report, narrative));
    }

    public synchronized List<AuditEntry> export() {
        return new ArrayList<>(history);
    }

    public record AuditEntry(Instant timestamp,
            String message,
            String namespace,
            Instant from,
            Instant to,
            Report report,
            String narrative) {
    }
}
