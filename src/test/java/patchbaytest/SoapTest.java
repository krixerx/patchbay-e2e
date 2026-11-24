package patchbaytest;

import static org.assertj.core.api.Assertions.assertThat;
import static patchbaytest.support.Json.assertJsonEquals;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import patchbaytest.support.Http;
import patchbaytest.support.Mock;
import patchbaytest.support.Patchbay;
import patchbaytest.support.PatchbaySetup;
import patchbaytest.support.PatchbayTest;

/**
 * SOAP services from a WSDL contract (Patchbay D-30). Callers POST JSON to an ordinary REST endpoint; Patchbay sends a
 * SOAP 1.1 or 1.2 envelope to the person registry ({@code mock/__files/wsdl/registry.wsdl}, stubs in
 * {@code mock/mappings/registry}) and maps the answer, or a SOAP Fault, back to JSON.
 */
class SoapTest extends PatchbayTest {

    static final String SOAP11 = "http://schemas.xmlsoap.org/soap/envelope/";
    static final String SOAP12 = "http://www.w3.org/2003/05/soap-envelope";

    @BeforeAll
    static void publish() {
        Patchbay.ensurePublished("registry-get-person", "registry-get-person12", "registry-register");
    }

    @Test
    void theWsdlAndTheSchemaItImportsAreBothRead() {
        PatchbaySetup.require("contract registry");
        JsonNode c = Patchbay.admin("GET", "/admin/api/contracts/registry").json();

        assertThat(c.path("sourceKind").asText()).isEqualTo("wsdl");
        assertThat(c.path("files").toString()).contains("registry.wsdl").contains("common.xsd");
        JsonNode get = null;
        for (JsonNode op : c.path("operations")) {
            if (op.path("id").asText().equals("RegistrySoap/GetPerson")) {
                get = op;
            }
        }
        assertThat(get).isNotNull();
        assertThat(get.path("protocol").asText()).isEqualTo("soap");
        // The address type comes from the imported XSD, so its fields are known, not shown as text.
        assertThat(get.path("response").toString()).contains("\"street\"").contains("\"city\"");
    }

    @Test
    void soap11SendsANamespacedEnvelopeWithSoapActionInSequenceOrder() {
        var r = Patchbay.send("POST", "registry-get-person", """
                {"history":true,"personCode":"38001010000"}""");

        assertThat(r.status()).as(r.body()).isEqualTo(200);
        var sent = Mock.single("POST", "/soap/registry");
        // The HTTP client may write the charset as UTF-8; charset names are case-insensitive (RFC 9110).
        assertThat(sent.header("Content-Type")).isEqualToIgnoringCase("text/xml; charset=utf-8");
        assertThat(sent.header("SOAPAction")).isEqualTo("\"urn:GetPerson\"");
        assertThat(sent.body()).contains("<soap:Envelope xmlns:soap=\"" + SOAP11 + "\"")
                .contains("<ns1:GetPerson xmlns:ns1=\"urn:example:registry\">")
                // The links are in the other order; the XSD sequence decides.
                .contains("<ns1:personCode>38001010000</ns1:personCode><ns1:includeHistory>true</ns1:includeHistory>");
    }

    @Test
    void theSoapAnswerIsMappedToTypedJsonAndOnePhoneIsStillAList() {
        var r = Patchbay.send("POST", "registry-get-person", """
                {"personCode":"38001010000"}""");

        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(r.header("Content-Type")).contains("application/json");
        assertJsonEquals(r.json(), """
                {"name":"Mari","surname":"Tamm-38001010000","born":"1980-01-01","kids":2,"city":"Tallinn",
                 "phones":["+372 5550001"]}""");
        assertThat(Mock.single("POST", "/soap/registry").body()).doesNotContain("includeHistory");
    }

