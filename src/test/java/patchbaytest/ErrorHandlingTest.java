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
