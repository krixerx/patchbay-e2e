package patchbaytest;

import static org.assertj.core.api.Assertions.assertThat;
import static patchbaytest.support.Json.assertJsonEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import patchbaytest.support.Mock;
import patchbaytest.support.Patchbay;
import patchbaytest.support.PatchbayTest;

/**
 * Header isolation in both directions (OV-8), mapped and connection headers (D-27), outbound API keys, and how typed
 * query parameters are written to the vendor.
 */
class HeadersAndAuthTest extends PatchbayTest {

    @BeforeAll
    static void publish() {
        Patchbay.ensurePublished("edge-headers", "edge-secure-query", "edge-secure-header", "edge-types", "books-create");
    }

    @Test
    void callerHeadersNeverReachTheVendorOnlyMappedAndConnectionHeadersDo() {
        var r = Patchbay.send("POST", "edge-headers", """
                {"message":"hi","priority":5,"correlationId":"corr-1","tenant":"t1"}""",
                Map.of("Authorization", "Bearer caller-token", "Cookie", "session=abc", "X-API-Key", "caller-key",
                        "X-Custom", "custom", "X-Forwarded-For", "10.0.0.1"));

        assertThat(r.status()).isEqualTo(200);
        assertJsonEquals(r.json(), """
                {"message":"hi","priority":5,"correlationId":"corr-1"}""");
        var sent = Mock.single("POST", "/edge/echo");
        assertThat(sent.header("X-Correlation-Id")).isEqualTo("corr-1");
        assertThat(sent.header("X-Tenant")).isEqualTo("t1");
        assertThat(sent.header("X-Client-Id")).isEqualTo("patchbay-tests");
        for (String h : List.of("Authorization", "Cookie", "X-API-Key", "X-Custom", "X-Forwarded-For")) {
            assertThat(sent.header(h)).as("caller header %s at the vendor", h).isNull();
        }
        assertJsonEquals(sent.json(), """
                {"message":"hi","priority":5}""");
    }

    @Test
    void aMappedHeaderThatTheCallerDidNotSendIsLeftOut() {
        Patchbay.send("POST", "edge-headers", """
                {"message":"no headers"}""");

        var sent = Mock.single("POST", "/edge/echo");
        assertThat(sent.header("X-Correlation-Id")).isNull();
        assertThat(sent.header("X-Tenant")).isNull();
        assertThat(sent.header("X-Client-Id")).isEqualTo("patchbay-tests");
    }

    @Test
    void vendorResponseHeadersNeverReachTheCaller() {
        var echo = Patchbay.send("POST", "edge-headers", "{\"message\":\"m\"}");
        var book = Patchbay.send("POST", "books-create", "{\"title\":\"T\",\"pages\":1}");

        for (var r : List.of(echo, book)) {
            assertThat(r.header("X-Vendor-Secret")).isNull();
            assertThat(r.header("Set-Cookie")).isNull();
            assertThat(r.header("Matched-Stub-Id")).isNull();
        }
    }

    @Test
    void anApiKeyGoesIntoTheQueryFromTheEnvironment() {
        var r = Patchbay.call("GET", "edge-secure-query", Map.of());

        assertThat(r.status()).isEqualTo(200);
        assertThat(Mock.single("GET", "/edge/secure-query").url()).isEqualTo("/edge/secure-query?api_key=edge-query-key");
    }

    @Test
    void anApiKeyGoesIntoAHeaderFromTheEnvironmentAndWinsOverTheCallers() {
        var r = Patchbay.call("GET", "edge-secure-header", Map.of(), Map.of("X-Api-Key", "callers-own-key"));

        assertThat(r.status()).isEqualTo(200);
        assertThat(Mock.single("GET", "/edge/secure-header").header("X-Api-Key")).isEqualTo("edge-header-key");
    }

    @Test
    void typedQueryParametersAreSentInTheirCanonicalFormAndAListRepeatsTheParameter() {
        var r = Patchbay.call("GET", "edge-types", Map.of("count", "007", "ratio", "0.50", "active", "true", "day", "2026-10-23",
                "at", "2026-10-23T10:00:00Z", "label", "a b&c", "ids", List.of(1, 2, 3)));

        assertThat(r.status()).isEqualTo(200);
        String url = Mock.single("GET", "/edge/types").url();
        assertThat(url).contains("count=7").contains("ratio=0.5").contains("active=true").contains("day=2026-10-23")
                .contains("ids=1&ids=2&ids=3").contains("label=a%20b%26c");
        assertThat(url).containsAnyOf("at=2026-10-23T10%3A00%3A00Z", "at=2026-10-23T10:00:00Z");
    }
}
