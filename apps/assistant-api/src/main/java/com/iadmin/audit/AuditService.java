package com.iadmin.audit;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class AuditService {

    private final Deque<Map<String, Object>> entries = new ArrayDeque<>();
    private static final int MAX = 50;

    public synchronized void add(Map<String, Object> entry) {
        entries.addFirst(entry);
        while (entries.size() > MAX) {
            entries.removeLast();
        }
    }

    public synchronized List<Map<String, Object>> export() {
        return new ArrayList<>(entries);
    }
}