    @Test
    void soap12PutsTheActionInTheContentType() {
        var r = Patchbay.send("POST", "registry-get-person12", """
                {"personCode":"47506300000"}""");

        assertThat(r.status()).as(r.body()).isEqualTo(200);
        var sent = Mock.single("POST", "/soap/registry");
        assertThat(sent.header("Content-Type")).isEqualTo("application/soap+xml; charset=utf-8; action=\"urn:GetPerson\"");
        assertThat(sent.header("SOAPAction")).isNull();
        assertThat(sent.body()).contains("<soap:Envelope xmlns:soap=\"" + SOAP12 + "\"");
        assertJsonEquals(r.json(), """
                {"name":"Jaan","surname":"Kask-47506300000","born":"1975-06-30","kids":0,"city":"Tartu",
                 "phones":["+372 5550002","+372 5550003"]}""");
    }

    @Test
    void aSoap11FaultBecomesARemoteFaultProblemAndIsSentOnce() {
        var r = Patchbay.send("POST", "registry-get-person", """
                {"personCode":"00000000000"}""");

        assertThat(r.status()).isEqualTo(500);
        assertThat(r.header("X-Integration-Error")).isEqualTo("remote");
        assertThat(r.header("Content-Type")).contains("application/json");
        assertJsonEquals(r.json(), """
                {"error":"remote-fault","integration":"registry-get-person","detail":"Unknown person",
                 "fault":{"code":"soap:Client","reason":"Unknown person",
                          "detail":{"error":{"code":"PERSON_NOT_FOUND","field":"personCode"}}}}""");
        assertThat(Mock.received("POST", "/soap/registry")).hasSize(1);
    }

    @Test
    void aSoap12FaultKeepsItsStatusAndNamesCodeAndSubcode() {
        var r = Patchbay.send("POST", "registry-get-person12", """
                {"personCode":"bad"}""");

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.header("X-Integration-Error")).isEqualTo("remote");
        assertThat(r.json().path("error").asText()).isEqualTo("remote-fault");
        assertThat(r.json().path("fault").path("code").asText()).isEqualTo("env:Sender / r:InvalidCode");
        assertThat(r.json().path("fault").path("reason").asText()).isEqualTo("Personal code is not valid");
    }

    @Test
    void elementsOfTheImportedSchemaGetTheirNamespaceAndBasicAuthIsSent() {
        var r = Patchbay.send("POST", "registry-register", """
                {"firstName":"Mari","lastName":"Tamm","street":"Pikk 1","city":"Tallinn"}""");

        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertJsonEquals(r.json(), """
                {"registrationId":4711,"status":"REGISTERED"}""");
        var sent = Mock.single("POST", "/soap/registry");
        assertThat(sent.header("Authorization")).isEqualTo("Basic cmVnaXN0cnktdXNlcjpzM2NyZXQ=");
        assertThat(sent.header("SOAPAction")).isEqualTo("\"urn:RegisterPerson\"");
        assertThat(sent.body())
                .contains("<ns1:RegisterPerson xmlns:ns1=\"urn:example:registry\" xmlns:ns2=\"urn:example:common\">")
                .contains("<ns1:firstName>Mari</ns1:firstName><ns1:lastName>Tamm</ns1:lastName>"
                        + "<ns1:address><ns2:street>Pikk 1</ns2:street><ns2:city>Tallinn</ns2:city></ns1:address>");
    }

    @Test
    void inputThatDoesNotMatchNeverReachesTheSoapService() {
        var r = Patchbay.send("POST", "registry-get-person", """
                {"history":"maybe"}""");

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body()).contains("personCode").contains("history");
        assertThat(Mock.total()).isZero();
    }

    @Test
    void aSoapIntegrationIsDescribedInTheOpenApiDocumentAsAJsonPost() {
        JsonNode doc = Http.send("GET", Patchbay.URL + "/api/openapi.json", null, null, Map.of()).json();

        JsonNode post = doc.path("paths").path("/api/v1/integrations/registry-get-person").path("post");
        assertThat(post.isMissingNode()).as("POST registry-get-person in %s", doc.path("paths").fieldNames()).isFalse();
        assertThat(post.path("requestBody").toString()).contains("application/json").contains("personCode");
    }
}
