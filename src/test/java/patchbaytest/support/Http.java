package patchbaytest.support;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;

/** A small blocking HTTP client; every call answers a {@link Response}, also for error statuses. */
public final class Http {

    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private Http() {
    }

    public record Response(int status, Map<String, List<String>> headers, byte[] bytes) {

        /** The body as UTF-8 text. */
        public String body() {
            return new String(bytes, StandardCharsets.UTF_8);
        }

        /** First value of a header, case-insensitive, or null. */
        public String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).findFirst()
                    .map(e -> e.getValue().getFirst()).orElse(null);
        }

        public JsonNode json() {
            return Json.parse(body());
        }

        @Override
        public String toString() {
            String b = body();
            return status + " " + (b.length() > 2000 ? b.substring(0, 2000) + "..." : b);
        }
    }

    /** Query string from name/value pairs; a list value repeats the parameter. Values are URL-encoded. */
    public static String query(Map<String, ?> params) {
        return params.entrySet().stream().flatMap(e -> (e.getValue() instanceof List<?> l ? l : List.of(e.getValue())).stream()
                .map(v -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(String.valueOf(v), StandardCharsets.UTF_8)))
                .collect(Collectors.joining("&"));
    }

    public static Response send(String method, String url, String contentType, String body, Map<String, String> headers) {
        return sendBytes(method, url, contentType, body == null ? null : body.getBytes(StandardCharsets.UTF_8), headers);
    }

    public static Response sendBytes(String method, String url, String contentType, byte[] body, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (contentType != null) {
            b.header("Content-Type", contentType);
        }
        headers.forEach(b::header);
        try {
            HttpResponse<byte[]> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Response(r.statusCode(), r.headers().map(), r.body());
        } catch (IOException e) {
            throw new IllegalStateException(method + " " + url + " failed: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** A multipart/form-data body: text parts and one file part. Answers the content type (with boundary) and bytes. */
    public record Multipart(String contentType, byte[] body) {

        public static Multipart of(Map<String, String> fields, String fileField, String fileName, String fileType, byte[] file) {
            String boundary = "----patchbay-test-" + System.nanoTime();
            var out = new java.io.ByteArrayOutputStream();
            fields.forEach((k, v) -> out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + k
                    + "\"\r\n\r\n" + v + "\r\n").getBytes(StandardCharsets.UTF_8)));
            out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + fileField + "\"; filename=\""
                    + fileName + "\"\r\nContent-Type: " + fileType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.writeBytes(file);
            out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return new Multipart("multipart/form-data; boundary=" + boundary, out.toByteArray());
        }
    }
}
