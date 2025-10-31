package com.iadmin.llm;

import com.iadmin.report.Report;

public interface LlmClient {
    String redactReport(Report report);
}
