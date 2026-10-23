package patchbaytest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import patchbaytest.support.Json;
import patchbaytest.support.Mock;
import patchbaytest.support.Patchbay;
import patchbaytest.support.PatchbayTest;

/**
 * Big lists and wide objects. The catalog file and the wide integration are written by tools/GenerateFixtures.java;
 * the formulas here must match it.
 */
class LargePayloadTest extends PatchbayTest {

    static final int IMPORT_PRODUCTS = 5_000;
    static final int CATALOG_PRODUCTS = 10_000;
    static final int WIDE_FIELDS = 120;
    static final Duration LIMIT = Duration.ofSeconds(15);

    @BeforeAll
    static void publish() {
        Patchbay.ensurePublished("shop-catalog-import", "shop-catalog-list", "shop-customer-wide");
    }

    @Test
    void aRequestWithThousandsOfNestedListItemsIsMappedCompletelyBothWays() {
        List<Map<String, Object>> products = new ArrayList<>();
        for (int i = 1; i <= IMPORT_PRODUCTS; i++) {
            products.add(Map.of("sku", "S" + i, "name", "Name " + i, "price", i * 0.5, "stock", i % 7, "active", i % 2 == 0,
                    "variants", List.of(
                            Map.of("sku", "S" + i + "-1", "color", "red", "size", "S"),
                            Map.of("sku", "S" + i + "-2", "color", "blue", "size", "M"),
                            Map.of("sku", "S" + i + "-3", "color", "green", "size", "L"))));
        }
        String body = Json.write(Map.of("supplier", "ACME", "products", products));

        long start = System.nanoTime();
        var r = Patchbay.send("POST", "shop-catalog-import", body);
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(r.status()).as("%s", r).isEqualTo(200);
        assertThat(took).as("a %d kB request", body.length() / 1024).isLessThan(LIMIT);

        JsonNode sent = Mock.single("POST", "/shop/v1/catalog/import").json();
        assertThat(sent.path("supplierCode").asText()).isEqualTo("ACME");
        assertThat(sent.path("items")).hasSize(IMPORT_PRODUCTS);
        for (int i = 1; i <= IMPORT_PRODUCTS; i++) {
            JsonNode item = sent.path("items").get(i - 1);
            assertThat(item.path("code").asText()).isEqualTo("S" + i);
            assertThat(item.path("qty").asInt()).isEqualTo(i % 7);
            assertThat(item.path("supplier").asText()).isEqualTo("ACME");
            assertThat(item.path("options")).hasSize(3);
            assertThat(item.path("options").get(2).path("colour").asText()).isEqualTo("green");
        }

        JsonNode out = r.json();
        assertThat(out.path("importId").asText()).isEqualTo("IMP-1");
        assertThat(out.path("items")).hasSize(IMPORT_PRODUCTS);
        JsonNode last = out.path("items").get(IMPORT_PRODUCTS - 1);
        Json.assertJsonEquals(last, """
                {"sku":"S%d","title":"Name %d","price":%s,"stock":%d,"active":%b,"supplier":"ACME",
                 "variants":[{"sku":"S%d-1","color":"red","size":"S"},{"sku":"S%d-2","color":"blue","size":"M"},
                             {"sku":"S%d-3","color":"green","size":"L"}]}"""
                .formatted(IMPORT_PRODUCTS, IMPORT_PRODUCTS, IMPORT_PRODUCTS * 0.5, IMPORT_PRODUCTS % 7, IMPORT_PRODUCTS % 2 == 0,
                        IMPORT_PRODUCTS, IMPORT_PRODUCTS, IMPORT_PRODUCTS));
    }

    @Test
    void aResponseWithTenThousandProductsIsMappedCompletely() {
        long start = System.nanoTime();
        var r = Patchbay.call("GET", "shop-catalog-list", Map.of("category", "tools"));
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(r.status()).isEqualTo(200);
        assertThat(took).isLessThan(LIMIT);
        JsonNode out = r.json();
        assertThat(out.path("total").asInt()).isEqualTo(CATALOG_PRODUCTS);
        assertThat(out.path("products")).hasSize(CATALOG_PRODUCTS);
        for (int i = 1; i <= CATALOG_PRODUCTS; i++) {
            JsonNode p = out.path("products").get(i - 1);
            String sku = "P%05d".formatted(i);
            assertThat(p.path("sku").asText()).isEqualTo(sku);
            assertThat(p.path("price").decimalValue()).isEqualByComparingTo(BigDecimal.valueOf(i * 1.25));
            assertThat(p.path("available").asInt()).isEqualTo(i % 50);
            assertThat(p.path("variants").get(1).path("sku").asText()).isEqualTo(sku + "-B");
            assertThat(p.path("variants").get(1).path("color").asText()).isEqualTo("blue");
            assertThat(p.has("category")).as("unlinked fields stay out").isFalse();
        }
        assertThat(Mock.single("GET", "/shop/v1/catalog").url()).endsWith("?category=tools");
    }

    @Test
    void anObjectWithOneHundredTwentyFieldsOfEveryTypeGoesIntoNestedGroupsAndBack() {
        Map<String, Object> input = new LinkedHashMap<>();
        for (int j = 1; j <= WIDE_FIELDS; j++) {
            input.put("f%03d".formatted(j), wideValue(j));
        }

        var r = Patchbay.send("POST", "shop-customer-wide", Json.write(input));

        assertThat(r.status()).as("%s", r).isEqualTo(200);
        JsonNode sent = Mock.single("POST", "/shop/v1/customers").json();
        assertThat(sent.size()).isEqualTo(WIDE_FIELDS / 20);
        JsonNode out = r.json();
        assertThat(out.size()).isEqualTo(WIDE_FIELDS);
        for (int j = 1; j <= WIDE_FIELDS; j++) {
            String expected = Json.write(wideValue(j));
            JsonNode wire = sent.path("group%02d".formatted((j - 1) / 20 + 1)).path("attr%03d".formatted(j));
            Json.assertJsonEquals(wire, expected);
            Json.assertJsonEquals(out.path("f%03d".formatted(j)), expected);
        }
    }

    /** Value of wide field j; its type is {"text","integer","number","boolean","date"}[j % 5] (GenerateFixtures). */
    static Object wideValue(int j) {
        return switch (j % 5) {
            case 0 -> "value " + j;
            case 1 -> j;
            case 2 -> j + 0.25;
            case 3 -> j % 2 == 0;
            default -> "2026-01-%02d".formatted(j % 28 + 1);
        };
    }
}
