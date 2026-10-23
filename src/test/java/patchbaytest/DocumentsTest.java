package patchbaytest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

import patchbaytest.support.Http;
import patchbaytest.support.Json;
import patchbaytest.support.Mock;
import patchbaytest.support.Patchbay;
import patchbaytest.support.PatchbayTest;

/**
 * Documents in all three REST forms, on both sides: raw body, multipart/form-data and base64 in JSON
 * (mock/mappings/docs).
 * <p>
 * The proposed design (patchbay/docs-package): a payload field of type {@code blob} holds a file. Inside Patchbay it is
 * the JSON value {@code {"contentType","fileName","size","data":"<base64>"}}; links can use the whole blob or
 * {@code <field>.data}, {@code .fileName}, {@code .contentType}, {@code .size}. Callers send an Input with a blob as
 * JSON (the blob as that object), as multipart (one part per field, a blob is a file part) or as a raw body (the blob;
 * other fields as query parameters; file name from Content-Disposition). {@code formats.request} {@code multipart} or
 * {@code binary} sends the vendor multipart or the blob as the raw body; a non-JSON, non-XML answer is the blob
 * {@code $body}; {@code formats.output} {@code binary} answers the caller with one blob as raw bytes. Blobs larger than
 * {@code mapper.max-blob-bytes} (2 MiB in docker-compose.yml) are refused with 413.
 * <p>
 * The first two tests use plain text fields holding base64 and work without blobs.
 */
class DocumentsTest extends PatchbayTest {

    static final byte[] PDF = read("sample.pdf");
    static final byte[] LARGE = read("large.bin");
    static final String PDF_BASE64 = Base64.getEncoder().encodeToString(PDF);

    /** The blob of the sample PDF as a JSON caller sends it. */
    static final Map<String, Object> PDF_BLOB = Map.of("fileName", "sample.pdf", "contentType", "application/pdf", "data", PDF_BASE64);

    // ---- base64 in text fields (no blob type needed)

