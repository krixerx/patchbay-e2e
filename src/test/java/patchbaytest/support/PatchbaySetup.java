package patchbaytest.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Configures an empty Patchbay the way a user would, once per test run:
 * <ol>
 * <li>imports every swagger file from the mock container ({@code mock/__files/openapi}) by URL, and every WSDL
 * listed under {@code wsdlContracts} ({@code mock/__files/wsdl}, D-30), whose XSD imports Patchbay fetches too,</li>
 * <li>sets each connection's base URL and headers ({@code patchbay/environment.json}),</li>
 * <li>imports the packages generated from {@code patchbay/package} and {@code patchbay/docs-package} (D-28),</li>
 * <li>publishes every integration in them.</li>
 * </ol>
 * A failing step is recorded, not thrown, so only the tests that need the broken part fail ({@link #require}).
 * The generated packages are also written to {@code target/} for importing by hand.
 */
public final class PatchbaySetup {

    /** Where Patchbay reaches the mock, inside the compose network. */
    public static final String MOCK_FROM_PATCHBAY = System.getProperty("mock.internal.url", "http://mock:8080");

    public static final Path ROOT = Path.of("patchbay");

    public record Step(String what, Http.Response response) {
        public boolean ok() {
            return response.status() == 200;
        }
    }

    /** Every step in order: contracts, connections, package imports, publishes. */
    public static final Map<String, Step> STEPS = new LinkedHashMap<>();

    private static boolean done;

    private PatchbaySetup() {
    }

    public static synchronized void run() {
        if (done) {
            return;
        }
        done = true;
        JsonNode env = Json.parse(read(ROOT.resolve("environment.json")));
        for (JsonNode c : env.path("contracts")) {
            String id = c.asText();
            String body = Json.write(Map.of("name", id, "url", MOCK_FROM_PATCHBAY + "/openapi/" + id + ".yaml"));
            STEPS.put("contract " + id, new Step("import swagger " + id,
                    Http.send("POST", Patchbay.URL + "/admin/api/contracts", "application/json", body, Map.of())));
        }
        for (JsonNode c : env.path("wsdlContracts")) {
            String id = c.asText();
            String body = Json.write(Map.of("name", id, "url", MOCK_FROM_PATCHBAY + "/wsdl/" + id + ".wsdl"));
            STEPS.put("contract " + id, new Step("import WSDL " + id,
                    Http.send("POST", Patchbay.URL + "/admin/api/contracts", "application/json", body, Map.of())));
        }
        env.path("connections").properties().forEach(e -> STEPS.put("connection " + e.getKey(), new Step("set connection " + e.getKey(),
                Http.send("PUT", Patchbay.URL + "/admin/api/connections/" + e.getKey(), "application/json", e.getValue().toString(),
                        Map.of()))));
        List<String> notPublished = new ArrayList<>();
        env.path("notPublished").forEach(n -> notPublished.add(n.asText()));
        for (String pkg : List.of("package", "docs-package")) {
            Path dir = ROOT.resolve(pkg);
            byte[] zip = buildPackage(dir);
            write(Path.of("target", "patchbay-" + pkg + ".zip"), zip);
            Step imported = new Step("import " + pkg,
                    Http.sendBytes("POST", Patchbay.URL + "/admin/api/import", "application/zip", zip, Map.of()));
            STEPS.put("import " + pkg, imported);
            for (String slug : slugs(dir.resolve("services"))) {
                if (!notPublished.contains(slug)) {
                    STEPS.put("publish " + slug, imported.ok() ? new Step("publish " + slug, Patchbay.publish(slug)) : imported);
                }
            }
        }
    }

    /** Fails the calling test with the reason when the setup step it depends on failed. */
    public static void require(String stepKey) {
        run();
        Step s = STEPS.get(stepKey);
        if (s == null) {
            throw new IllegalStateException("No setup step " + stepKey + "; known: " + STEPS.keySet());
        }
        if (!s.ok()) {
            throw new IllegalStateException("Setup step '" + s.what() + "' failed: " + s.response());
        }
    }

    /** A package ZIP: manifest.json plus entities/ and services/ of {@code dir}, like a Patchbay export. */
    public static byte[] buildPackage(Path dir) {
        List<Map<String, String>> listed = slugs(dir.resolve("services")).stream().map(s -> Map.of("id", s)).toList();
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", "patchbay-package");
        manifest.put("formatVersion", 1);
        manifest.put("exportedAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
        manifest.put("integrations", listed);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            put(zip, "manifest.json", Json.write(manifest).getBytes());
            for (String folder : List.of("entities", "services")) {
                for (Path f : jsonFiles(dir.resolve(folder))) {
                    put(zip, folder + "/" + f.getFileName(), Files.readAllBytes(f));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    public static List<String> slugs(Path servicesDir) {
        return jsonFiles(servicesDir).stream().map(p -> p.getFileName().toString().replaceFirst("\\.json$", "")).toList();
    }

    private static List<Path> jsonFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void put(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path p, byte[] content) {
        try {
            Files.createDirectories(p.getParent());
            Files.write(p, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
