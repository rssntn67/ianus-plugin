# Collector e consolidamento della cache

Data: 5 novembre 2026. Stato: design da approvare.

## Obiettivo

Oggi ogni richiesta di IANUS può arrivare fino all'NMS: la cache tiene solo le risposte già chieste, per 10 minuti.
Con questo design ogni adapter raccoglie i dati dal proprio NMS a intervalli configurabili, e il plugin risponde a
IANUS solo con i dati raccolti, consolidandoli sulla finestra richiesta.

Esempio: OpenNMS viene letto ogni 5 minuti. Se IANUS chiede `PT30M`, il plugin consolida i sei blocchi da 5 minuti
della finestra, senza interrogare OpenNMS.

## Requisiti

- Ogni sorgente ha un proprio intervallo di raccolta configurabile.
- Le richieste di IANUS non interrogano mai gli NMS, salvo il controllo di raggiungibilità di `/ianus/v2/health`.
- La storia conservata è configurabile, con default 3 ore.
- Il dimensionamento di riferimento è di decine di migliaia di serie per installazione.
- Dopo un riavvio o un'interruzione i dati mancanti si recuperano da soli, finché l'NMS li conserva.

## Fuori perimetro

- Persistenza su disco: la storia è in memoria e riparte vuota a ogni riavvio.
- Parallelismo delle letture dentro una sorgente: si valuta dopo aver misurato la durata del ciclo su un NMS reale.
- Ricaricamento a caldo della configurazione.

## Configurazione

```yaml
ianus:
  retention: PT3H              # sostituisce cache-ttl e cache-max-entries
  sources:
    opennms:
      collect-interval: PT5M   # ampiezza dei blocchi e frequenza di raccolta
      collect-delay: PT1M      # attesa dopo la fine del blocco, perche' l'NMS abbia i dati
```

- `collect-interval` e `collect-delay` si leggono per ogni sorgente con una classe `CollectorProperties`, comune a
  tutte le sorgenti. Default: `PT5M` e `PT1M`.
- All'avvio il plugin si rifiuta di partire se `collect-interval` non divide esattamente ogni finestra supportata, o
  se `retention` è minore della finestra più ampia.
- `history-depth` scompare dalle sorgenti: la profondità storica è la `retention`.
- Per Icinga, `ianus.sources.icinga.retention` deve essere almeno pari a `ianus.retention`, altrimenti il plugin non
  parte.

## Componenti

### Blocco

Risultato di una lettura su `[start, end)` di ampiezza `collect-interval`, allineato a multipli della sua ampiezza.
Contiene le serie, i gruppi di eventi e il flag `complete` riportato dall'adapter.

### BlockStore

Un archivio per sorgente, in `core/collector`.

- Conserva i blocchi per istante di inizio ed elimina quelli oltre la `retention`.
- Ogni identità di serie (`node`, `metric`, `instance`, `unit`, `labels`) è memorizzata una sola volta per sorgente: i
  blocchi tengono solo il riferimento e i numeri. Le identità non più usate da alcun blocco vengono eliminate.
- Sostituisce un blocco quando ne arriva una versione completa.

Stima: 50.000 serie per 36 blocchi sono circa 1,8 milioni di voci, tra 200 e 300 MB. Il valore va confermato con una
misura, e il README indicherà come dimensionare la memoria della JVM.

### Collector

Un thread per sorgente, così un NMS lento non blocca gli altri (sezione 8 della specifica). Il tempo arriva dal `Clock`
iniettato, per poter provare la logica senza attese reali.

Ciclo:

1. All'istante `fine del blocco + collect-delay` chiama `adapter.metrics(blocco, QueryFilter.NONE)` e
   `adapter.events(blocco, QueryFilter.NONE)`.
2. Se entrambe riescono, salva il blocco. Il blocco è completo solo se lo sono entrambe.
3. Se una fallisce con `SourceUnavailableException`, non salva nulla: il blocco resta mancante.
4. Registra nel log la durata del ciclo, con un avviso se supera `collect-interval`. Un ciclo in ritardo non si
   sovrappone al successivo: il blocco saltato passa al recupero.

Recupero, nel tempo libero fino al ciclo successivo:

- Legge i blocchi mancanti o incompleti dentro la `retention`, dal più recente al più vecchio.
- Si ferma al primo errore di NMS non raggiungibile e riprende dopo il ciclo successivo.
- Ogni blocco incompleto viene riletto al massimo 3 volte, per non interrogare all'infinito un dato che non arriverà.

