# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Purpose

End-to-end tests for Patchbay, the integration mapper in the sibling project `../JustDoIntegration` (its own CLAUDE.md and `engine/README.md` describe the engine). Two containers run: **Patchbay** (system under test) and **WireMock** (the external APIs Patchbay calls). JUnit tests in this project call Patchbay's published endpoints and read WireMock's request journal to see exactly what Patchbay sent.

The intended workflow: run the suite, count the failures, then improve Patchbay in `../JustDoIntegration` until the tests pass. A failing test here is often a real Patchbay finding, not a test bug. Check the documented behaviour in `../JustDoIntegration/engine/README.md` before changing an assertion.

## Commands

```
docker compose up -d --build --wait     # builds Patchbay from ../JustDoIntegration/engine (PATCHBAY_ENGINE_DIR overrides)
mvn test                                # all tests; containers must be running
mvn test -Dtest=NestedMappingTest       # one class; -Dtest=NestedMappingTest#deleteSendsPathAndQueryAndMapsTheNestedResponse one method
docker compose up -d --force-recreate --wait patchbay   # fresh, empty Patchbay (its /config is a tmpfs)
java tools/GenerateFixtures.java        # regenerate the large catalog, the 120-field integration and the binary fixtures
docker compose down
```

Patchbay listens on localhost:18080, WireMock on localhost:18081 (`-Dpatchbay.url`, `-Dmock.url` override). Inside the compose network Patchbay reaches the mock as `http://mock:8080`. After editing `mock/mappings`, run `curl -X POST localhost:18081/__admin/mappings/reset` or restart the mock. Java 25, JUnit 6, no Spring.

## How a test run configures Patchbay

`PatchbaySetup` runs once per JVM (from `PatchbayTest`'s `@BeforeAll`) against an empty Patchbay:

1. It imports each swagger file in `mock/__files/openapi/<api>.yaml` by URL (`POST /admin/api/contracts`). WireMock serves these at `/openapi/<api>.yaml`.
2. It sets connections from `patchbay/environment.json` (`PUT /admin/api/connections/{id}`). Connections and secrets never travel in packages. Secrets are `MAPPER_SECRET_<SLUG>` in `docker-compose.yml`.
3. It zips `patchbay/package` and `patchbay/docs-package` into Patchbay packages (`manifest.json` + `entities/` + `services/`) and imports them (`POST /admin/api/import`). Copies land in `target/patchbay-*.zip`.
4. It publishes every integration, except those under `notPublished` in `environment.json`.

Each step is recorded instead of thrown. `Patchbay.ensurePublished(slug)` fails only the tests whose setup step failed, and `SetupTest` reports every step by name. Contract id = connection id = the `contract`/`target.connection` of a draft.

## Layout that spans several files

- An integration is a draft in `patchbay/package/services/<slug>.json` that names payload definitions in `patchbay/package/entities/`. It calls a swagger operation (`contract` + `operationId`, and `SetupTest` checks method/path against the imported contract). That operation is stubbed in `mock/mappings/<api>/*.json` and described in `mock/__files/openapi/<api>.yaml`. Keep all four consistent when adding one.
- Echo stubs answer the received body under `received`/`update`/`user`, so response mapping is tested with the data Patchbay itself sent. In WireMock templates write `{{{request.body}}} }` with a space; `}}}}` breaks Handlebars.
- `patchbay/docs-package` holds the document integrations of Patchbay's D-29 (in `../JustDoIntegration/docs/slice-plan.md`): a `blob` field type, held inside Patchbay as `{"contentType","fileName","size","data":"<base64>"}`, accepted and sent as raw body, multipart or base64 JSON on both sides (`formats.request` `binary`/`multipart`, `formats.output` `binary`, `$body` for a whole-body answer, `<blob>.data|fileName|contentType|size` in links, limit `mapper.max-blob-bytes` → 413). It stays a separate package so a document problem can't block the import of everything else. `DocumentsTest`'s class comment is the caller-side contract.
- WireMock's `multipartPatterns` with `"matchingType": "ALL"` requires every part to match; use `ANY` to ask for one part.
- `LargePayloadTest` and `DocumentsTest` repeat the formulas of `tools/GenerateFixtures.java`; change both together.
- `PatchbayTest` clears WireMock's journal before every test, so tests must not run in parallel.
- From the Windows host, call the containers at `127.0.0.1`: Docker Desktop's IPv6 forwarding for `localhost` can stop answering (the JUnit client uses IPv4 and is not affected).

## Known Patchbay findings (failing on purpose)

None at the moment: all 178 tests pass against Patchbay `console/foundation` (up to `4f5cc89`). Fixed so far: outbound retries and cookies (OV-8), files as blobs (D-29).
