package com.iadmin.llm.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.iadmin.report.Report;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HttpLlmClientTest {

    HttpLlmClient client;

    @BeforeEach
    void setUp() {
        client = new HttpLlmClient();
        client.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        client.model = Optional.of("test-model");
        client.endpoint = "http://localhost/test";
        client.apiKey = Optional.empty();
    }

    @Test
    void extractContentFromOpenAIResponse() {
        var payload = "{\"choices\":[{\"message\":{\"content\":\"Hola mundo\"}}]}";
        var stub = new StubHttpClient(payload);
        client.setHttpClient(stub);

        Report report = new Report(
                new Report.TimeWindow(Instant.now(), Instant.now().plus(Duration.ofMinutes(5))),
                List.of(),
                "",
                List.of());

        String narrative = client.redactReport(report);
        assertEquals("Hola mundo", narrative);
    }

    @Test
    void includeAuthorizationHeaderWhenApiKeyPresent() {
        var payload = "{\"choices\":[{\"message\":{\"content\":\"Hola mundo\"}}]}";
        var stub = new StubHttpClient(payload);
        client.setHttpClient(stub);
        client.apiKey = Optional.of("test-key");

        Report report = new Report(
                new Report.TimeWindow(Instant.now(), Instant.now().plus(Duration.ofMinutes(5))),
                List.of(),
                "",
                List.of());

        client.redactReport(report);

        assertEquals("Bearer test-key", stub.lastRequest.headers().firstValue("Authorization").orElse(null));
    }

    static class StubHttpClient extends HttpClient {

        private final String payload;
        HttpRequest lastRequest;

        StubHttpClient(String payload) {
            this.payload = payload;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return null;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            lastRequest = request;
            return new SimpleResponse<>(request, (T) payload);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler) {
            return CompletableFuture.completedFuture(send(request, responseBodyHandler));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return CompletableFuture.completedFuture(send(request, responseBodyHandler));
        }

        static class SimpleResponse<T> implements HttpResponse<T> {

            private final HttpRequest request;
            private final T body;

            SimpleResponse(HttpRequest request, T body) {
                this.request = request;
                this.body = body;
            }

            @Override
            public int statusCode() {
                return 200;
            }

            @Override
            public HttpRequest request() {
                return request;
            }

            @Override
            public Optional<HttpResponse<T>> previousResponse() {
                return Optional.empty();
            }

            @Override
            public HttpHeaders headers() {
                return HttpHeaders.of(Map.of(), (a, b) -> true);
            }

            @Override
            public T body() {
                return body;
            }

            @Override
            public Optional<SSLSession> sslSession() {
                return Optional.empty();
            }

            @Override
            public URI uri() {
                return request.uri();
            }

            @Override
            public Version version() {
                return Version.HTTP_1_1;
            }

        }
    }
}
