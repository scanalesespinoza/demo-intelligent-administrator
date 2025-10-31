package com.iadmin.ui.model;

public record ChatRequest(String message, String namespace, String from, String to, Integer topN) {
}
