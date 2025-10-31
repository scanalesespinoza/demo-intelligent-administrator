package com.iadmin.ui;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.isEmptyString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusMock;
import org.junit.jupiter.api.Test;

@QuarkusTest
class SmokeTest {

    @Test
    void indexShouldRenderPage() {
        ApiClient apiClient = mock(ApiClient.class);
        QuarkusMock.installMockForType(apiClient, ApiClient.class);
        when(apiClient.fetchReport(anyString(), anyInt())).thenReturn(null);

        given()
                .when().get("/")
                .then()
                .statusCode(200)
                .header("Content-Type", containsString("text/html"))
                .body(not(isEmptyString()));
    }
}
