package patchbaytest.support;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The Patchbay container under test: its admin API (publish, versions) and the published integrations under
 * {@code /api/v1/integrations/<slug>}. {@link PatchbaySetup} configures it.
 */
public final class Patchbay {

    public static final String URL = System.getProperty("patchbay.url", "http://localhost:18080");

    private Patchbay() {
    }

    /** Fails with the setup error when one of these integrations could not be imported or published. */
    public static void ensurePublished(String... slugs) {
        for (String slug : slugs) {
            PatchbaySetup.require("publish " + slug);
        }
    }

    public static Http.Response publish(String slug) {
        return admin("POST", "/admin/integrations/" + slug + "/publish");
    }

    public static Http.Response versions(String slug) {
        return admin("GET", "/admin/integrations/" + slug + "/versions");
    }

    public static Http.Response activate(String slug, String version) {
        return admin("POST", "/admin/integrations/" + slug + "/versions/" + version + "/activate");
    }

    public static JsonNode integrations() {
        return admin("GET", "/admin/integrations").json();
    }

    public static Http.Response admin(String method, String path) {
        return Http.send(method, URL + path, null, null, Map.of());
    }

    /** GET or DELETE: the input goes as query parameters. */
    public static Http.Response call(String method, String slug, Map<String, ?> query) {
        return call(method, slug, query, Map.of());
    }

    public static Http.Response call(String method, String slug, Map<String, ?> query, Map<String, String> headers) {
        String q = query.isEmpty() ? "" : "?" + Http.query(query);
        return Http.send(method, endpoint(slug) + q, null, null, headers);
    }

    /** POST, PUT or PATCH with a JSON body. */
    public static Http.Response send(String method, String slug, String jsonBody) {
        return send(method, slug, jsonBody, Map.of());
    }

    public static Http.Response send(String method, String slug, String jsonBody, Map<String, String> headers) {
        return Http.send(method, endpoint(slug), "application/json", jsonBody, headers);
    }

    public static String endpoint(String slug) {
        return URL + "/api/v1/integrations/" + slug;
    }
}
