package patchbaytest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import patchbaytest.support.Patchbay;
import patchbaytest.support.PatchbaySetup;
import patchbaytest.support.PatchbayTest;

/**
 * The setup itself, one test per step, so a failing import or publish shows up by name: the swagger files import
 * completely, the connections are saved, both packages import, and every integration publishes.
 */
class SetupTest extends PatchbayTest {

    @ParameterizedTest
    @CsvSource({"fake-rest-api, 5", "gorest, 4", "shop, 9", "edge, 11", "unreachable, 1", "docs, 5"})
    void theSwaggerFileImportsWithEveryOperation(String contract, int operations) {
        PatchbaySetup.require("contract " + contract);

        JsonNode c = Patchbay.admin("GET", "/admin/api/contracts/" + contract).json();
        assertThat(c.path("operations")).hasSize(operations);
    }

    @ParameterizedTest
    @ValueSource(strings = {"fake-rest-api", "gorest", "shop", "edge", "unreachable", "docs"})
    void theSwaggerFileImportsWithoutWarnings(String contract) {
        PatchbaySetup.require("contract " + contract);

        JsonNode c = Patchbay.admin("GET", "/admin/api/contracts/" + contract).json();
        assertThat(c.path("warnings").isEmpty()).as("warnings: %s", c.path("warnings")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"fake-rest-api", "gorest", "shop", "edge", "unreachable", "docs"})
    void theConnectionIsSaved(String connection) {
        PatchbaySetup.require("connection " + connection);
    }

    @ParameterizedTest
    @ValueSource(strings = {"package", "docs-package"})
    void thePackageImports(String pkg) {
        PatchbaySetup.require("import " + pkg);
    }

    @ParameterizedTest
    @MethodSource("publishedSlugs")
    void theIntegrationPublishes(String slug) {
        PatchbaySetup.require("publish " + slug);
    }

    /** Each draft calls exactly the method and path of its operation in the imported swagger file. */
    @ParameterizedTest
    @MethodSource("allSlugs")
    void theDraftMatchesItsSwaggerOperation(String slug) {
        PatchbaySetup.run();
        JsonNode draft = Patchbay.admin("GET", "/admin/api/integrations/" + slug).json();
        JsonNode contract = Patchbay.admin("GET", "/admin/api/contracts/" + draft.path("contract").asText()).json();
        JsonNode op = null;
        for (JsonNode o : contract.path("operations")) {
            if (o.path("id").asText().equals(draft.path("operationId").asText())) {
                op = o;
            }
        }

        assertThat(op).as("operation %s in contract %s", draft.path("operationId"), draft.path("contract")).isNotNull();
        assertThat(op.path("method").asText()).isEqualTo(draft.path("target").path("method").asText());
        assertThat(op.path("path").asText()).isEqualTo(draft.path("target").path("path").asText());
    }

    static Stream<String> allSlugs() {
        return Stream.of("package", "docs-package")
                .flatMap(p -> PatchbaySetup.slugs(PatchbaySetup.ROOT.resolve(p).resolve("services")).stream());
    }

    static Stream<String> publishedSlugs() {
        PatchbaySetup.run();
        return PatchbaySetup.STEPS.keySet().stream().filter(k -> k.startsWith("publish ")).map(k -> k.substring(8));
    }
}
