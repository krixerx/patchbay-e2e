# patchbay-e2e

End-to-end test suite for Patchbay, the integration mapper. Docker Compose starts Patchbay and WireMock (fake REST and SOAP APIs); JUnit configures Patchbay through its admin API, calls the published integrations and checks every outbound request in WireMock's journal.

Patchbay itself is in [krixerx/pathchbay](https://github.com/krixerx/pathchbay). Docker Compose builds it from a checkout next to this one (`../JustDoIntegration/engine`); set `PATCHBAY_ENGINE_DIR` to build it from elsewhere.

## Run it

You need Docker, Java 25 and Maven.

```bash
docker compose up -d --build --wait   # builds and starts Patchbay and WireMock
mvn test                              # all tests; the containers must be running
mvn test -Dtest=BooksTest             # one class
docker compose down
```

Patchbay listens on `localhost:18080`, WireMock on `localhost:18081` (`-Dpatchbay.url` and `-Dmock.url` override them). Patchbay starts with an empty, in-memory `/config` every time, and with the admin login from `docker-compose.yml`, which the tests send on every admin call. `docker compose up -d --force-recreate --wait patchbay` gives a fresh, empty Patchbay.

## How a run works

Before the first test, `PatchbaySetup` configures the empty Patchbay the way a user would: it imports the Swagger and WSDL files that WireMock serves, sets the connections from `patchbay/environment.json`, imports the integrations in `patchbay/package` and `patchbay/docs-package` as packages, and publishes them. `SetupTest` reports each of those steps. The other tests call the published integrations and compare what Patchbay sent to WireMock, and what it answered, with what they expect.

A failing test is often a real Patchbay bug: check the documented behaviour in Patchbay's README before changing an assertion.

## Layout

| Path | What it holds |
|---|---|
| `src/test/java/patchbaytest` | The tests, one class per area (books, users, nested mapping, large payloads, XML, SOAP, documents, headers and auth, errors, publishing) |
| `src/test/java/patchbaytest/support` | The HTTP client, Patchbay and WireMock helpers, and the setup |
| `patchbay/package`, `patchbay/docs-package` | The integrations and payload definitions imported into Patchbay |
| `patchbay/environment.json` | Connections per Swagger configuration, and integrations that are not published |
| `mock/mappings`, `mock/__files` | WireMock stubs, and the Swagger, WSDL and XSD files it serves |
| `tools/GenerateFixtures.java` | Regenerates the large and binary fixtures |

`CLAUDE.md` has the details on keeping drafts, contracts and stubs consistent.