Gli adapter non cambiano per questo: Icinga legge dal proprio buffer, quindi i blocchi delle interruzioni restano
incompleti o mancanti.

### Consolidatore

In `core/aggregation`. Dati i blocchi di una finestra, produce le serie e i gruppi di eventi.

| Campo | Regola |
| --- | --- |
| `value` | Media dei `value` dei blocchi pesata con `samples` |
| `min`, `max` | Minimo dei `min`, massimo dei `max` |
| `samples` | Somma |
| `count` | Somma, tramite `EventAggregator.merge` |
| `first_seen`, `last_seen` | Il primo e l'ultimo |
| `message`, `labels`, `native_severity` degli eventi | Dall'occorrenza più recente |

Una serie presente in più blocchi è identificata da `node`, `metric`, `instance` e `labels`. I blocchi con `samples: 0`
non pesano sui valori. Se nessun blocco ha campioni, la serie esce con `value`, `min`, `max` a `null` e `samples: 0`.

Il risultato coincide con il calcolo sui campioni originali, anche per i contatori, perché i tassi si comportano come
campioni.

### IanusService

Legge solo dal `BlockStore`, poi applica filtri, ordinamento, paginazione e busta come oggi. `ResultCache` viene
eliminata.

## Risposte a IANUS

| Situazione | Risposta |
| --- | --- |
| Tutti i blocchi presenti e completi | 200, `complete: true` |
| Alcuni blocchi mancanti o incompleti | 200, `complete: false`, con i dati dei blocchi presenti |
| Nessun blocco, finestra dentro la `retention` | 503 `source_unavailable` |
| Inizio della finestra più vecchio della `retention` | 400 `window_too_old` |

- `capabilities` risponde dall'archivio: metriche e nodi dell'ultimo blocco, `history_depth` pari alla `retention`.
  Con NMS giù risponde 200 con gli ultimi dati noti, e con liste vuote se non c'è ancora nessun blocco.
- `SourceAdapter.capabilities()` non serve più e viene rimosso, insieme a `SourceCapabilities`.
- `health` continua a interrogare gli NMS e aggiunge per ogni sorgente `last_collected`, la fine dell'ultimo blocco
  raccolto. Il campo è assente se non c'è ancora nessun blocco.
- IANUS deve chiedere una finestra almeno `collect-delay` dopo la sua fine. Prima di allora l'ultimo blocco non è ancora
  raccolto: la risposta è `complete: false`, oppure 503 se è l'unico blocco della finestra.

## Rischi

- **Durata del ciclo su OpenNMS.** Con `batch-size` 50 e 50.000 serie sono circa 1.000 chiamate in sequenza per ciclo.
  A 200 ms l'una il ciclo dura più di 3 minuti su 5. Va misurato su un OpenNMS reale; le contromisure sono alzare
  `batch-size` o aggiungere il parallelismo.
- **Recupero dopo un riavvio.** Rileggere 36 blocchi costa 36 cicli. Avviene solo nel tempo libero, quindi su sorgenti
  lente può richiedere molto tempo.
- **Dati in ritardo.** Quelli arrivati all'NMS dopo la lettura del blocco non vengono visti, salvo che il blocco sia
  incompleto. La sezione 8 della specifica lo ammette; `collect-delay` serve a ridurre il problema.

## Modifiche alla specifica

- **Sezione 1 e sezione 3:** la frequenza di raccolta diventa configurazione del plugin. IANUS sceglie la finestra, il
  plugin risponde con i dati raccolti.
- **Sezione 3.3:** aggiunge il consolidamento dei blocchi.
- **Sezione 7.2 e 7.4:** `last_collected` in `health`, errore `window_too_old`.
- **Sezione 8:** riscrive Profondità storica, Chiamate verso l'NMS e Cache, e aggiunge il momento in cui IANUS deve
  chiedere una finestra.

## Test

- **Consolidatore:** valori calcolati a mano per gauge, tassi, blocchi con `samples: 0`, serie presente solo in alcuni
  blocchi ed eventi.
- **BlockStore:** eliminazione oltre la `retention`, sostituzione con il blocco completo, identità condivise ed
  eliminate.
- **Collector:** con adapter finto e `Clock` controllato. Coprono ciclo regolare, `collect-delay`, NMS giù, recupero dal
  blocco più recente, limite di 3 riletture, ciclo in ritardo.
- **IanusService:** i quattro casi della tabella delle risposte, `capabilities`, `health`.
- **Avvio:** rifiuto delle configurazioni non valide.
- **Memoria:** una prova con 50.000 serie per confermare la stima.
