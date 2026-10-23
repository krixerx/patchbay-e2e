package patchbaytest;

import static org.assertj.core.api.Assertions.assertThat;
import static patchbaytest.support.Json.assertJsonEquals;

import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import patchbaytest.support.Mock;
import patchbaytest.support.Patchbay;
import patchbaytest.support.PatchbayTest;

/** XML to and from the external system (D-24): JSON callers, XML vendor, and XML back to callers. */
class XmlTest extends PatchbayTest {

    @BeforeAll
    static void publish() {
        Patchbay.ensurePublished("shop-xml-order-create", "shop-xml-order-get");
    }

    @Test
    void jsonInputIsSentAsXmlWithRepeatedElementsForAList() {
        Patchbay.send("POST", "shop-xml-order-create", """
                {"orderRef":"R-X1","customer":"Mari","lines":[{"sku":"A","qty":1},{"sku":"B","qty":2}]}""");

        var sent = Mock.single("POST", "/shop/v1/xml/orders");
        assertThat(sent.header("Content-Type")).contains("xml");
        assertThat(sent.body()).startsWith("<Order>").endsWith("</Order>")
                .contains("<ref>R-X1</ref>").contains("<customer>Mari</customer>")
                .contains("<line><sku>A</sku><qty>1</qty></line><line><sku>B</sku><qty>2</qty></line>");
    }

    @Test
    void anXmlAnswerIsReturnedAsXmlUnderTheOutputRoot() {
        var r = Patchbay.send("POST", "shop-xml-order-create", """
                {"orderRef":"R-X2","lines":[{"sku":"A","qty":1}]}""");

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.header("Content-Type")).contains("application/xml");
        assertThat(r.body()).startsWith("<confirmation>").endsWith("</confirmation>")
                .contains("<orderId>ORD-2001</orderId>").contains("<status>ACCEPTED</status>")
                .contains("<line><sku>XML-A</sku><qty>1</qty></line><line><sku>XML-B</sku><qty>3</qty></line>");
    }

    @Test
    void xmlAttributesAndElementsBecomeTypedJsonFields() {
        var r = Patchbay.call("GET", "shop-xml-order-get", Map.of("orderId", "ORD-3001"));

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.header("Content-Type")).contains("application/json");
        assertJsonEquals(r.json(), """
                {"orderId":"ORD-3001","currency":"EUR","customerName":"Mari Tamm","customerEmail":"mari.tamm@example.com",
                 "paid":true,
                 "lines":[{"lineNo":1,"sku":"XML-A","qty":2,"price":10.5},{"lineNo":2,"sku":"XML-B","qty":1,"price":4.25}]}""");
    }

    @Test
    void aSingleRepeatedXmlElementStillBecomesAList() {
        var r = Patchbay.call("GET", "shop-xml-order-get", Map.of("orderId", "ORD-3002"));

        assertJsonEquals(r.json().path("lines"), """
                [{"lineNo":1,"sku":"XML-ONLY","qty":5,"price":1.0}]""");
        assertThat(r.json().path("paid").isBoolean()).isTrue();
    }
}
