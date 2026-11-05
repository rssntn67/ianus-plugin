# IANUS Plugin 2.0

Strato di integrazione tra i sistemi di Network Management (NMS) e l'appliance IANUS.
Implementa il contratto descritto in [SPECIFICA.md](SPECIFICA.md) (*IANUS – Architettura di Integrazione e Modello Dati v2.0*):

- IANUS chiede una **finestra temporale** (`window=PT5M` o `PT30M`), allineata a multipli della sua ampiezza;
- il plugin risponde con **dati sintetici**: per ogni serie `value` (media), `min`, `max`, `samples`;
  per gli eventi, gruppi con `count`, `first_seen`, `last_seen`;
- severità normalizzate su **INFO / WARNING / ERROR / CRITICAL**, con la severità nativa conservata;
- accesso protetto da **API key** nell'header `X-API-Key`.

Sorgenti supportate: **OpenNMS**, **Prometheus**, **Icinga 2**, **Zabbix**.

## Requisiti

- Java 21
- Maven 3.9+
- Spring Boot 4.0 (gestito dal `pom.xml`)

## Configurazione obbligatoria

**Il plugin si avvia solo con un file di configurazione esterno.** Se il file manca, l'avvio fallisce
con `APPLICATION FAILED TO START`.

[`config/ianus-template.yml`](config/ianus-template.yml) è **solo un template**: non è incluso nel jar
e non viene mai letto dal plugin. Va copiato e compilato per ogni installazione:

```bash
cp config/ianus-template.yml /etc/ianus/ianus.yml
vi /etc/ianus/ianus.yml      # API key, sorgenti abilitate, URL, credenziali
```

Il percorso del file si indica in uno di questi modi, in ordine di precedenza:

| Modo | Esempio |
| --- | --- |
| Argomento | `--ianus.config=/percorso/ianus.yml` |
| Variabile d'ambiente | `IANUS_CONFIG=/percorso/ianus.yml` |
| Percorso predefinito | `/etc/ianus/ianus.yml` |

Nel file si possono usare riferimenti a variabili d'ambiente, come `${OPENNMS_PASSWORD:}` nel template.
`ianus.api-key` è obbligatoria e le sorgenti si abilitano con `ianus.sources.<sorgente>.enabled: true`.
I valori del file si possono sovrascrivere con variabili d'ambiente
(`IANUS_SOURCES_OPENNMS_ENABLED=true`, `IANUS_SOURCES_OPENNMS_URL=...`).

Le credenziali scritte nel file sono **in chiaro**: limitare i permessi del file (`chmod 600`) e non
versionarlo. `ianus.yml` è già escluso dal `.gitignore`.

## Compilazione e avvio

```bash
mvn clean verify
java -jar target/ianus-plugin-2.0.0-SNAPSHOT.jar --ianus.config=/etc/ianus/ianus.yml
```

Immagine container: il file si monta sul percorso predefinito.

```bash
mvn clean package
docker build -t ianus-plugin:2.0.0 .
docker run -v /etc/ianus/ianus.yml:/etc/ianus/ianus.yml:ro -p 8080:8080 ianus-plugin:2.0.0
```

## Endpoint

| Metodo e percorso | Risposta |
| --- | --- |
| `GET /ianus/v2/{source}/metrics` | Media, minimo e massimo di ogni serie nella finestra, paginato |
| `GET /ianus/v2/{source}/events` | Eventi raggruppati per nodo, tipo, istanza e severità |
| `GET /ianus/v2/{source}/capabilities` | Finestre supportate, metriche, nodi, profondità storica |
| `GET /ianus/v2/health` | Stato del plugin e raggiungibilità di ogni NMS |
| `GET /v3/api-docs` | Specifica OpenAPI 3 generata dal codice (non richiede API key) |
| `GET /swagger-ui.html` | Interfaccia Swagger UI |

`{source}` vale `opennms`, `prometheus`, `icinga`, `zabbix`.
Parametri: `window` (obbligatorio), `end` (RFC 3339 UTC, allineato), `node` e `metric` (ripetibili),
`page` (da 0), `size` (default 500, massimo 5000).

```bash
curl -H "X-API-Key: $IANUS_API_KEY" \
  "http://localhost:8080/ianus/v2/opennms/metrics?window=PT5M"

curl -H "X-API-Key: $IANUS_API_KEY" \
  "http://localhost:8080/ianus/v2/zabbix/events?window=PT30M&end=2026-10-14T10:00:00Z"
```

