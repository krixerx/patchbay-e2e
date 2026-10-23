import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the fixtures that are too big to keep by hand. Run from the project root after changing a formula:
 * {@code java tools/GenerateFixtures.java}. The tests compute their expectations with the same formulas
 * (LargePayloadTest), so change both together.
 */
public class GenerateFixtures {

    static final int CATALOG_PRODUCTS = 10_000;
    static final int WIDE_FIELDS = 120;
    static final int WIDE_GROUP_SIZE = 20;
    static final String[] WIDE_TYPES = {"text", "integer", "number", "boolean", "date"};

    public static void main(String[] args) throws IOException {
        catalog(Path.of("mock/__files/shop/catalog-large.json"));
        wide(Path.of("patchbay/package"));
        documents(Path.of("mock/__files/docs"));
    }

    /** A tiny PDF with non-ASCII bytes, the same PDF as base64 inside JSON, and 1 MiB of seeded random bytes. */
    static void documents(Path dir) throws IOException {
        Files.createDirectories(dir);
        byte[] pdf = ("%PDF-1.4\n%âãÏÓ\n"
                + "1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n"
                + "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n"
                + "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 100]>>endobj\n"
                + "trailer<</Root 1 0 R>>\n%%EOF\n").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        Files.write(dir.resolve("sample.pdf"), pdf);
        Files.writeString(dir.resolve("document-DOC-1.json"), """
                {"documentId":"DOC-1","fileName":"sample.pdf","contentType":"application/pdf","size":%d,"contentBase64":"%s"}
                """.formatted(pdf.length, java.util.Base64.getEncoder().encodeToString(pdf)));
        byte[] large = new byte[1 << 20];
        new java.util.Random(42).nextBytes(large);
        Files.write(dir.resolve("large.bin"), large);
    }

    /** Product i (1-based): sku P00001, price i * 1.25 EUR, i % 50 in stock, a red M and a blue L variant. */
    static void catalog(Path file) throws IOException {
        StringBuilder sb = new StringBuilder(2_500_000);
        sb.append("{\"page\":1,\"total\":").append(CATALOG_PRODUCTS).append(",\"products\":[\n");
        for (int i = 1; i <= CATALOG_PRODUCTS; i++) {
            String sku = "P%05d".formatted(i);
            sb.append("{\"sku\":\"").append(sku).append("\",\"name\":\"Product ").append(i)
                    .append("\",\"category\":\"tools\",\"pricing\":{\"amount\":").append(i * 1.25)
                    .append(",\"currency\":\"EUR\"},\"stock\":{\"available\":").append(i % 50)
                    .append("},\"variants\":[{\"sku\":\"").append(sku).append("-R\",\"attrs\":{\"color\":\"red\",\"size\":\"M\"}},")
                    .append("{\"sku\":\"").append(sku).append("-B\",\"attrs\":{\"color\":\"blue\",\"size\":\"L\"}}]}")
                    .append(i < CATALOG_PRODUCTS ? ",\n" : "\n");
        }
        Files.writeString(file, sb.append("]}\n").toString());
    }

    /**
     * Field j (1-based) of the wide customer: name f001, type WIDE_TYPES[j % 5]; sent to the shop as
     * body.group01.attr001 (20 fields per group) and read back from received.group01.attr001.
     */
    static void wide(Path config) throws IOException {
        List<String> in = new ArrayList<>();
        List<String> out = new ArrayList<>();
        List<String> req = new ArrayList<>();
        List<String> res = new ArrayList<>();
        for (int j = 1; j <= WIDE_FIELDS; j++) {
            String type = WIDE_TYPES[j % WIDE_TYPES.length];
            String wire = "group%02d.attr%03d".formatted((j - 1) / WIDE_GROUP_SIZE + 1, j);
            in.add("    { \"id\": \"wc-f%03d\", \"name\": \"f%03d\", \"type\": \"%s\"%s }"
                    .formatted(j, j, type, j == 1 ? ", \"required\": true" : ""));
            out.add("    { \"id\": \"wr-f%03d\", \"name\": \"f%03d\", \"type\": \"%s\" }".formatted(j, j, type));
            req.add("    { \"from\": \"wc-f%03d\", \"to\": \"body.%s\" }".formatted(j, wire));
            res.add("    { \"from\": \"received.%s\", \"to\": \"wr-f%03d\" }".formatted(wire, j));
        }
        Files.writeString(config.resolve("entities/wide-customer.json"), """
                {
                  "id": "wide-customer",
                  "name": "Wide customer (%d fields)",
                  "fields": [
                %s
                  ]
                }
                """.formatted(WIDE_FIELDS, String.join(",\n", in)));
        Files.writeString(config.resolve("entities/wide-customer-result.json"), """
                {
                  "id": "wide-customer-result",
                  "name": "Wide customer result (%d fields)",
                  "fields": [
                %s
                  ]
                }
                """.formatted(WIDE_FIELDS, String.join(",\n", out)));
        Files.writeString(config.resolve("services/shop-customer-wide.json"), """
                {
                  "id": "shop-customer-wide",
                  "contract": "shop",
                  "operationId": "createCustomer",
                  "input": "wide-customer",
                  "output": "wide-customer-result",
                  "target": { "connection": "shop", "method": "POST", "path": "/shop/v1/customers" },
                  "request": { "links": [
                %s
                  ] },
                  "response": { "links": [
                %s
                  ] }
                }
                """.formatted(String.join(",\n", req), String.join(",\n", res)));
    }
}
