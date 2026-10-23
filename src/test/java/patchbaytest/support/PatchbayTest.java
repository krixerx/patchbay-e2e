package patchbaytest.support;

import java.net.ConnectException;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

/**
 * Base for every test class: checks once that both containers answer and configures Patchbay
 * ({@link PatchbaySetup}), and clears the mock's request journal before
 * each test so {@link Mock#received} only sees the requests of that test. Tests therefore run one at a time.
 */
public abstract class PatchbayTest {

    private static boolean up;

    @BeforeAll
    static void containersAreUp() {
        if (up) {
            return;
        }
        check("Patchbay", Patchbay.URL + "/admin/integrations");
        check("WireMock", Mock.URL + "/__admin/health");
        up = true;
        PatchbaySetup.run();
    }

    @BeforeEach
    void clearMockJournal() {
        Mock.clearRequests();
    }

    private static void check(String name, String url) {
        try {
            int status = Http.send("GET", url, null, null, Map.of()).status();
            if (status != 200) {
                throw new IllegalStateException(name + " answered " + status + " at " + url);
            }
        } catch (IllegalStateException e) {
            if (e.getCause() instanceof ConnectException) {
                throw new IllegalStateException(name + " is not running at " + url
                        + ". Start the containers first: docker compose up -d --build --wait", e);
            }
            throw e;
        }
    }
}
