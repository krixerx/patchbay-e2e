package patchbaytest;

import static org.assertj.core.api.Assertions.assertThat;
import static patchbaytest.support.Json.assertJsonEquals;

import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import patchbaytest.support.Mock;
import patchbaytest.support.Patchbay;
import patchbaytest.support.PatchbayTest;

/**
 * The GoREST Users stand-in (mock/mappings/users): bearer auth from MAPPER_SECRET_* in docker-compose.yml, renamed
 * query parameters, vendor validation errors and a 204 delete.
 */
class UsersTest extends PatchbayTest {

    @BeforeAll
    static void publish() {
        Patchbay.ensurePublished("users-create", "users-search", "users-update", "users-delete");
    }

    @Test
    void postSendsTheBearerTokenFromTheEnvironmentNotTheCallersOwn() {
        var r = Patchbay.send("POST", "users-create", """
                {"fullName":"Mari Tamm","email":"mari@example.com","gender":"female"}""",
                Map.of("Authorization", "Bearer callers-own-token"));

        // GoREST answers 201; Patchbay answers every successful call with 200.
        assertThat(r.status()).isEqualTo(200);
        assertJsonEquals(r.json(), """
                {"userId":7001,"name":"Mari Tamm","email":"mari@example.com","gender":"female","status":"active"}""");
        var sent = Mock.single("POST", "/public/v2/users");
        assertThat(sent.header("Authorization")).isEqualTo("Bearer gorest-test-token");
        assertJsonEquals(sent.json(), """
                {"name":"Mari Tamm","email":"mari@example.com","gender":"female","status":"active"}""");
    }

    @Test
    void aVendorValidationErrorIsPassedThroughWithItsListBody() {
        var r = Patchbay.send("POST", "users-create", """
                {"fullName":"Dup","email":"taken@example.com"}""");

        assertThat(r.status()).isEqualTo(422);
        assertThat(r.header("X-Integration-Error")).isEqualTo("remote");
        assertJsonEquals(r.json(), """
                [{"field":"email","message":"has already been taken"}]""");
    }

    @Test
    void getSearchRenamesQueryParametersAndLeavesOutTheOnesNotSent() {
        var r = Patchbay.call("GET", "users-search", Map.of("status", "active", "perPage", 20));

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().path("users")).hasSize(3);
        assertJsonEquals(r.json().path("users").get(0), """
                {"userId":7001,"name":"Mari Tamm","email":"mari.tamm@example.com","status":"active"}""");
        String url = Mock.single("GET", "/public/v2/users").url();
        assertThat(url).contains("status=active").contains("per_page=20").doesNotContain("perPage").doesNotContain("name=")
                .doesNotContain("page=null");
    }

    @Test
    void putUpdatesWithBearerAuthAndMapsTheNestedEcho() {
        var r = Patchbay.send("PUT", "users-update", """
                {"userId":7002,"status":"inactive"}""");

        assertThat(r.status()).isEqualTo(200);
        assertJsonEquals(r.json(), """
                {"userId":7002,"status":"inactive"}""");
        var sent = Mock.single("PUT", "/public/v2/users/7002");
        assertThat(sent.header("Authorization")).isEqualTo("Bearer gorest-test-token");
        assertJsonEquals(sent.json(), """
                {"status":"inactive"}""");
    }

    @Test
    void deleteAnswered204WithoutABodyGivesAnEmptyOutput() {
        var r = Patchbay.call("DELETE", "users-delete", Map.of("userId", 7003));

        assertThat(r.status()).isEqualTo(200);
        assertJsonEquals(r.json(), "{}");
        assertThat(Mock.single("DELETE", "/public/v2/users/7003")).isNotNull();
    }
}
