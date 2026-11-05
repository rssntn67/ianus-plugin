# Collector e consolidamento della cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ogni sorgente raccoglie i dati dal proprio NMS a intervalli configurabili e IANUS riceve solo dati raccolti, consolidati sulla finestra richiesta.

**Architecture:** un `SourceCollector` per sorgente legge blocchi allineati tramite `SourceAdapter` e li salva in un `BlockStore` in memoria. `IanusService` legge solo dal `BlockStore` e consolida i blocchi della finestra con `Consolidator`. `ResultCache` viene eliminata.

**Tech Stack:** Java 21, Spring Boot 4, JUnit 5, AssertJ, Maven.

**Spec:** `docs/superpowers/specs/2026-11-05-collector-design.md`

## Global Constraints

- Codice, commenti, messaggi e documentazione in italiano, senza accenti nei sorgenti Java (`e'`, `piu'`), come nel resto del codice.
- Default: `ianus.retention` `PT3H`, `collect-interval` `PT5M`, `collect-delay` `PT1M`.
- Massimo 3 riletture per blocco incompleto.
- Le richieste di IANUS non interrogano mai gli NMS, salvo `/ianus/v2/health`.
- Non cambiare la logica di finestre, aggregazione (`Aggregator`) e severità.
- Ogni commit termina con `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Prima di dichiarare chiuso un task: `mvn -B -o clean verify` verde.

## Review Focus

1. Un adapter che lancia un'eccezione imprevista, diversa da `SourceUnavailableException`: il thread della sorgente deve sopravvivere e continuare (Task 3, `unexpectedExceptionDoesNotStopCollector`).
2. `collect-delay` maggiore di `collect-interval`: il blocco dovuto va calcolato comunque correttamente (Task 3, `delayLongerThanIntervalPicksRightBlock`).
3. Serie che compaiono o spariscono tra un blocco e l'altro: restano nella finestra con i soli dati disponibili (Task 1, `seriesPresentOnlyInSomeBlocksKeepsItsData`).
4. Finestra che inizia esattamente al limite della `retention`: va servita, e il suo primo blocco non deve essere già stato eliminato (Task 5, `windowStartingAtRetentionLimitIsServed`).
5. Letture di IANUS durante la scrittura di un blocco dal thread del Collector: nessun errore e nessun blocco a metà (Task 2, `concurrentReadsDuringPutAreSafe`).

---

## File Structure

| File | Responsabilità |
| --- | --- |
| `core/aggregation/Consolidator.java` (nuovo) | Da serie ed eventi di più blocchi a quelli della finestra |
| `core/collector/Block.java` (nuovo) | Risultato della lettura di un blocco |
| `core/collector/BlockStore.java` (nuovo) | Archivio in memoria dei blocchi di una sorgente |
| `core/collector/SourceCollector.java` (nuovo) | Ciclo e recupero di una sorgente, senza thread |
| `core/collector/Collectors.java` (nuovo) | Validazione, registro degli archivi, thread delle sorgenti |
| `config/CollectorProperties.java` (nuovo) | `collect-interval` e `collect-delay` per sorgente |
| `core/service/IanusService.java` | Legge dall'archivio invece che dagli adapter |
| `core/service/ResultCache.java` | Eliminata |

---

### Task 1: Consolidatore

**Files:**
- Create: `src/main/java/it/infocube/ianus/plugin/core/aggregation/Consolidator.java`
- Test: `src/test/java/it/infocube/ianus/plugin/core/ConsolidatorTest.java`

**Interfaces:**
- Consumes: `MetricSeries`, `EventGroup`, `EventAggregator.merge(Collection<EventGroup>)`.
- Produces: `public static List<MetricSeries> Consolidator.metrics(Collection<MetricSeries> series)` e `public static List<EventGroup> Consolidator.events(Collection<EventGroup> groups)`. L'input è l'unione delle voci di tutti i blocchi della finestra.

- [ ] **Step 1: Scrivi i test che falliscono**

Con `s(value, min, max, samples)` che crea una serie `node=a, metric=m, instance=i, unit=u`:

```java
@Test void weightsValueBySamples() {
    MetricSeries r = single(Consolidator.metrics(List.of(s(10.0, 5.0, 20.0, 1), s(40.0, 30.0, 50.0, 3))));
    assertThat(r.value()).isEqualTo(32.5);   // (10*1 + 40*3) / 4
    assertThat(r.min()).isEqualTo(5.0);
    assertThat(r.max()).isEqualTo(50.0);
    assertThat(r.samples()).isEqualTo(4);
}
@Test void blockWithoutSamplesDoesNotWeigh() {
    MetricSeries r = single(Consolidator.metrics(List.of(s(10.0, 10.0, 10.0, 2), s(null, null, null, 0))));
    assertThat(r.value()).isEqualTo(10.0);
    assertThat(r.samples()).isEqualTo(2);
}
@Test void seriesWithoutAnySampleStaysNull() {
    MetricSeries r = single(Consolidator.metrics(List.of(s(null, null, null, 0), s(null, null, null, 0))));
    assertThat(r.value()).isNull();
    assertThat(r.min()).isNull();
    assertThat(r.max()).isNull();
    assertThat(r.samples()).isZero();
}
@Test void seriesWithDifferentLabelsStayDistinct() { /* stesso node/metric/instance, labels diverse: 2 serie */ }
@Test void seriesPresentOnlyInSomeBlocksKeepsItsData() { /* serie b solo nel secondo blocco: 2 serie, b con i suoi valori */ }
@Test void eventsAreMergedAcrossBlocks() { /* count sommato, first_seen il piu' vecchio, last_seen e message del piu' recente */ }
```

- [ ] **Step 2: Verifica che falliscano**

Run: `mvn -B -o test -Dtest=ConsolidatorTest`
Expected: errore di compilazione, `Consolidator` non esiste.

- [ ] **Step 3: Implementa `Consolidator`**

Raggruppa per `node`, `metric`, `instance`, `labels`; `unit` dalla prima voce del gruppo. `value` è `sum(value * samples) / sum(samples)` sulle voci con `samples > 0`. `events` delega a `EventAggregator.merge`.

- [ ] **Step 4: Verifica che passino**

Run: `mvn -B -o test -Dtest=ConsolidatorTest`
Expected: 6 test verdi.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/it/infocube/ianus/plugin/core/aggregation/Consolidator.java src/test/java/it/infocube/ianus/plugin/core/ConsolidatorTest.java
git commit -m "Consolidatore dei blocchi sulla finestra"
```