Errori: `400` finestra non supportata / `end` non allineato o futuro / paginazione errata,
`401` API key assente o errata, `404` sorgente non configurata, `503` NMS non raggiungibile.
Il corpo contiene sempre `code` e `message`. `complete: false` indica una risposta parziale.

## Come lavora ogni sorgente

### OpenNMS
- **Metriche**: Measurements API (`POST /rest/measurements`) sugli attributi dei grafici dei nodi
  selezionati (`nodes`, vuoto = tutti). Nodi, risorse e attributi sono riletti ogni `discovery-refresh`.
- `sample-step` deve corrispondere all'intervallo di raccolta (default 5 minuti).
- I DS di tipo COUNTER sono già restituiti come tassi da OpenNMS, quindi si aggregano come gauge.
- OpenNMS non espone l'unità di misura: si configura per attributo in `units` (altrimenti `unknown`).
- **Eventi**: `GET /api/v2/events` con filtro FIQL su `eventTime`.

### Prometheus
- L'aggregazione è fatta in PromQL, valutata a `end − 1 ms` per coincidere con `[start, end)`:
  - gauge: `avg/min/max/count_over_time(selettore[W])`;
  - counter: `avg/min/max/count_over_time(rate(selettore[R])[W:S])`.
- Un selettore composto (es. `100 - avg by (instance) (...)`) viene valutato come subquery.
- Una serie senza campioni nella finestra resta nella risposta con valori `null` e `samples: 0` se ha
  campioni nel periodo `series-lookback` (default 1 ora) prima della fine della finestra; oltre quel
  periodo sparisce. Costa una query in più per metrica.
- Le metriche esposte sono quelle elencate in `metrics` (nome IANUS, selettore, tipo, unità, etichette di istanza).
- **Eventi**: serie `ALERTS{alertstate="firing"}` campionata ogni `alert-step`; ogni periodo di attivazione conta come un'occorrenza.

### Icinga 2
- L'API REST di Icinga restituisce solo l'ultimo risultato di ogni controllo. Il plugin interroga
  `/v1/objects/hosts` e `/v1/objects/services` ogni `poll-interval` e conserva i campioni in memoria per `retention`.
- **Metriche**: performance data dei controlli (`metric` = etichetta, `instance` = servizio),
  con conversione delle unità (ms → seconds, KB → bytes, `c` → contatore).
- **Eventi**: cambi di stato di host e servizi (`last_state_change`).
- Dopo un riavvio il buffer riparte vuoto: le finestre precedenti all'avvio risultano `complete: false`.
- Se Icinga smette di rispondere, le finestre toccate dall'interruzione risultano `complete: false`;
  quelle interamente dentro l'interruzione rispondono `503`. Una sola interrogazione fallita basta a
  segnalarlo. Le interruzioni più vecchie di `retention` non vengono più ricordate.
- Utente API consigliato in Icinga (sola lettura):
  ```
  object ApiUser "ianus" {
    password = "..."
    permissions = [ "objects/query/Host", "objects/query/Service", "status/query" ]
  }
  ```

### Zabbix
- **Metriche**: item numerici (`value_type` 0 e 3) letti con `history.get` nella finestra.
  `metric` = chiave senza parametri (`system.cpu.util` → `system_cpu_util`), `instance` = parametri della chiave.
- Gli item con preprocessing "Change per second" sono già tassi.
- **Eventi**: `event.get` sui trigger (problemi e risoluzioni); `event_id` = `zabbix.trigger.<id>`.
- Autenticazione: API token di un utente in sola lettura; `auth-mode: header` (Zabbix 6.4+) oppure `param`.

## Struttura del codice

```
it.infocube.ianus.plugin
├── config            impostazioni generali e bean comuni
├── core
│   ├── model         oggetti JSON del contratto v2
│   ├── window        finestre allineate e parametri window/end
│   ├── aggregation   media/min/max, tassi dei contatori, raggruppamento eventi
│   ├── severity      mappatura delle severità (sezione 6.1)
│   ├── buffer        buffer in memoria per sorgenti senza storico
│   ├── source        interfaccia SourceAdapter
│   └── service       busta, filtri, paginazione, cache
├── web               controller REST, filtro API key, gestione errori
└── sources           opennms, prometheus, icinga, zabbix
```

Per aggiungere una sorgente: implementare `SourceAdapter` come `@Component` condizionato a
`ianus.sources.<nome>.enabled`, con le proprie `Properties` e il proprio client.
