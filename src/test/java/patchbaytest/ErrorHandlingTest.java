package patchbaytest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import patchbaytest.support.Http;
import patchbaytest.support.Mock;
import patchbaytest.support.Patchbay;
import patchbaytest.support.PatchbayTest;

/**
 * What callers get when the vendor fails or the caller sends bad input (runtime rules in the slice plan): vendor
 * errors pass through, faults become 502, timeouts 504, unreadable answers 500, bad input 400/415 without an
 * outbound call. docker-compose.yml sets Patchbay's outbound timeout to 2 s.
 */
class ErrorHandlingTest extends PatchbayTest {

    @BeforeAll
    static void publish() {
        Patchbay.ensurePublished("edge-status", "edge-slow", "edge-sluggish", "edge-reset", "edge-empty", "edge-garbage",
                "edge-malformed", "edge-unreachable", "books-create", "edge-types");
    }

    @ParameterizedTest
    @CsvSource({
            "400, application/json, vendor says 400",
            "401, application/json, unauthorized",
            "403, application/json, forbidden",
            "404, application/json, not-found",
            "409, application/json, conflict",
            "422, application/json, can't be blank",
            "500, application/json, internal",
            "502, text/html, Bad gateway",
            "503, text/plain, maintenance"})
    void aVendorErrorIsPassedThroughWithItsStatusBodyAndContentType(int status, String contentType, String bodyPart) {
        var r = Patchbay.call("GET", "edge-status", Map.of("code", status));

        assertThat(r.status()).isEqualTo(status);
        assertThat(r.header("X-Integration-Error")).isEqualTo("remote");
        assertThat(r.header("Content-Type")).startsWith(contentType);
        assertThat(r.body()).contains(bodyPart);
        assertThat(Mock.received("GET", "/edge/status")).as("no retry").hasSize(1);
    }

    /** A connection's base URL goes into the route's endpoint, so user info, a fragment or a placeholder is refused. */
    @ParameterizedTest
    @ValueSource(strings = {"http://user:pw@mock:9999", "http://mock:9999/#top", "http://mock:9999/{{secret}}"})
    void aConnectionBaseUrlThatIsNotAPlainUrlIsRefusedAndNothingChanges(String url) {
        String before = Patchbay.admin("GET", "/admin/api/connections/unreachable").body();

        var r = Http.send("PUT", Patchbay.URL + "/admin/api/connections/unreachable", "application/json",
                "{\"baseUrl\":\"" + url + "\"}", Map.of());

        assertThat(r.status()).as("%s", r).isEqualTo(400);
        assertThat(Patchbay.admin("GET", "/admin/api/connections/unreachable").body()).isEqualTo(before);
    }

    /** The outbound HTTP client must not wait for Retry-After and call again (fixed in OutboundHttpConfig). */
    @Test
    void aRateLimitAnswerWithRetryAfterIsPassedThroughNotRetried() {
        var r = Patchbay.call("GET", "edge-status", Map.of("code", 429));

        assertThat(Mock.received("GET", "/edge/status")).as("calls to the vendor").hasSize(1);
        assertThat(r.status()).isEqualTo(429);
        assertThat(r.header("X-Integration-Error")).isEqualTo("remote");
    }

