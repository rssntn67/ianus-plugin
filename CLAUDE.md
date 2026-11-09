# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

IANUS Plugin 2.0: a Spring Boot 4 / Java 21 service that sits between Network Management Systems (OpenNMS, Prometheus, Icinga 2, Zabbix) and the IANUS appliance. IANUS asks for an aligned time window; the plugin answers with already-aggregated data (mean/min/max/samples per series, grouped events with count/first_seen/last_seen). `SPECIFICA.md` is the authoritative contract (v2) — check it before changing response shapes, aggregation rules, severity mapping or error codes. The README documents per-source behaviour and endpoints.

Code, comments, docs and error messages are written in **Italian**; keep that convention.

## Commands

No Maven wrapper — use a system `mvn` (3.9+).

```bash
mvn clean verify                                   # build + all tests
mvn test -Dtest=WindowResolverTest                 # single test class
mvn test -Dtest=AggregatorTest#methodName          # single test method
java -jar target/ianus-plugin-2.0.0-SNAPSHOT.jar --ianus.config=/path/ianus.yml
docker build -t ianus-plugin:2.0.0 .               # after mvn package (copies target/*.jar)
```

The app refuses to start without an external config file: the packaged `src/main/resources/application.yml` holds only a mandatory `spring.config.import` of `--ianus.config` / `IANUS_CONFIG` (default `/etc/ianus/ianus.yml`). `config/ianus-template.yml` is only the template to copy and fill in; it is not in the jar, so add new settings there. `ianus.api-key` is also required in that file (startup fails if blank), and all sources are `enabled: false` in the template. Invalid configuration fails at startup rather than at request time: `IanusProperties` requires `PT5M` and `PT30M` in `ianus.windows`, `PrometheusProperties.PromMetric` requires snake_case names, `Collectors` checks `collect-interval`/`collect-delay`/`retention`. OpenAPI at `/v3/api-docs` and `/swagger-ui.html` (no API key required).

## Architecture

Package root `it.infocube.ianus.plugin`.

**Data flow:** one `core.collector.SourceCollector` per source reads aligned blocks through `SourceAdapter` every `collect-interval` (after `collect-delay`) and stores them in a `BlockStore`, recovering missing blocks in idle time; `Collectors` runs one thread per source and refuses invalid configuration at startup. **Request flow:** `web.IanusController` → `core.service.IanusService` → `BlockStore`: requests never reach an NMS, except `/ianus/v2/health`. `web.ApiKeyFilter` checks `X-API-Key`; Errors are subclasses of `core.error.IanusException`, which carries its own `HttpStatus` and `code` (`BadRequestException` 400, `SourceNotFoundException` 404, `SourceUnavailableException` 503, also via `noBlocks(source)` when a window has no collected block); `web.ApiExceptionHandler` turns them into the `code`/`message` body. Throw one of these rather than adding new handlers.

**`IanusService` owns all source-independent logic** — adapters must not reimplement it:
- resolves `window`/`end` into a `TimeWindow` via `WindowResolver` (windows aligned to multiples of their width, `[start, end)`, default = last completed window, unaligned/future `end` → 400);
- looks up the adapter by `system()` (the `{source}` URL segment);
- builds the window from its blocks with `aggregation.Consolidator` (`complete` is false if a block is missing or incomplete, no block → 503, older than `ianus.retention` → 400 `window_too_old`);
- re-applies node/metric filters, sorts deterministically, paginates, builds the response envelope.

**`core.source.SourceAdapter`** is the plugin interface: `metrics(window, filter)`, `events(window, filter)`, `health()`, each returning data already in the IANUS model. `SourceResult.complete = false` signals a partial NMS answer (still valid data). Each source in `sources/<name>/` follows the same trio: `<Name>Properties` (`@ConfigurationProperties`), `<Name>Client` (built on `core.util.HttpClients`, JSON handled via `core.util.Json`), `<Name>Adapter` (`@Component` + `@ConditionalOnProperty(prefix = "ianus.sources.<name>", name = "enabled", havingValue = "true")`). Adding a source = adding this trio; nothing else needs registering.

**Shared building blocks in `core`:**
- `aggregation.Aggregator` / `Stats` / `MetricKind` — gauge mean/min/max and counter→rate conversion (counter resets are dropped, never negative rates); a known series with no samples is still returned with null values and `samples: 0`.
- `aggregation.EventAggregator` — groups `RawEvent`s by node, event id, instance and severity.
- `severity.SeverityMapper` — native severities → INFO/WARNING/ERROR/CRITICAL (spec §6.1), native value preserved; unknown or blank severities become WARNING and are logged once per source (blank is expected, and not logged, for Prometheus).
- `buffer.SampleBuffer` / `EventBuffer` — in-memory retention for sources with no history API (Icinga polls on a `@Scheduled` interval; after restart, earlier windows are `complete: false`).
- `util.RetryInterceptor` — every NMS client retries up to 3 times (200 ms, 1 s) on connection failure and 502/503/504, never on read timeout.
- `util.Names` (metric names → lowercase snake_case, e.g. `ifHCInOctets` → `if_hc_in_octets`), `util.Times` (output always RFC 3339 UTC with `Z`; parses epoch/ISO/OpenNMS formats).

Units: adapters convert to IANUS units (`percent`, `bytes`, `seconds`, `<unit>_per_second` for counters), scaling values when needed (Icinga and Zabbix divide `ms` by 1000); a missing unit is `none`, an unrecognised one passes through lowercased.

Sources differ in where aggregation happens: Prometheus pushes it to the NMS (PromQL `*_over_time` evaluated at `end − 1ms`), OpenNMS fetches `AVERAGE` points from the Measurements API at `sample-step` and aggregates locally, Zabbix fetches raw `history.get` samples and aggregates locally, Icinga aggregates from its own buffer.

**Tests** are mostly plain JUnit 5 + AssertJ unit tests (no live NMS); `IanusPluginApplicationTest`, `JsonSerializationTest` and `ConfigurationFileTest` start the Spring context, and with it the collector threads. `src/test/resources/application.yml` shadows the packaged one, so tests don't need the external file. Adapter tests exercise package-private static mapping helpers (e.g. `ZabbixAdapter.toItem`, `toRaw`) with `Map` fixtures; time-dependent code takes an injected `Clock`. Spec §9 lists the minimum test coverage expected for a source (window alignment, gauge/counter incl. reset, empty series, full severity mapping, unreachable NMS).

`_to_delete/` holds an old archive and is not part of the build.