---

### Task 2: Block e BlockStore

**Files:**
- Create: `src/main/java/it/infocube/ianus/plugin/core/collector/Block.java`
- Create: `src/main/java/it/infocube/ianus/plugin/core/collector/BlockStore.java`
- Test: `src/test/java/it/infocube/ianus/plugin/core/BlockStoreTest.java`

**Interfaces:**
- Produces:
  - `public record Block(Instant start, Instant end, List<MetricSeries> metrics, List<EventGroup> events, boolean complete)`
  - `public BlockStore(Duration interval, Duration retention, Clock clock)`
  - `public void put(Block block)`: salva ed elimina i blocchi con `start` precedente a `now - retention`. Un blocco completo non viene sostituito da uno incompleto.
  - `public Optional<Block> get(Instant start)`
  - `public List<Block> between(Instant start, Instant end)`: blocchi con inizio in `[start, end)`, in ordine crescente.
  - `public boolean complete(Instant start, Instant end)`: vero se ci sono tutti i blocchi attesi (`(end - start) / interval`) e sono tutti completi.
  - `public Optional<Block> latest()`
  - `public Duration interval()`

- [ ] **Step 1: Scrivi i test che falliscono**

`clock` è un orologio modificabile, come `MutableClock` in `IcingaAdapterTest`.

```java
@Test void betweenReturnsOnlyBlocksInsideRangeInOrder() { /* blocchi a 10:00, 10:05, 10:10; between(10:00, 10:10) -> 10:00, 10:05 */ }
@Test void blocksOlderThanRetentionArePruned() { /* retention PT3H: dopo put a now=13:06 il blocco 10:00 non c'e' piu' */ }
@Test void completeBlockReplacesIncompleteOne() { /* get restituisce quello completo */ }
@Test void incompleteBlockDoesNotReplaceCompleteOne() { /* get resta quello completo */ }
@Test void completeNeedsAllBlocksAndAllComplete() {
    // tutti presenti e completi -> true; uno mancante -> false; uno incompleto -> false
}
@Test void seriesValuesSurviveStorage() { /* value, min, max, samples, labels e serie con null identici dopo get */ }
@Test void identicalSeriesShareIdentity() {
    // due blocchi con la stessa serie: le stringhe node restituite da get sono la stessa istanza
    assertThat(first.metrics().get(0).node()).isSameAs(second.metrics().get(0).node());
}
@Test void concurrentReadsDuringPutAreSafe() { /* un thread fa put in ciclo, l'altro between: nessuna eccezione */ }
```

