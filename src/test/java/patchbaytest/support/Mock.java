package patchbaytest.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The WireMock container that stands in for the external APIs. Tests use its admin API to see exactly what
 * Patchbay sent: {@code /__admin/requests}.
 */
public final class Mock {

    public static final String URL = System.getProperty("mock.url", "http://localhost:18081");

    private Mock() {
    }

    /** One request as WireMock received it from Patchbay. */
    public record Received(String method, String url, Map<String, String> headers, String body, byte[] bytes) {

        public JsonNode json() {
            return Json.parse(body);
        }

        /** Header value, case-insensitive, or null. */
        public String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).findFirst()
                    .map(Map.Entry::getValue).orElse(null);
        }
    }

    /** Forgets every recorded request; the stubs stay. */
    public static void clearRequests() {
        Http.send("DELETE", URL + "/__admin/requests", null, null, Map.of());
    }

    /** Requests with this method and path (no query string), oldest first. */
    public static List<Received> received(String method, String urlPath) {
        String pattern = Json.write(Map.of("method", method, "urlPath", urlPath));
        JsonNode found = Http.send("POST", URL + "/__admin/requests/find", "application/json", pattern, Map.of()).json();
        List<Received> out = new ArrayList<>();
        for (JsonNode r : found.path("requests")) {
            Map<String, String> headers = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            r.path("headers").properties().forEach(e -> headers.put(e.getKey(), e.getValue().asText()));
            byte[] bytes = java.util.Base64.getDecoder().decode(r.path("bodyAsBase64").asText(""));
            out.add(new Received(r.path("method").asText(), r.path("url").asText(), headers, r.path("body").asText(), bytes));
        }
        return out.reversed();
    }

    /** The only request with this method and path; fails if there were none or several. */
    public static Received single(String method, String urlPath) {
        List<Received> all = received(method, urlPath);
        assertThat(all).as("requests %s %s that reached the mock", method, urlPath).hasSize(1);
        return all.getFirst();
    }

    /** Every request that reached the mock since the last {@link #clearRequests()}. */
    public static int total() {
        return Http.send("GET", URL + "/__admin/requests", null, null, Map.of()).json().path("requests").size();
    }
}
