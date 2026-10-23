package patchbaytest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import patchbaytest.support.Patchbay;
import patchbaytest.support.PatchbaySetup;
import patchbaytest.support.PatchbayTest;

/** The admin API: publish, versions, activate, and a draft whose links break the rules. */
class PublishLifecycleTest extends PatchbayTest {

    @BeforeAll
    static void imported() {
        PatchbaySetup.require("import package");
    }

    @Test
    void everyPublishAddsAMinorVersionAndAnOlderOneCanBeMadeLiveAgain() {
        JsonNode first = Patchbay.publish("lifecycle-book").json();
        JsonNode second = Patchbay.publish("lifecycle-book").json();
        String v1 = first.path("activeVersion").asText();
        String v2 = second.path("activeVersion").asText();

        assertThat(v2).isNotEqualTo(v1);
        assertThat(minor(v2)).isEqualTo(minor(v1) + 1);
        JsonNode versions = Patchbay.versions("lifecycle-book").json();
        assertThat(versions.toString()).contains(v1).contains(v2);
        assertThat(Patchbay.integrations().path("active").path("lifecycle-book").toString()).contains(v2);

        assertThat(Patchbay.activate("lifecycle-book", v1).status()).isEqualTo(200);
        assertThat(Patchbay.integrations().path("active").path("lifecycle-book").toString()).contains(v1);
        assertThat(Patchbay.call("GET", "lifecycle-book", Map.of("bookId", 3)).json().path("title").asText()).isEqualTo("Book 3");
    }

    @Test
    void activatingAVersionThatDoesNotExistIsRefused() {
        Patchbay.publish("lifecycle-book");

        assertThat(Patchbay.activate("lifecycle-book", "9.9.9").status()).isBetween(400, 404);
    }

    @Test
    void aDraftWithBrokenLinksIsNotPublishedAndEveryProblemIsListed() {
        var r = Patchbay.publish("broken-links");

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body())
                .contains("so-gone")
                .contains("Link the surrounding list first")
                .contains("A list can only be linked to a list")
                .contains("A single value can't be linked to a list");
        assertThat(Patchbay.integrations().path("active").has("broken-links")).isFalse();
        assertThat(Patchbay.send("POST", "broken-links", "{}").status()).isEqualTo(404);
    }

    static int minor(String version) {
        return Integer.parseInt(version.split("\\.")[1]);
    }
}