- [ ] **Step 2: Verifica che falliscano**

Run: `mvn -B -o test -Dtest=BlockStoreTest`
Expected: errore di compilazione, `BlockStore` non esiste.

- [ ] **Step 3: Implementa `Block` e `BlockStore`**

`ConcurrentSkipListMap` per istante di inizio. Internamente ogni serie è un riferimento a un'identità condivisa (`node`, `metric`, `instance`, `unit`, `labels`) più `value`, `min`, `max` come `double` (`NaN` per `null`) e `samples`. Le identità stanno in una mappa per sorgente; ad ogni eliminazione si tolgono quelle non più usate. `get` e `between` ricostruiscono i `MetricSeries`.

- [ ] **Step 4: Verifica che passino**

Run: `mvn -B -o test -Dtest=BlockStoreTest`
Expected: 8 test verdi.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/it/infocube/ianus/plugin/core/collector src/test/java/it/infocube/ianus/plugin/core/BlockStoreTest.java
git commit -m "Archivio in memoria dei blocchi raccolti"
```

---

### Task 3: SourceCollector

**Files:**
- Create: `src/main/java/it/infocube/ianus/plugin/core/collector/SourceCollector.java`
- Test: `src/test/java/it/infocube/ianus/plugin/core/SourceCollectorTest.java`

**Interfaces:**
- Consumes: `BlockStore` (Task 2), `SourceAdapter.metrics/events`, `QueryFilter.NONE`, `TimeWindow`.
- Produces:
  - `public SourceCollector(SourceAdapter adapter, BlockStore store, Duration delay, Duration retention, Clock clock)`; l'intervallo arriva da `store.interval()`.
  - `public void collectDue()`: legge il blocco dovuto, se non già letto.
  - `public boolean recoverOne()`: legge un blocco mancante o incompleto; `false` se non c'è nulla da fare o l'NMS non risponde.
  - `public Instant nextRun()`: fine del blocco successivo a quello dovuto, più `delay`.

Il blocco dovuto è l'ultimo con `fine + delay <= now`:

```java
Instant dueStart(Instant now) {
    long step = interval.toMillis();
    long limit = now.minus(delay).toEpochMilli();
    return Instant.ofEpochMilli(Math.floorDiv(limit, step) * step).minus(interval);
}
```

- [ ] **Step 1: Scrivi i test che falliscono**

`FakeAdapter` conta le letture per finestra e può rispondere con dati, con `complete = false`, con `SourceUnavailableException` o con `IllegalStateException`. Con `interval` `PT5M` e `delay` `PT1M`:

```java
@Test void collectsLastBlockAfterDelay() { /* now=10:06:00 -> legge [10:00, 10:05) */ }
@Test void waitsForDelayBeforeCollecting() { /* now=10:05:30 -> legge [09:55, 10:00), non [10:00, 10:05) */ }
@Test void delayLongerThanIntervalPicksRightBlock() { /* delay PT7M, now=10:12:00 -> legge [10:00, 10:05) */ }
@Test void doesNotReadSameBlockTwice() { /* due collectDue allo stesso now: una sola lettura */ }
@Test void blockIsIncompleteIfMetricsOrEventsAre() { /* metrics complete, events no -> Block.complete false */ }
@Test void unreachableNmsSavesNothing() { /* SourceUnavailableException -> store.get vuoto */ }
@Test void unexpectedExceptionDoesNotStopCollector() {
    // IllegalStateException al primo collectDue: nessuna eccezione verso il chiamante,
    // al ciclo successivo la lettura riesce e il blocco viene salvato
}
@Test void recoveryReadsMissingBlocksFromNewest() { /* dopo collectDue, recoverOne legge 09:55, poi 09:50 */ }
@Test void recoveryStopsWhenNmsIsUnreachable() { /* recoverOne -> false, nessun blocco salvato */ }
@Test void recoveryStaysInsideRetention() { /* retention PT15M: nessuna lettura oltre */ }
@Test void incompleteBlockIsReadAtMostThreeMoreTimes() { /* sempre incompleto: 3 riletture, poi recoverOne -> false */ }
@Test void nextRunIsEndOfNextBlockPlusDelay() { /* now=10:06:00 -> 10:11:00 */ }
```

- [ ] **Step 2: Verifica che falliscano**

Run: `mvn -B -o test -Dtest=SourceCollectorTest`
Expected: errore di compilazione, `SourceCollector` non esiste.

- [ ] **Step 3: Implementa `SourceCollector`**

Una lettura chiama `metrics` ed `events` con `QueryFilter.NONE` e salva un `Block` completo solo se lo sono entrambi. `SourceUnavailableException` registra `LOG.warn`; ogni altra `RuntimeException` registra `LOG.error`; in entrambi i casi non si salva nulla. `collectDue` registra la durata e `LOG.warn` se supera l'intervallo. Il log dei tempi di `IanusService.timed` passa qui, nello stesso formato.

- [ ] **Step 4: Verifica che passino**

Run: `mvn -B -o test -Dtest=SourceCollectorTest`
Expected: 12 test verdi.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/it/infocube/ianus/plugin/core/collector/SourceCollector.java src/test/java/it/infocube/ianus/plugin/core/SourceCollectorTest.java
git commit -m "Ciclo di raccolta e recupero di una sorgente"
```

