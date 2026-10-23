package patchbaytest;

import static org.assertj.core.api.Assertions.assertThat;
import static patchbaytest.support.Json.assertJsonEquals;

import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import patchbaytest.support.Mock;
import patchbaytest.support.Patchbay;
import patchbaytest.support.PatchbayTest;

/** GET, POST, PUT and DELETE against the FakeRESTApi Books stand-in (mock/mappings/books). */
class BooksTest extends PatchbayTest {

    @BeforeAll
    static void publish() {
        Patchbay.ensurePublished("books-list", "books-get", "books-create", "books-update", "books-delete");
    }

    @Test
    void getListMapsTheBareArrayIntoAnOutputListKeepingNullAndLeavingOutMissing() {
        var r = Patchbay.call("GET", "books-list", Map.of());

        assertThat(r.status()).isEqualTo(200);
        assertJsonEquals(r.json(), """
                {"books":[
                  {"bookId":1,"title":"Book 1","description":"First","pages":100},
                  {"bookId":2,"title":"Book 2","description":"Second","pages":200},
                  {"bookId":3,"title":"Book 3","description":"Third","pages":300},
                  {"bookId":4,"title":"Book 4","description":null,"pages":400},
                  {"bookId":5,"title":"Book 5","pages":500}]}""");
        assertThat(Mock.single("GET", "/api/v1/Books").url()).isEqualTo("/api/v1/Books");
    }

    @Test
    void getOneFillsThePathFromAQueryParameterAndRenamesEveryField() {
        var r = Patchbay.call("GET", "books-get", Map.of("bookId", 7));

        assertThat(r.status()).isEqualTo(200);
        assertJsonEquals(r.json(), """
                {"bookId":7,"title":"Book 7","description":"Description of book 7","pages":700,
                 "excerpt":"Excerpt 7","published":"2026-01-15T10:30:00Z"}""");
        assertThat(Mock.single("GET", "/api/v1/Books/7")).isNotNull();
    }

    @Test
    void getOfAnUnknownBookPassesThe404AndItsBodyThrough() {
        var r = Patchbay.call("GET", "books-get", Map.of("bookId", 404));

        assertThat(r.status()).isEqualTo(404);
        assertThat(r.header("X-Integration-Error")).isEqualTo("remote");
        assertThat(r.json().path("title").asText()).isEqualTo("Not Found");
    }

    @Test
    void getWithAQueryParameterOfTheWrongTypeIsRefusedWithoutCallingTheVendor() {
        var r = Patchbay.call("GET", "books-get", Map.of("bookId", "seven"));

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.json().path("problems").toString()).contains("bookId");
        assertThat(Mock.total()).isZero();
    }

    @Test
    void postRenamesFieldsBuildsANestedObjectAndAddsTheConstant() {
        var r = Patchbay.send("POST", "books-create", """
                {"title":"Patchbay","summary":"No-code mapping","pages":42,"published":"2026-10-20T00:00:00Z","available":true}""");

        assertThat(r.status()).isEqualTo(200);
        assertJsonEquals(Mock.single("POST", "/api/v1/Books").json(), """
                {"title":"Patchbay","description":"No-code mapping","pageCount":42,"publishDate":"2026-10-20T00:00:00Z",
                 "meta":{"available":true},"excerpt":"Created by Patchbay tests"}""");
        assertJsonEquals(r.json(), """
                {"bookId":4242,"title":"Patchbay","summary":"No-code mapping","pages":42,"published":"2026-10-20T00:00:00Z",
                 "available":true,"source":"Created by Patchbay tests"}""");
    }

    @Test
    void postIgnoresAValueTheCallerSendsForAConstant() {
        Patchbay.send("POST", "books-create", """
                {"title":"T","pages":1,"source":"caller tries to override"}""");

        assertThat(Mock.single("POST", "/api/v1/Books").json().path("excerpt").asText()).isEqualTo("Created by Patchbay tests");
    }

    @Test
    void postLeavesOutFieldsTheCallerDidNotSendAndAnObjectWithNothingInIt() {
        Patchbay.send("POST", "books-create", """
                {"title":"Only required","pages":10}""");

        assertJsonEquals(Mock.single("POST", "/api/v1/Books").json(), """
                {"title":"Only required","pageCount":10,"excerpt":"Created by Patchbay tests"}""");
    }

    @Test
    void postWithoutARequiredFieldListsEveryProblemAndDoesNotCallTheVendor() {
        var r = Patchbay.send("POST", "books-create", """
                {"summary":"no title, no pages"}""");

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.json().path("error").asText()).isEqualTo("bad-request");
        assertThat(r.json().path("problems").toString()).contains("title: required").contains("pages: required");
        assertThat(Mock.total()).isZero();
    }

    @Test
    void putSendsOneInputFieldToBothThePathAndTheBodyAndPassesAnExplicitNull() {
        var r = Patchbay.send("PUT", "books-update", """
                {"bookId":12,"title":"New title","summary":null}""");

        assertThat(r.status()).isEqualTo(200);
        assertJsonEquals(Mock.single("PUT", "/api/v1/Books/12").json(), """
                {"id":12,"title":"New title","description":null}""");
        assertJsonEquals(r.json(), """
                {"bookId":12,"title":"New title","summary":null}""");
    }

    @Test
    void deleteTakesTheIdFromTheQueryAndAnswersAnEmptyOutputForAnEmptyVendorBody() {
        var r = Patchbay.call("DELETE", "books-delete", Map.of("bookId", 3));

        assertThat(r.status()).isEqualTo(200);
        assertJsonEquals(r.json(), "{}");
        assertThat(Mock.single("DELETE", "/api/v1/Books/3").body()).isEmpty();
    }
}
