package patchbaytest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;

import patchbaytest.support.Http;
import patchbaytest.support.Json;
import patchbaytest.support.Mock;
import patchbaytest.support.Patchbay;
import patchbaytest.support.PatchbayTest;

/**
 * Binary documents in and out (mock/mappings/docs). Base64 inside JSON works with today's text fields. Raw bytes
 * and multipart need features Patchbay does not have yet; their integrations (patchbay/docs-package) use a proposed
 * configuration: a {@code binary} field type, {@code formats.request} {@code binary} or {@code multipart},
 * {@code formats.output} {@code binary}, and {@code $body} for the whole body in a link. Callers send raw bytes as the
 * request body with the other Input fields as query parameters, or multipart/form-data with one part per field.
 */
class DocumentsTest extends PatchbayTest {

    static final byte[] PDF = read("sample.pdf");
    static final byte[] LARGE = read("large.bin");

    @Test
    void aDocumentReadAsBase64InJsonKeepsEveryByte() {
        Patchbay.ensurePublished("docs-get-base64");

        var r = Patchbay.call("GET", "docs-get-base64", Map.of("documentId", "DOC-1"));

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().path("fileName").asText()).isEqualTo("sample.pdf");
        assertThat(r.json().path("size").asInt()).isEqualTo(PDF.length);
        assertThat(Base64.getDecoder().decode(r.json().path("content").asText())).isEqualTo(PDF);
    }

    @Test
    void aOneMegabyteDocumentSentAsBase64InJsonArrivesUnchanged() {
        Patchbay.ensurePublished("docs-upload-base64");
        String base64 = Base64.getEncoder().encodeToString(LARGE);

        var r = Patchbay.send("POST", "docs-upload-base64", Json.write(Map.of("fileName", "large.bin",
                "contentType", "application/octet-stream", "content", base64)));

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().path("documentId").asText()).isEqualTo("DOC-3");
        var sent = Mock.single("POST", "/docs/v1/documents/base64").json();
        assertThat(Base64.getDecoder().decode(sent.path("contentBase64").asText())).isEqualTo(LARGE);
    }

    @Test
    void aMultipartUploadIsPassedOnAsMultipartWithTheFileUnchanged() {
        Patchbay.ensurePublished("docs-upload-multipart");
        var form = Http.Multipart.of(Map.of("title", "Contract"), "file", "sample.pdf", "application/pdf", PDF);

        var r = Http.sendBytes("POST", Patchbay.endpoint("docs-upload-multipart"), form.contentType(), form.body(), Map.of());

        assertThat(r.status()).as("%s", r).isEqualTo(200);
        assertThat(r.json().path("documentId").asText()).isEqualTo("DOC-2");
        assertThat(r.json().path("title").asText()).isEqualTo("Contract");
        var sent = Mock.single("POST", "/docs/v1/documents");
        assertThat(sent.header("Content-Type")).startsWith("multipart/form-data");
        assertThat(indexOf(sent.bytes(), PDF)).as("the PDF bytes inside the multipart body").isNotNegative();
    }

    @Test
    void rawBytesAreSentAsTheBodyWithTheirContentType() {
        Patchbay.ensurePublished("docs-replace-content");

        var r = Http.sendBytes("PUT", Patchbay.endpoint("docs-replace-content") + "?documentId=DOC-9", "application/pdf", PDF,
                Map.of());

        assertThat(r.status()).as("%s", r).isEqualTo(200);
        assertThat(r.json().path("documentId").asText()).isEqualTo("DOC-9");
        var sent = Mock.single("PUT", "/docs/v1/documents/DOC-9/content");
        assertThat(sent.header("Content-Type")).startsWith("application/pdf");
        assertThat(sent.bytes()).isEqualTo(PDF);
    }

    @Test
    void aDownloadedPdfReachesTheCallerByteForByteWithItsTypeAndFileName() {
        Patchbay.ensurePublished("docs-download");

        var r = Patchbay.call("GET", "docs-download", Map.of("documentId", "DOC-1"));

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.header("Content-Type")).startsWith("application/pdf");
        assertThat(r.header("Content-Disposition")).contains("sample.pdf");
        assertThat(r.bytes()).isEqualTo(PDF);
    }

    @Test
    void aOneMegabyteBinaryDownloadIsComplete() {
        Patchbay.ensurePublished("docs-download");

        var r = Patchbay.call("GET", "docs-download", Map.of("documentId", "DOC-LARGE"));

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.header("Content-Type")).startsWith("application/octet-stream");
        assertThat(r.bytes()).isEqualTo(LARGE);
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