---

### Task 4: Configurazione e avvio dei Collector

**Files:**
- Create: `src/main/java/it/infocube/ianus/plugin/config/CollectorProperties.java`
- Create: `src/main/java/it/infocube/ianus/plugin/core/collector/Collectors.java`
- Modify: `src/main/java/it/infocube/ianus/plugin/config/IanusProperties.java`
- Modify: `src/main/java/it/infocube/ianus/plugin/sources/icinga/IcingaAdapter.java`
- Modify: `config/ianus-template.yml`
- Test: `src/test/java/it/infocube/ianus/plugin/core/CollectorsTest.java`, `src/test/java/it/infocube/ianus/plugin/sources/icinga/IcingaAdapterTest.java`

**Interfaces:**
- Consumes: `BlockStore`, `SourceCollector`, `SourceAdapter`, `WindowResolver.supported()`.
- Produces:
  - `IanusProperties` aggiunge in fondo `Duration retention`, default `PT3H`. In questo task `cacheTtl` e `cacheMaxEntries` restano.
  - `@ConfigurationProperties("ianus") public record CollectorProperties(Map<String, CollectSettings> sources)` con `public record CollectSettings(Duration collectInterval, Duration collectDelay)` e `public CollectSettings settings(String system)`, che applica i default.
  - `@Component public class Collectors implements SmartLifecycle`, costruttore `(ObjectProvider<SourceAdapter>, CollectorProperties, IanusProperties, WindowResolver, Clock)`, metodo `public BlockStore store(String system)`.

- [ ] **Step 1: Scrivi i test che falliscono**

```java
@Test void intervalMustDivideEveryWindow() { /* PT10M con windows PT5M e PT30M -> IllegalStateException con il nome della sorgente */ }
@Test void retentionMustCoverLargestWindow() { /* retention PT20M con PT30M -> IllegalStateException */ }
@Test void validConfigurationBuildsOneStorePerSource() { /* store("fake") con interval PT5M */ }
@Test void settingsUseDefaults() { /* sorgente non configurata -> PT5M e PT1M */ }
```

In `ConfigurationFileTest`, con `Collectors collectors` iniettato, `templateBindsAllSources` verifica anche:

```java
assertThat(collectors.store("opennms").interval()).isEqualTo(Duration.ofMinutes(5));
```

In `IcingaAdapterTest`:

```java
@Test void retentionShorterThanGlobalOneRefusesToStart() { /* retention Icinga PT1H, globale PT3H -> IllegalStateException */ }
```

- [ ] **Step 2: Verifica che falliscano**