    @Test
    void aSuccessfulVendorAnswerIsMapped() {
        var r = Patchbay.call("GET", "edge-status", Map.of("code", 200));

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().path("ok").asBoolean()).isTrue();
        assertThat(r.header("X-Integration-Error")).isNull();
    }

    @Test
    void aVendorThatDoesNotAnswerInTimeGives504() {
        long start = System.nanoTime();
        var r = Patchbay.call("GET", "edge-slow", Map.of());

        assertThat(r.status()).isEqualTo(504);
        assertThat(r.json().path("error").asText()).isEqualTo("remote-timeout");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(4));
    }

    /** NFR-001: calls that wait for a slow vendor run side by side; they used to run 8 at a time. */
    @Test
    void parallelSlowCallsAreNotServedInBatches() throws Exception {
        var client = java.net.http.HttpClient.newHttpClient();
        int calls = 24;
        long start = System.nanoTime();
        var sent = new java.util.ArrayList<java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<String>>>();
        for (int i = 0; i < calls; i++) {
            sent.add(client.sendAsync(java.net.http.HttpRequest.newBuilder(java.net.URI.create(Patchbay.endpoint("edge-sluggish"))).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString()));
        }
        for (var f : sent) {
            assertThat(f.get().statusCode()).isEqualTo(200);
        }
        // Each vendor call takes 0.5 s; three batches of 8 would take 1.5 s.
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1300));
        assertThat(Mock.received("GET", "/edge/sluggish")).hasSize(calls);
    }

    /** Every answer carries the caller's request id, so a failed call can be found in Patchbay's log. */
    @Test
    void theRequestIdComesBackWithAnError() {
        var r = Patchbay.call("GET", "edge-status", Map.of("code", 500), Map.of("X-Request-Id", "e2e-4711"));

        assertThat(r.status()).isEqualTo(500);
        assertThat(r.header("X-Request-Id")).isEqualTo("e2e-4711");
    }

    @Test
    void aSlowVendorInsideTheTimeoutIsFine() {
        var r = Patchbay.call("GET", "edge-sluggish", Map.of());

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().path("ok").asBoolean()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"edge-reset", "edge-empty", "edge-garbage"})
    void aBrokenConnectionGives502RemoteFailure(String slug) {
        var r = Patchbay.call("GET", slug, Map.of());

        assertThat(r.status()).isEqualTo(502);
        assertThat(r.json().path("error").asText()).isEqualTo("remote-failure");
        assertThat(r.json().path("integration").asText()).isEqualTo(slug);
    }

    @Test
    void aVendorThatCannotBeReachedGives502RemoteUnreachable() {
        var r = Patchbay.call("GET", "edge-unreachable", Map.of());

        assertThat(r.status()).isEqualTo(502);
        assertThat(r.json().path("error").asText()).isEqualTo("remote-unreachable");
    }

    @Test
    void aSuccessfulAnswerThatIsNotValidJsonGives500MappingError() {
        var r = Patchbay.call("GET", "edge-malformed", Map.of());

        assertThat(r.status()).isEqualTo(500);
        assertThat(r.json().path("error").asText()).isEqualTo("mapping-error");
    }

    @Test
    void aBodyThatIsNotJsonIs400() {
        var r = Patchbay.send("POST", "books-create", "{\"title\":");

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.json().path("error").asText()).isEqualTo("bad-request");
        assertThat(Mock.total()).isZero();
    }

    @Test
    void aJsonArrayInsteadOfAnObjectIs400() {
        var r = Patchbay.send("POST", "books-create", "[{\"title\":\"T\",\"pages\":1}]");

        assertThat(r.status()).isEqualTo(400);
        assertThat(Mock.total()).isZero();
    }

    @Test
    void aBodyThatIsNotDeclaredAsJsonIs415() {
        var r = Http.send("POST", Patchbay.endpoint("books-create"), "text/plain", "{\"title\":\"T\",\"pages\":1}", Map.of());

        assertThat(r.status()).isEqualTo(415);
        assertThat(Mock.total()).isZero();
    }

    /** The gate decides on the path the router sees, so a ;parameter can't skip its checks (it used to). */
    @Test
    void aPathParameterDoesNotSkipTheGate() {
        var r = Http.send("POST", Patchbay.URL + "/api/v1/integrations;x/books-create", "text/plain", "{\"title\":\"T\",\"pages\":1}", Map.of());

        assertThat(r.status()).as("%s", r).isEqualTo(415);
        assertThat(Mock.total()).isZero();
    }

    /** mapper.max-body-bytes (10 MiB by default) also limits a chunked JSON body, which declares no length. */
    @Test
    void aChunkedJsonBodyOverTheLimitIs413() throws Exception {
        byte[] big = ("{\"title\":\"" + "x".repeat(11 * 1024 * 1024) + "\",\"pages\":1}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var r = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(Patchbay.endpoint("books-create")))
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(big))).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());

        assertThat(r.statusCode()).as(r.body()).isEqualTo(413);
        assertThat(r.body()).contains("payload-too-large");
        assertThat(Mock.total()).isZero();
    }

    /** A contract may not refer to files outside itself: the engine would read its own files or call other hosts. */
    @Test
    void aContractWithAnExternalReferenceIsRefused() {
        String text = """
                {"openapi":"3.0.1","info":{"title":"t","version":"1"},"paths":{"/a":{"get":{"responses":{"200":{"description":"ok",
                  "content":{"application/json":{"schema":{"$ref":"../config/connections/shop.json#/x"}}}}}}}}}""";
        var r = Http.send("POST", Patchbay.URL + "/admin/api/contracts", "application/json",
                patchbaytest.support.Json.write(Map.of("name", "ref-probe", "content", text)), Map.of());

        assertThat(r.status()).as("%s", r).isEqualTo(400);
        assertThat(r.body()).contains("../config/connections/shop.json#/x");
        assertThat(Patchbay.admin("GET", "/admin/api/contracts/ref-probe").status()).isNotEqualTo(200);
    }

    @Test
    void queryParametersOfTheWrongTypeAreAllReported() {
        var r = Patchbay.call("GET", "edge-types", Map.of("count", "x", "ratio", "half", "active", "maybe", "day", "23.10.2026",
                "at", "yesterday", "ids", "one"));

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.json().path("problems").toString())
                .contains("count").contains("ratio").contains("active").contains("day").contains("at").contains("ids");
        assertThat(Mock.total()).isZero();
    }

    @Test
    void aMissingRequiredQueryParameterIs400() {
        var r = Patchbay.call("GET", "edge-status", Map.of());

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.json().path("problems").toString()).contains("code: required");
        assertThat(Mock.total()).isZero();
    }

    @Test
    void anUnknownIntegrationIs404() {
        var r = Patchbay.call("GET", "no-such-integration", Map.of());

        assertThat(r.status()).isEqualTo(404);
        assertThat(Mock.total()).isZero();
    }

    @Test
    void aMethodOtherThanTheIntegrationsIs405() {
        var r = Patchbay.send("POST", "edge-status", "{\"code\":200}");

        assertThat(r.status()).isEqualTo(405);
        assertThat(Mock.total()).isZero();
    }
}
