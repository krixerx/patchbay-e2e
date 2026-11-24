package patchbaytest;

import static org.assertj.core.api.Assertions.assertThat;
import static patchbaytest.support.Json.assertJsonEquals;

import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import patchbaytest.support.Mock;
import patchbaytest.support.Patchbay;
import patchbaytest.support.PatchbayTest;

/**
 * Flat ↔ nested objects, lists of objects, lists inside lists and the list rules D-12, in both directions, against
 * the shop stand-in (mock/mappings/shop).
 */
class NestedMappingTest extends PatchbayTest {

    @BeforeAll
    static void publish() {
        Patchbay.ensurePublished("shop-order-create", "shop-order-get", "shop-order-status", "shop-order-cancel");
    }

    static final String ORDER = """
            {"orderRef":"R-1","customerFirstName":"Mari","customerLastName":"Tamm","email":"mari@example.com",
             "phone":"+372 5555 0001","vip":true,"country":"EE","currency":"EUR","orderDate":"2026-10-23","notes":null,
             "tags":["gift","rush"],
             "lines":[
               {"sku":"S1","name":"Shirt","qty":2,"price":19.9,
                "options":[{"code":"size","value":"M"},{"code":"color","value":"red"}]},
               {"sku":"S2","qty":1,"price":5,"options":[]}]}""";

    @Test
    void flatInputBecomesNestedObjectsAndListsAreMappedLevelByLevel() {
        Patchbay.send("POST", "shop-order-create", ORDER);

        // country and currency sit outside the list and are repeated in every item (rule 3); the item's sku goes
        // into every attribute of the nested list (rule 4); tags are copied as they are; null is passed on.
        assertJsonEquals(Mock.single("POST", "/shop/v1/orders").json(), """
                {"externalRef":"R-1",
                 "customer":{"name":{"first":"Mari","last":"Tamm"},
                             "contact":{"email":"mari@example.com","phone":"+372 5555 0001"},"vip":true},
                 "currency":"EUR","orderDate":"2026-10-23","comment":null,"channel":"patchbay-test",
                 "labels":["gift","rush"],
                 "items":[
                   {"product":{"sku":"S1","name":"Shirt"},"quantity":2,"unitPrice":19.9,"shipCountry":"EE","currency":"EUR",
                    "attributes":[{"key":"size","val":"M","sku":"S1"},{"key":"color","val":"red","sku":"S1"}]},
                   {"product":{"sku":"S2"},"quantity":1,"unitPrice":5,"shipCountry":"EE","currency":"EUR",
                    "attributes":[]}]}""");
    }

    @Test
    void nestedResponseIsFlattenedBackWithListsInsideLists() {
        var r = Patchbay.send("POST", "shop-order-create", ORDER);

        assertThat(r.status()).isEqualTo(200);
        assertJsonEquals(r.json(), """
                {"orderId":"ORD-1001","status":"CREATED","customerEmail":"mari@example.com","vip":true,
                 "channel":"patchbay-test","notes":null,"labels":["gift","rush"],
                 "lines":[
                   {"sku":"S1","qty":2,"price":19.9,"shipCountry":"EE",
                    "attributes":[{"code":"size","value":"M","sku":"S1"},{"code":"color","value":"red","sku":"S1"}]},
                   {"sku":"S2","qty":1,"price":5,"shipCountry":"EE","attributes":[]}]}""");
    }

    @Test
    void missingOptionalPartsAreLeftOutAtEveryLevel() {
        Patchbay.send("POST", "shop-order-create", """
                {"orderRef":"R-2","email":"x@example.com","lines":[{"sku":"S9"}]}""");

        assertJsonEquals(Mock.single("POST", "/shop/v1/orders").json(), """
                {"externalRef":"R-2","customer":{"contact":{"email":"x@example.com"}},"channel":"patchbay-test",
                 "items":[{"product":{"sku":"S9"}}]}""");
    }