Run: `mvn -B -o test -Dtest='CollectorsTest,IcingaAdapterTest,ConfigurationFileTest'`
Expected: errore di compilazione, `Collectors` non esiste.

- [ ] **Step 3: Implementa configurazione e `Collectors`**

`Collectors` valida nel costruttore. `start()` avvia un thread daemon per sorgente con questo ciclo, e `stop()` lo interrompe:

```java
while (running) {
    collector.collectDue();
    while (running && clock.instant().isBefore(collector.nextRun()) && collector.recoverOne()) {
        // recupero nel tempo libero
    }
    attendi fino a collector.nextRun();
}
```

Il costruttore pubblico di `IcingaAdapter` riceve anche `IanusProperties` e verifica la `retention`. Il template aggiunge `ianus.retention: PT3H` e, per ogni sorgente, `collect-interval: PT5M` e `collect-delay: PT1M`.

- [ ] **Step 4: Verifica che passino**

Run: `mvn -B -o clean verify`
Expected: tutti i test verdi. Le risposte a IANUS non cambiano ancora.

- [ ] **Step 5: Commit**

```bash
git add config src
git commit -m "Configurazione e avvio dei Collector"
```

---

### Task 5: IanusService legge dall'archivio

**Files:**
- Modify: `core/service/IanusService.java`, `config/CoreConfig.java`, `config/IanusProperties.java`, `core/source/SourceAdapter.java`, `core/model/SourceHealth.java`, `core/buffer/SampleBuffer.java`
- Modify: i quattro `*Adapter.java` e i tre `*Properties.java` con `history-depth`, `PrometheusClient.java`, `config/ianus-template.yml`
- Delete: `core/service/ResultCache.java`, `core/source/SourceCapabilities.java`
- Test: `core/IanusServiceTest.java`, `IanusPluginApplicationTest.java`, `core/JsonSerializationTest.java`, `sources/prometheus/PrometheusAdapterTest.java`

I percorsi sono relativi a `src/main/java/it/infocube/ianus/plugin/` e `src/test/java/it/infocube/ianus/plugin/`.

**Interfaces:**
- Consumes: `Collectors.store(String)`, `BlockStore.between/complete/latest/interval`, `Consolidator.metrics/events`.
- Produces:
  - `IanusService(ObjectProvider<SourceAdapter>, WindowResolver, Collectors, IanusProperties, Clock)`
  - `SourceHealth` aggiunge `@JsonProperty("last_collected") @JsonInclude(NON_NULL) String lastCollected`, mantiene un costruttore a 4 argomenti e offre `withLastCollected(String)`.
  - `IanusProperties(apiKey, pluginVersion, windows, defaultPageSize, maxPageSize, retention)`: `cacheTtl` e `cacheMaxEntries` spariscono.

- [ ] **Step 1: Scrivi i test che falliscono**

In `IanusServiceTest`, costruisci `Collectors` senza avviarlo e riempi l'archivio con `collectors.store("fake").put(...)`. `now` è 10:07:30.

```java
@Test void allBlocksPresentGiveCompleteAnswer() { /* PT30M su sei blocchi completi: complete true, value consolidato */ }
@Test void missingBlockGivesIncompleteAnswerWithData() { /* cinque blocchi su sei: complete false, serie presenti */ }
@Test void incompleteBlockGivesIncompleteAnswer() { /* tutti presenti, uno incompleto: complete false */ }
@Test void noBlockInWindowIs503() { /* SourceUnavailableException */ }
@Test void windowOlderThanRetentionIs400() {
    // BadRequestException con code window_too_old
}
@Test void windowStartingAtRetentionLimitIsServed() {
    // now=13:05, retention PT3H, finestra [10:05, 10:10) con il suo blocco: complete true, nessun errore
}
@Test void requestsNeverCallTheAdapter() { /* FakeAdapter conta metrics/events: zero chiamate */ }
@Test void capabilitiesComeFromLatestBlock() { /* metriche e nodi del blocco, history_depth PT3H */ }
@Test void capabilitiesWithoutBlocksAreEmpty() { /* liste vuote, nessuna eccezione */ }
@Test void healthReportsLastCollected() { /* last_collected pari alla fine dell'ultimo blocco */ }
```

