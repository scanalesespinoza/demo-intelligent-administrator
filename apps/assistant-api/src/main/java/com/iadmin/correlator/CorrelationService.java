package com.iadmin.correlator;

import com.iadmin.report.Report;
import java.time.Instant;

public interface CorrelationService {
    Report analyze(String namespace, Instant from, Instant to, int topN);
}