    @Test
    void aRequiredFieldMissingInsideAListItemIsReportedWithItsIndex() {
        var r = Patchbay.send("POST", "shop-order-create", """
                {"orderRef":"R-3","email":"x@example.com","lines":[{"sku":"ok"},{"qty":1},{"name":"no sku"}]}""");

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.json().path("problems").toString()).contains("lines[1].sku: required").contains("lines[2].sku: required");
        assertThat(Mock.total()).isZero();
    }

    @Test
    void wrongTypesAreAllReportedAtOnceAlsoInsideLists() {
        var r = Patchbay.send("POST", "shop-order-create", """
                {"orderRef":5,"email":"x","vip":"yes","orderDate":"23.10.2026","tags":"not-a-list",
                 "lines":[{"sku":"a","qty":"two","price":"x","options":[{"code":1}]}]}""");

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.json().path("problems").toString())
                .contains("orderRef").contains("vip").contains("orderDate").contains("tags")
                .contains("lines[0].qty").contains("lines[0].price").contains("lines[0].options[0].code");
    }

    @Test
    void deeplyNestedResponseIsReshapedIntoAnotherNestedShape() {
        var r = Patchbay.call("GET", "shop-order-get", Map.of("orderId", "ORD-1001"));

        assertThat(r.status()).isEqualTo(200);
        assertJsonEquals(r.json(), """
                {"orderId":"ORD-1001","createdAt":"2026-10-01T10:15:30Z","deliveryDate":"2026-10-05","paid":true,
                 "total":55.55,"currency":"EUR",
                 "customer":{"id":501,"firstName":"Mari","lastName":"Tamm","email":"mari.tamm@example.com",
                   "phones":[{"type":"mobile","number":"+372 5555 0001"},{"type":"work","number":"+372 600 0002"}]},
                 "lines":[
                   {"lineNo":1,"sku":"SKU-RED-M","productName":"T-shirt red M","quantity":2,"unitPrice":19.9,
                    "discounts":[{"code":"AUTUMN","amount":2.0},{"code":"VIP","amount":1.5}]},
                   {"lineNo":2,"sku":"SKU-MUG","productName":"Mug","quantity":1,"unitPrice":8.5,"discounts":[]}],
                 "shipments":[
                   {"id":"SHP-1","carrier":"Omniva",
                    "parcels":[{"trackingNo":"TRK-1","weightKg":0.4},{"trackingNo":"TRK-2","weightKg":0.6}]},
                   {"id":"SHP-2","carrier":"DPD","parcels":[{"trackingNo":"TRK-3","weightKg":1.25}]}]}""");
    }

    @Test
    void emptyListsNullsAndMissingPartsOfAResponseKeepTheirMeaning() {
        var r = Patchbay.call("GET", "shop-order-get", Map.of("orderId", "ORD-EMPTY"));

        // lines: [] stays an empty list, last: null stays null, deliveryDate and shipments are absent and left out.
        assertJsonEquals(r.json(), """
                {"orderId":"ORD-EMPTY","createdAt":"2026-10-02T08:00:00Z","paid":false,"total":0,"currency":"EUR",
                 "customer":{"id":502,"firstName":"Jaan","lastName":null,"email":"jaan@example.com","phones":[]},
                 "lines":[]}""");
    }

    @Test
    void putFillsThePathTheBodyAndAnAddedHeaderFromOneInput() {
        var r = Patchbay.send("PUT", "shop-order-status", """
                {"orderId":"ORD-1001","status":"SHIPPED","notifyCustomer":false,"correlationId":"corr-42"}""");

        assertThat(r.status()).isEqualTo(200);
        assertJsonEquals(r.json(), """
                {"orderId":"ORD-1001","status":"SHIPPED","notifyCustomer":false}""");
        var sent = Mock.single("PUT", "/shop/v1/orders/ORD-1001/status");
        assertJsonEquals(sent.json(), """
                {"status":"SHIPPED","notify":false}""");
        assertThat(sent.header("X-Correlation-Id")).isEqualTo("corr-42");
        assertThat(sent.header("X-Client-Id")).isEqualTo("patchbay-tests");
    }

    @Test
    void deleteSendsPathAndQueryAndMapsTheNestedResponse() {
        var r = Patchbay.call("DELETE", "shop-order-cancel", Map.of("orderId", "ORD-1001", "reason", "changed mind"));

        assertThat(r.status()).isEqualTo(200);
        assertJsonEquals(r.json(), """
                {"orderId":"ORD-1001","cancelled":true,"reason":"changed mind","refundAmount":49.95,"refundCurrency":"EUR"}""");
        assertThat(Mock.single("DELETE", "/shop/v1/orders/ORD-1001").url()).matches(".*\\?reason=changed(%20|\\+)mind");
    }

    /** An object linked as a whole goes out as it came in and comes back the same way (was an NPE at publish). */
    @Test
    void anObjectLinkedAsAWholeIsCopiedInBothDirections() {
        Patchbay.ensurePublished("shop-order-whole");
        String customer = """
                {"name":{"first":"Mari","last":"Tamm"},"contact":{"email":"mari@example.com"},"vip":true}""";

        var r = Patchbay.send("POST", "shop-order-whole", "{\"orderRef\":\"R-9\",\"customer\":" + customer + "}");

        assertThat(r.status()).as("%s", r).isEqualTo(200);
        assertJsonEquals(Mock.single("POST", "/shop/v1/orders").json(), "{\"externalRef\":\"R-9\",\"customer\":" + customer + "}");
        assertJsonEquals(r.json(), "{\"orderId\":\"ORD-1001\",\"customer\":" + customer + "}");
    }
}