    @Test
    void base64TextFromTheVendorReachesTheCallerUnchanged() {
        Patchbay.ensurePublished("docs-get-base64");

        var r = Patchbay.call("GET", "docs-get-base64", Map.of("documentId", "DOC-1"));

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().path("fileName").asText()).isEqualTo("sample.pdf");
        assertThat(r.json().path("size").asInt()).isEqualTo(PDF.length);
        assertThat(Base64.getDecoder().decode(r.json().path("content").asText())).isEqualTo(PDF);
    }

    @Test
    void oneMegabyteOfBase64TextFromTheCallerReachesTheVendorUnchanged() {
        Patchbay.ensurePublished("docs-upload-base64");

        var r = Patchbay.send("POST", "docs-upload-base64", Json.write(Map.of("fileName", "large.bin",
                "contentType", "application/octet-stream", "content", Base64.getEncoder().encodeToString(LARGE))));

        assertThat(r.status()).isEqualTo(200);
        var sent = Mock.single("POST", "/docs/v1/documents/base64").json();
        assertThat(Base64.getDecoder().decode(sent.path("contentBase64").asText())).isEqualTo(LARGE);
    }

    // ---- uploads: caller form → vendor form

    @Test
    void multipartFromTheCallerGoesToTheVendorAsMultipart() {
        Patchbay.ensurePublished("docs-upload-multipart");
        var form = Http.Multipart.of(Map.of("title", "Contract"), "file", "sample.pdf", "application/pdf", PDF);

        var r = Http.sendBytes("POST", Patchbay.endpoint("docs-upload-multipart"), form.contentType(), form.body(), Map.of());

        assertThat(r.status()).as("%s", r).isEqualTo(200);
        assertJsonStored(r, "DOC-2");
        assertThat(r.json().path("title").asText()).isEqualTo("Contract");
        assertVendorGotMultipart("Contract");
    }

    @Test
    void aJsonBlobFromTheCallerGoesToTheVendorAsMultipart() {
        Patchbay.ensurePublished("docs-upload-multipart");

        var r = Patchbay.send("POST", "docs-upload-multipart", Json.write(Map.of("title", "From JSON", "file", PDF_BLOB)));

        assertThat(r.status()).as("%s", r).isEqualTo(200);
        assertJsonStored(r, "DOC-2");
        assertVendorGotMultipart("From JSON");
    }

    @Test
    void aRawBodyFromTheCallerGoesToTheVendorAsARawBody() {
        Patchbay.ensurePublished("docs-replace-content");

        var r = Http.sendBytes("PUT", Patchbay.endpoint("docs-replace-content") + "?documentId=DOC-9", "application/pdf", PDF,
                Map.of("Content-Disposition", "attachment; filename=\"sample.pdf\""));

        assertThat(r.status()).as("%s", r).isEqualTo(200);
        assertJsonStored(r, "DOC-9");
        assertVendorGotRaw("DOC-9");
    }

    @Test
    void aJsonBlobFromTheCallerGoesToTheVendorAsARawBody() {
        Patchbay.ensurePublished("docs-replace-content");

        var r = Patchbay.send("PUT", "docs-replace-content", Json.write(Map.of("documentId", "DOC-8", "content", PDF_BLOB)));

        assertThat(r.status()).as("%s", r).isEqualTo(200);
        assertJsonStored(r, "DOC-8");
        assertVendorGotRaw("DOC-8");
    }

    @Test
    void multipartFromTheCallerGoesToTheVendorAsBase64Json() {
        Patchbay.ensurePublished("docs-upload-as-base64");
        var form = Http.Multipart.of(Map.of(), "file", "sample.pdf", "application/pdf", PDF);

        var r = Http.sendBytes("POST", Patchbay.endpoint("docs-upload-as-base64"), form.contentType(), form.body(), Map.of());

        assertThat(r.status()).as("%s", r).isEqualTo(200);
        assertJsonStored(r, "DOC-3");
        assertVendorGotBase64Json();
    }

    @Test
    void aJsonBlobFromTheCallerGoesToTheVendorAsBase64Json() {
        Patchbay.ensurePublished("docs-upload-as-base64");

        var r = Patchbay.send("POST", "docs-upload-as-base64", Json.write(Map.of("file", PDF_BLOB)));

        assertThat(r.status()).as("%s", r).isEqualTo(200);
        assertJsonStored(r, "DOC-3");
        assertVendorGotBase64Json();
    }

    // ---- downloads: vendor form → caller form

    @Test
    void aRawPdfFromTheVendorReachesTheCallerRawWithTypeAndFileName() {
        Patchbay.ensurePublished("docs-download");

        var r = Patchbay.call("GET", "docs-download", Map.of("documentId", "DOC-1"));

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.header("Content-Type")).startsWith("application/pdf");
        assertThat(r.header("Content-Disposition")).contains("sample.pdf");
        assertThat(r.bytes()).isEqualTo(PDF);
    }

    @Test
    void oneMegabyteOfRawBinaryFromTheVendorReachesTheCallerComplete() {
        Patchbay.ensurePublished("docs-download");

        var r = Patchbay.call("GET", "docs-download", Map.of("documentId", "DOC-LARGE"));

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.header("Content-Type")).startsWith("application/octet-stream");
        assertThat(r.header("Content-Disposition")).contains("large.bin");
        assertThat(r.bytes()).isEqualTo(LARGE);
    }

    @Test
    void aRawPdfFromTheVendorReachesTheCallerAsAJsonBlob() {
        Patchbay.ensurePublished("docs-download-json");

        var r = Patchbay.call("GET", "docs-download-json", Map.of("documentId", "DOC-1"));

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.header("Content-Type")).startsWith("application/json");
        JsonNode blob = r.json().path("content");
        assertThat(blob.path("fileName").asText()).isEqualTo("sample.pdf");
        assertThat(blob.path("contentType").asText()).startsWith("application/pdf");
        assertThat(blob.path("size").asInt()).isEqualTo(PDF.length);
        assertThat(Base64.getDecoder().decode(blob.path("data").asText())).isEqualTo(PDF);
    }

    @Test
    void base64JsonFromTheVendorReachesTheCallerAsARawPdf() {
        Patchbay.ensurePublished("docs-get-raw");

        var r = Patchbay.call("GET", "docs-get-raw", Map.of("documentId", "DOC-1"));

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.header("Content-Type")).startsWith("application/pdf");
        assertThat(r.header("Content-Disposition")).contains("sample.pdf");
        assertThat(r.bytes()).isEqualTo(PDF);
    }

    // ---- refused before the vendor is called

    @Test
    void aJsonBlobWhoseDataIsNotBase64Is400() {
        Patchbay.ensurePublished("docs-upload-multipart");

        var r = Patchbay.send("POST", "docs-upload-multipart", Json.write(Map.of("file",
                Map.of("fileName", "x.pdf", "contentType", "application/pdf", "data", "not base64 !!!"))));

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.json().path("problems").toString()).contains("file");
        assertThat(Mock.total()).isZero();
    }

    @Test
    void aMissingRequiredBlobIs400() {
        Patchbay.ensurePublished("docs-upload-multipart");
        var form = Http.Multipart.of(Map.of("title", "no file"), "other", "x.txt", "text/plain", "x".getBytes());

        var r = Http.sendBytes("POST", Patchbay.endpoint("docs-upload-multipart"), form.contentType(), form.body(), Map.of());

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.json().path("problems").toString()).contains("file: required");
        assertThat(Mock.total()).isZero();
    }

    @Test
    void aBlobLargerThanTheLimitIs413() {
        Patchbay.ensurePublished("docs-replace-content");
        byte[] tooLarge = new byte[3 * 1024 * 1024];

        var r = Http.sendBytes("PUT", Patchbay.endpoint("docs-replace-content") + "?documentId=DOC-9", "application/octet-stream",
                tooLarge, Map.of());

        assertThat(r.status()).isEqualTo(413);
        assertThat(Mock.total()).isZero();
    }

    // ---- helpers

    static void assertJsonStored(Http.Response r, String documentId) {
        assertThat(r.header("Content-Type")).startsWith("application/json");
        assertThat(r.json().path("documentId").asText()).isEqualTo(documentId);
        assertThat(r.json().path("status").asText()).isEqualTo("STORED");
    }

    /** One multipart request with a title part and a file part carrying the PDF, its file name and type. */
    static void assertVendorGotMultipart(String title) {
        var sent = Mock.single("POST", "/docs/v1/documents");
        assertThat(sent.header("Content-Type")).startsWith("multipart/form-data");
        String text = new String(sent.bytes(), StandardCharsets.ISO_8859_1);
        assertThat(text).contains("name=\"title\"").contains(title)
                .contains("name=\"file\"; filename=\"sample.pdf\"").contains("Content-Type: application/pdf");
        assertThat(indexOf(sent.bytes(), PDF)).as("the PDF bytes inside the multipart body").isNotNegative();
    }

    static void assertVendorGotRaw(String documentId) {
        var sent = Mock.single("PUT", "/docs/v1/documents/" + documentId + "/content");
        assertThat(sent.header("Content-Type")).startsWith("application/pdf");
        assertThat(sent.header("Content-Disposition")).contains("sample.pdf");
        assertThat(sent.bytes()).isEqualTo(PDF);
    }

    static void assertVendorGotBase64Json() {
        var sent = Mock.single("POST", "/docs/v1/documents/base64").json();
        assertThat(sent.path("fileName").asText()).isEqualTo("sample.pdf");
        assertThat(sent.path("contentType").asText()).isEqualTo("application/pdf");
        assertThat(Base64.getDecoder().decode(sent.path("contentBase64").asText())).isEqualTo(PDF);
    }

    static byte[] read(String name) {
        try {
            return Files.readAllBytes(Path.of("mock/__files/docs", name));
        } catch (IOException e) {
            throw new IllegalStateException("Run java tools/GenerateFixtures.java first", e);
        }
    }

    static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