In `JsonSerializationTest`: `last_collected` presente quando valorizzato e assente quando è `null`.

In `IanusPluginApplicationTest`, `unreachableNmsIs503` resta valido.

`nmsCallIsLoggedWithDurationOnlyOnCacheMiss` va tolto da `IanusServiceTest`: il log dei tempi ora sta in `SourceCollector`. In `SourceCollectorTest`, `collectsLastBlockAfterDelay` verifica con `CapturedOutput` che compaia `fake: metrics letti in `.

- [ ] **Step 2: Verifica che falliscano**

Run: `mvn -B -o test -Dtest=IanusServiceTest`
Expected: errore di compilazione, il costruttore di `IanusService` non corrisponde.

- [ ] **Step 3: Passa `IanusService` all'archivio e rimuovi il vecchio percorso**

In `metrics` ed `events`: se `window.start()` precede `now - retention` lancia `new BadRequestException("window_too_old", ...)`; prendi `store.between(...)`; se è vuoto lancia `SourceUnavailableException`; filtra per nodo e metrica prima di consolidare; `complete` è `store.complete(...)`.

Rimuovi `ResultCache` e il suo bean, `SourceAdapter.capabilities()`, `SourceCapabilities`, `historyDepth` da `OpenNmsProperties`, `PrometheusProperties`, `ZabbixProperties`, `cache-ttl` e `history-depth` dal template, `PrometheusClient.labelValues`, `SampleBuffer.nodes/metrics`, `SampleBufferTest` dove li usa, e il test `capabilitiesWithUnreachablePrometheusAreUnavailable`. Aggiorna gli argomenti posizionali nei test delle `Properties`.

- [ ] **Step 4: Verifica che passino**

Run: `mvn -B -o clean verify`
Expected: tutti i test verdi.

- [ ] **Step 5: Prova sul jar**

Avvia il jar con il template compilato e OpenNMS irraggiungibile. Expected: `/ianus/v2/opennms/metrics?window=PT5M` risponde 503, `capabilities` 200 con liste vuote, nel log compaiono i tentativi del Collector.

- [ ] **Step 6: Commit**

```bash
git add -A config src
git commit -m "IanusService risponde solo dai blocchi raccolti"
```

---

### Task 6: Documentazione e misura della memoria

**Files:**
- Modify: `SPECIFICA.md`, `README.md`, `CLAUDE.md`
- Create: `src/test/java/it/infocube/ianus/plugin/core/BlockStoreMemoryTest.java`

- [ ] **Step 1: Misura la memoria**

Test con `@Disabled("misura manuale")`: riempie un `BlockStore` con 36 blocchi da 50.000 serie, chiama `System.gc()` e stampa i MB usati.

Run: togli `@Disabled` in locale, `mvn -B -o test -Dtest=BlockStoreMemoryTest`, poi ripristinalo.
Expected: valore intorno a 200-300 MB. Se supera 500 MB, fermati e segnala il risultato.

- [ ] **Step 2: Aggiorna `SPECIFICA.md`**

- §1 e §3: la frequenza di raccolta è configurazione del plugin; IANUS sceglie la finestra e il plugin risponde con i dati raccolti.
- §3.3: aggiunge le regole di consolidamento del design.
- §7.2: `last_collected` in `health`. §7.4: `window_too_old`.
- §8: riscrive Profondità storica, Chiamate verso l'NMS, Cache; aggiunge che IANUS chiede una finestra almeno `collect-delay` dopo la sua fine.

- [ ] **Step 3: Aggiorna `README.md` e `CLAUDE.md`**

README: nuova sezione sulla raccolta con i parametri, la tabella delle risposte, la memoria misurata e il dimensionamento della JVM; rimuove `cache-ttl` e `history-depth`. CLAUDE.md: il flusso diventa `SourceCollector` → `BlockStore` → `IanusService`, e `ResultCache` sparisce.

- [ ] **Step 4: Verifica**

Run: `mvn -B -o clean verify` e `grep -rn "ResultCache\|cache-ttl\|history-depth" README.md CLAUDE.md config src`
Expected: build verde, grep vuoto.

- [ ] **Step 5: Commit**

```bash
git add SPECIFICA.md README.md CLAUDE.md src
git commit -m "Documenta la raccolta periodica e il consolidamento"
```
