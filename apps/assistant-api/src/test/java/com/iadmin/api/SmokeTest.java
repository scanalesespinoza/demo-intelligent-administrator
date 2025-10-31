package com.iadmin.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class SmokeTest {

    @Test
    void healthEndpointShouldReturnOk() {
        given()
                .when().get("/healthz")
                .then()
                .statusCode(200)
                .body(equalTo("ok"));
    }
}
