# DESIGN.md — dizajn lokalnog LSM mehanizma (Checkpoint 1)

Ovaj fajl objašnjava **kako** mehanizam radi i **zašto** je tako napravljen: granice modula, redosled koraka, brave, objavljivanje i oporavak (CP1 §31.1). Tačni bajtovi fajlova su u `FORMAT.md`, a odluke tima sa obrazloženjem u `docs/ODLUKE.md`. Kada se ovaj fajl i specifikacija razlikuju, merodavna je specifikacija.

Urednik fajla je Ilija (plan §15); svaki odeljak piše vlasnik dela sistema.

| CP1 §31.1 traži | Odeljak | Piše | Stanje |
|---|---|---|---|
| granice modula | §1 | Mladen | popunjeno |
| linearizacija mutacije i redosled WAL force-a | §2 | Mladen | popunjeno |
| vlasništvo nad bravama i redosled brava | §3 | Mladen | popunjeno |
| MemTable rotacija i backpressure | §4 | Mladen | popunjeno |
| algoritmi čitanja i spajanja skeniranja | §5 | Mladen, Kimi (SSTable) | engine deo popunjen |
| objavljivanje pri flush-u i compaction-u | §6 | Ilija | čeka |
| oporavak i politika oštećenja | §7 | Matej | čeka |
| status i brojači | §8 | Kimi | čeka |
| failpoint-i i arhitektura testova | §9 | Andrija | čeka |
| namerna odstupanja od preporuka | §10 | svi | popunjeno za Mladenov deo |

---

## 1. Granice modula

### 1.1 Maven moduli

| Modul | Sadrži | Ne sme da zna za |
|---|---|---|
| `dlsm-core` | mehanizam: zapisi, WAL, MemTable, SSTable, manifest, održavanje, oporavak, statistika, inspektori | HTTP, Docker, particije, portove (CP1 §2.1) |
| `dlsm-server` | proces: `Main`, HTTP serveri 8080/8082, javni i admin API, `inspect` podkomanda | format fajlova i redosled koraka mehanizma |
| `dlsm-tests` | harness i 33 scenarija | unutrašnjost mehanizma (testovi ga gledaju spolja) |

Mehanizam se konfiguriše isključivo kroz `EngineConfig` i ulaz za oporavak (`RecoveredState`), pa CP2 može da otvori više nezavisnih instanci u istom procesu (plan §19): nema statičkih singletona ni globalnih brava.

### 1.2 Paketi i vlasništvo

Vlasništvo je u planu §4.3. Ukratko: `common` i `engine` (Mladen), `wal` i oporavak (Matej), `sstable`, `stats`, `log` (Kimi), manifest i održavanje (Ilija), `io`, failpoint-i i `dlsm-tests` (Andrija); `dlsm-server` je Mladenov, a admin rute za status i failpoint-e dodaju Kimi i Andrija.

### 1.3 Šavovi između modula

Moduli razgovaraju samo preko malih interfejsa u `rs.raf.dlsm.engine`. Svaki šav ima privremenu implementaciju dok pravi deo ne stigne (plan §11, pravilo stubova).

| Šav | Šta prenosi | Koristi | Implementira | Privremeno (M1) |
|---|---|---|---|---|
| `MutationLog` | jedan WAL segment: `append`, `force`, `segmentId`, `close` | engine | WAL modul | `InMemoryWal` (ne čuva ništa) |
| `WalFactory` | stvaranje sledećeg segmenta po CP1 §16.1 | engine (rotacija) | WAL modul | `InMemoryWal` |
| `TableSource` | jedna objavljena SSTable: `meta`, `get`, `iterator(start)` | engine (GET, SCAN) | SSTable modul (`DiskSSTableTableSource`) | `MemoryTable` |
| `MaintenanceHost` | ono što flush i compaction smeju da traže od mehanizma | održavanje | engine | — |
| `FlushScheduler` | „zamrznuta generacija čeka flush“, provera auto-compaction-a | engine | održavanje | `InMemoryFlusher` |
| `RecoveredState` + `Engine.open` | oporavljeno stanje → spreman mehanizam | oporavak | engine | `RecoveredState.empty` |
| `EngineStatus` | konzistentan snimak polja mehanizma za status | status rute | engine | `NodeStatus` dopunjuje WAL/manifest delove |

Dok traje M1, `Engine.openInMemory` sastavlja mehanizam od privremenih delova: podaci postoje samo u memoriji, a zamrznute generacije se „flush-uju“ u `MemoryTable` kroz isti `MaintenanceHost` tok kao pravi flush.

---

## 2. Put mutacije, linearizacija i redosled WAL force-a

`PUT` i `DELETE` prolaze kroz isti put od deset koraka (CP1 §9). Kod: `Engine.put` / `Engine.delete` → `Engine.mutate`.

| Korak (CP1 §9) | U kodu | Napomena |
|---:|---|---|
| 1. validacija | `requireValidKey`, provera vrednosti u `Engine.put`; pre toga HTTP sloj (`KvRoutes`) | odbijen zahtev ne troši redni broj i ne dira WAL |
| 2. kritična sekcija | `acquire(engineLock.writeLock(), ctx)` | čekanje je ograničeno rokom zahteva → `DEADLINE_EXCEEDED` |
| 3. provera režima | `requireMode`, zatim `awaitRotationSlot` i po potrebi `rotateLocked` | `FAILED` → `NODE_NOT_READY`; backpressure u §4 |
| 4. redni broj | `sequences.allocate()` | dodeljen broj je potrošen i ako mutacija kasnije ne uspe (praznine su dozvoljene) |
| 5–6. kodiranje i dopisivanje | `activeLog.append(record, ctx)` | ceo okvir; kodira WAL modul |
| 7. force | `activeLog.force(ctx)` | **granica trajnosti i tačka linearizacije** (CP1 §9.2) |
| 8. MemTable | `active.apply(record)` | redni brojevi moraju strogo da rastu |
| 9. vidljivost | puštanje write lock-a | čitaoci drže read lock, pa vide zapis tek posle koraka 8 |
| 10. odgovor | `return new MutationResult(sequence)` | |

**Tačka linearizacije** je uspešan povratak iz `activeLog.force`. Pošto ceo put (koraci 3–8) teče pod istim write lock-om, redosled u WAL-u, redosled rednih brojeva i redosled vidljivosti su isti. Nijedan čitalac ne može da vidi zapis pre force-a (I2): čitanje čeka na read lock dok mutacija drži write lock.

**Ishodi otkaza** (CP1 §9.3):

| Gde | Šta mehanizam radi | Odgovor |
|---|---|---|
| rok istekne pre write lock-a ili tokom backpressure-a | ništa nije potrošeno | `504 DEADLINE_EXCEEDED` |
| redni brojevi iscrpljeni | `FAILED` (kategorija `SEQUENCE_EXHAUSTED`) | `500 INTERNAL_ERROR` |
| `append` ili `force` baci `IOException` | `FAILED`; posle neizvesnog završetka WAL-a **ništa se više ne dopisuje** | `500 STORAGE_IO` |
| `apply` padne posle uspešnog force-a | `FAILED` (zapis je trajan, a memorija više ne odgovara WAL-u) | `500 INTERNAL_ERROR` |

Svaki prelazak u `FAILED` čuva **prvu** grešku kao `lastFatalError` i odmah budi pisce koji čekaju (§3, §4). Iz `FAILED` nema povratka bez restarta (CP1 §22).

Za status: `lastAcceptedSequence` je najveći redni broj vidljiv u mehanizmu, a `lastDurableSequence` najveći redni broj nad kojim je force uspeo ili koji je u objavljenoj tabeli (CP1 §24). Oba se čitaju u istom snimku (`Engine.status`).

---

## 3. Brave i redosled preuzimanja

Dizajn je preporučena osnova iz CP1 §13.1: jedna pravedna `ReentrantReadWriteLock` za mehanizam i jedna brava održavanja.

### 3.1 Brave

| Brava | Vrsta | Ko je drži | Šta štiti |
|---|---|---|---|
| `maintenanceLock` | `ReentrantLock` (održavanje) | flush ili compaction posao, ceo posao | serijalizuje održavanje; nikad je ne uzima klijentski zahtev |
| `engineLock` (write) | pravedna `ReentrantReadWriteLock` | `PUT`/`DELETE` (koraci 3–8), rotacija, objava rezultata održavanja (`underEngineWriteLock`) | aktivni par (MemTable, WAL segment), alokator, `EngineView`, prelaze režima |
| `engineLock` (read) | ista | `GET`, ceo `SCAN`, `Engine.status`, `maxSequenceInEngine`, `underEngineReadLock` | stabilan pogled na sve izvore tokom čitanja |
| `rotationSlotFree` | `Condition` vezan za write lock | pisac pod backpressure-om | čekanje na slobodan slot za zamrznutu generaciju |
| `failureLock` | monitor (`synchronized`) | kratko, pri prelazu režima | `mode` i `lastFatalError`; može ga uzeti i čitalac pod read lock-om |

`EngineView` je nepromenljiv i menja se kao celina (`volatile`), pa održavanje sme da ga pročita bez brave (`currentView`, `oldestImmutable`).

### 3.2 Redosled

```text
maintenanceLock  →  engineLock  →  failureLock
```

- Kada su potrebne obe, održavanje uvek uzima `maintenanceLock` pa `engineLock` (kroz `underEngineWriteLock`). Mehanizam nikad ne uzima `maintenanceLock`.
- `failureLock` je list: dok se drži, ne uzima se nijedna druga brava.
- `FlushScheduler.flushNeeded()` se poziva pod write lock-om (korak 4 rotacije), pa sme samo da stavi posao u red i vrati se; ne sme da čeka niti da uzme `maintenanceLock`.
- Čitalac koji otkrije oštećenje drži samo read lock i ne može da ga nadogradi; da bi probudio pisce koji čekaju, `wakeWaitingWriters` pokreće kratku virtuelnu nit koja uzme write lock kada se oslobodi. Pisac drži write lock sve dok ne počne da čeka, pa signal ne može da promaši.

### 3.3 Napredovanje

- Svako čekanje na `engineLock` (`acquire`) i na `rotationSlotFree` ograničeno je rokom iz `OpContext` (monotoni sat), pa nijedan zahtev ne visi zauvek.
- Pravedna brava sprečava da stalan niz čitanja izgladni pisce.
- Pisac koji čeka na backpressure pušta write lock (`awaitNanos`), pa flush može da objavi rezultat i da ga probudi; ovo uklanja očigledan deadlock između backpressure-a i flush-a (CP1 §13.1).

---

## 4. MemTable rotacija i backpressure

### 4.1 Generacije

Mehanizam ima tačno jednu aktivnu MemTable i najviše jednu zamrznutu (CP1 §10). Aktivna MemTable i njen WAL segment čine **par** koji se menja samo zajedno.

- `MemTable` čuva jednu verziju po ključu (`TreeMap` po `Keys::compare`), a tombstone kao pravi zapis.
- Logička veličina je `32 + dužina ključa + dužina vrednosti` (vrednost je 0 za tombstone i praznu vrednost); zamena oduzima stari i dodaje novi zapis.
- `rotationRequired()` postaje tačno kada veličina dostigne `MEMTABLE_MAX_BYTES`. Mutacija koja je prešla prag se normalno završava; sledeća mora da sačeka rotaciju. Jedan zapis veći od praga se prihvata u praznu generaciju i odmah traži rotaciju.

### 4.2 Rotacija (`Engine.rotateLocked`, pod write lock-om)

1. `walFactory.createSegment` — nov segment je trajno napravljen pre nego što primi ijedan zapis. Ako ne uspe, stari par ostaje važeći i mehanizam ostaje `SERVING` (nijedan bajt nije dopisan iza nesigurnog kraja).
2. `active.freeze(List.of(activeLog.segmentId()))` — stara generacija postaje nepromenljiva zajedno sa ID-jem segmenta koji je štiti.
3. Zamena para i pogleda u jednoj izmeni: nova prazna MemTable, novi segment, nov `EngineView` sa zamrznutom generacijom. Ovo je tačka linearizacije rotacije; stari segment se zatvara.
4. `flushScheduler.flushNeeded()`.

Rotacija se pokreće automatski (u koraku 3 puta mutacije, kad je aktivna puna i slot slobodan) ili ručno, za ručni flush (`rotateIfActiveContainsUpTo`, CP1 §23.1).

### 4.3 Backpressure

Kad je aktivna generacija puna, a zamrznuta još čeka flush (`slotBlocked()`), nova mutacija ne sme da napravi drugu zamrznutu generaciju niti da odbaci upis (CP1 §10):

- pisac čeka na `rotationSlotFree.awaitNanos(preostaliRok)`, puštajući write lock; ne troši redni broj i ne dira WAL;
- čitanja za to vreme rade normalno;
- `installFlushResult` oslobađa slot i budi sve pisce; prvi rotira punu generaciju, ostali nastavljaju u novoj;
- prelaz u `FAILED` budi pisce, koji odmah dobijaju `NODE_NOT_READY`;
- istek roka daje `DEADLINE_EXCEEDED`.

Režim je `BACKPRESSURED` čim je aktivna puna dok je slot zauzet, čak i ako niko još ne čeka, i vraća se u `SERVING` posle flush-a (`updatePressureLocked`, CP1 §22). Oba režima su lifecycle `READY`. Status prijavljuje `writeBackpressure` i `backpressuredWriters`.

---

## 5. Čitanje: GET i SCAN

Izvori, od najnovijeg ka najstarijem po prirodi (ali ne po pravilu odlučivanja): aktivna MemTable, zamrznuta MemTable (ako postoji), objavljene tabele iz `EngineView`. **Pobednik je uvek zapis sa najvećim rednim brojem**, bez obzira na izvor ili ID tabele (I3).

### 5.1 GET (`Engine.get`, read lock)

1. Kandidat iz aktivne, pa iz zamrznute generacije.
2. Za svaku tabelu čiji opseg `[minKey, maxKey]` može da sadrži ključ: `table.get(key)` (Bloom, retki indeks, jedno čitanje bloka — §5.3).
3. Najveći redni broj pobeđuje; tombstone ili odsustvo daju `NOT_FOUND`.

Greška tabele (oštećen blok, nestao fajl) **nije odsustvo** (I7): mehanizam prelazi u `FAILED` i vraća grešku, nikad `NOT_FOUND`.

### 5.2 SCAN (`Engine.scan`, read lock tokom celog skeniranja)

Read lock se drži do kraja, pa je skeniranje jedan stabilan snimak (CP1 §12 korak 1); konkurentne mutacije čekaju, što je dozvoljeno.

1. Izvori se pozicioniraju na `start`: MemTable kroz `tailMap`, tabele kroz retki indeks; uzimaju se samo tabele čiji se opseg preklapa sa `[start, end)`.
2. `MergingIterator` spaja izvore min-heap-om sa najviše jednim zapisom po izvoru, **grupiše sve verzije istog ključa** i daje jednu — onu sa najvećim rednim brojem, uključujući tombstone (compaction koristi isto spajanje i zadržava tombstone, I8). Izvori se pomeraju lenjo, tek kad zatreba sledeći ključ, i ne čitaju se posle `end`.
3. `LiveScan` tek onda izbacuje tombstone i broji samo žive ključeve do `limit`; čitanje staje čim je `limit` dostignut.

Svojstva iz CP1 §12: rezultat je strogo rastući po ključu, bez duplikata, bez obrisanih ključeva, unutar `[start, end)`, a `end` se nikad ne vraća.

### 5.3 Put čitanja kroz SSTable

Engine od tabele traži samo ugovor `TableSource`: `get` vraća kandidat zapis, a iterator kreće od zadatog ključa. Implementacija za disk je `DiskSSTableTableSource`. Redosled jednog `get(key)` poziva:

1. **Provera opsega.** Ako je `key` izvan `[minKey, maxKey]` iz manifesta, vraća se odsustvo bez ikakvog čitanja sa diska.
2. **Bloom filter.** Ako filter kaže da ključa sigurno nema, brojač `bloomNegativeSkips` se uvećava i vraća se odsustvo bez čitanja bloka. Ako kaže da ključ **može** biti, uvećava se `bloomPositiveProbes`. Bloom nikad ne daje lažno negativan rezultat (CP1 §17.5).
3. **Retki indeks.** Binarna pretraga po prvim ključevima blokova bira poslednji blok čiji je prvi ključ ≤ traženog. Nijedan blok pre izabranog se ne čita.
4. **Jedno čitanje bloka.** Čita se tačno jedan blok (najviše dva za pretragu, CP1 §11.2). Uvećavaju se `sstableDataBlocksRead` i `sstableDataBytesRead`, a blok se dekodira uz proveru CRC32C.
5. **Pretraga unutar bloka.** Zapis sa tačnim ključem se vraća kao kandidat, i to i kad je tombstone. Engine tek tada odlučuje da li je ključ obrisan (§5.1).

**Oštećenje nije odsustvo.** Ako je blok neispravan (CRC, dužine, redosled) ili fajl nedostaje, poziv baca `StorageException` sa kategorijom (`DATA_BLOCK`, `INDEX`, `BLOOM`, `FOOTER`, `TRUNCATED_REGION`). Nikad se ne vraća prazan rezultat, jer bi to bilo tiho gubljenje podataka (I7).

**Skeniranje** (`iterator`) ne konsultuje Bloom filter, jer ključevi intervala ne moraju postojati. Pozicionira se kroz retki indeks i čita blokove redom. Svaki blok koji čita iteratoru uvećava `rangeScanBlocksRead` (u tom brojaču su i `sstableDataBlocksRead` i `sstableDataBytesRead`).

Validacija cele tabele pri pokretanju (`SSTableValidator.validateFully`) je odvojen put: strimuje ceo fajl i ne koristi Bloom ni indeks za pojedinačne ključeve (§7).
---

## 6. Objavljivanje pri flush-u i compaction-u

> **Piše Ilija** (CP1 §19, §20, §23).

Engine strana je ugovor `MaintenanceHost` (javadoc u kodu sadrži redosled koraka):

- flush uzima `oldestImmutable()`, piše tabelu **van** engine brave, pa u `underEngineWriteLock` objavljuje manifest i poziva `installFlushResult(generacija, tabela)`, koji u jednoj zameni pogleda povlači zamrznutu generaciju i dodaje tabelu, pa budi pisce;
- compaction bira ulaze iz `currentView()`, spaja ih `MergingIterator`-om van brave, pa u `underEngineWriteLock` proverava da su ulazi i dalje objavljeni, objavljuje manifest i poziva `installCompactionResult(ulazi, izlaz)`;
- pošto čitanja drže read lock, posle povratka iz objave nijedan čitalac ne koristi stare ulaze i njihovi fajlovi smeju da se obrišu;
- greška posle preimenovanja manifesta prijavljuje se kroz `reportFatalError` (CP1 §26.1).

Testovi `EngineMaintenanceTest` i `EngineDiskMaintenanceTest` pokazuju tačan redosled poziva.

---

## 7. Oporavak i politika oštećenja

> **Piše Matej** (CP1 §14, §16.4, §21).

Engine strana: na kraju oporavka `Engine.open(config, RecoveredState, maintenance)` dobija `lastUsedSequence`, oporavljenu generaciju, tabele iz manifesta i nov aktivni segment; odbija stanje koje bi ponovo upotrebilo redni broj ili WAL segment, vraća spreman mehanizam, odmah traži flush oporavljene generacije i proveru auto-compaction-a.

Za vreme rada: oštećenje ili I/O greška na objavljenoj tabeli vodi u `FAILED`; posle toga se odbijaju i mutacije i čitanja (§10).

---

## 8. Status i brojači

> **Piše Kimi** (CP1 §24).

**Brojači.** Jedan `StorageStats` za ceo čvor (`AtomicLong` po brojaču, sa tačnim imenima iz CP1 §24). Moduli samo uvećavaju brojače, a nijedan ih ne resetuje sam. Grupe:

- WAL: `walAppendAttempts`, `walAppendsCompleted`, `walForceAttempts`, `walForcesSucceeded`, `walBytesWritten`, `walRecordsReplayed`, `walTornTailsTruncated`;
- čišćenje pri oporavku: `orphanFilesRemoved`, `temporaryFilesRemoved`;
- održavanje: `flushAttempts`, `flushPublications`, `compactionAttempts`, `compactionPublications`;
- SSTable čitanje: `sstableDataBlocksRead`, `sstableDataBytesRead`, `bloomNegativeSkips`, `bloomPositiveProbes`, `rangeScanBlocksRead`;
- `resetEpoch`: broj resetovanja. Posle `POST /admin/v1/storage/stats/reset` brojači počinju od nule, a epoha se uvećava.

**Endpoint-i** (port 8082):

- `GET /admin/v1/storage/status`: pun pogled iz `StatusBuilder`: `protocolVersion`, `nodeId`, `lifecycle`, `engineMode`, `writeBackpressure`, `backpressuredWriters`, `lastFatalError`, `nextSequence`, `activeMemtable`, `immutableMemtable`, niz `walSegments` (`segmentId`, `role`, `minSequence`, `maxSequence`, `sizeBytes`), objekat `manifest` (`generation`, `nextTableId`, `flushedThroughSequence`, `sstableCount`), niz `sstables` (sortiran po ID-ju), objekat `maintenance` (`operation`, `phase`, `targetSequence`, `inputTableIds`) i objekat `stats` sa svim brojačima.
- `GET /admin/v1/status`: standalone pogled sa `standaloneStorage` (gore), `partitions:[{partitionId:0, role:"PRIMARY", epoch:0, ...}]`, `metadataRevision:0` i `metadataLeaderId:null`. Vrednosti su fiksne jer u CP1 nema particija ni Raft-a.
- `POST /admin/v1/storage/stats/reset`: resetuje brojače i vraća novu `resetEpoch`.

**Konzistentnost snimka.** `Engine.status(ctx)` pravi jedan snimak pod read lock-om, pa polja iz istog odgovora pripadaju istom trenutku. Radi i u stanju `FAILED`, da bi se greška mogla videti. `NodeStatus` uzima snimak jednom po zahtevu i dopunjuje ga delovima drugih modula.
---

## 9. Failpoint-i i arhitektura testova

> **Piše Andrija** (CP1 §26, §28).

Mesta failpoint-a u mehanizmu su obeležena komentarom `// failpoint: <ime>` u `Engine`: `wal.after-sync.before-memtable` i `memtable.after-apply.before-response` (`mutate`), `rotation.after-new-wal-sync.before-switch` i `rotation.after-switch.before-flush` (`rotateLocked`), `scan.after-snapshot.before-merge` (`scan`).

---

## 10. Namerna odstupanja i izbori tima

Usvojene preporučene osnove: brave iz CP1 §13.1, serijalizovano održavanje, WAL raspored iz §16.3.1, bez group commit-a (§9.4).

Izbori koji se tiču mehanizma i API-ja (obrazloženja u `docs/ODLUKE.md`):

| Izbor | Gde je zapisan |
|---|---|
| Posle `FAILED` odbijaju se i čitanja (`503 NODE_NOT_READY`) — TEAM CHOICE iz CP1 §9.3 | `ODLUKE.md`, red o čitanjima posle `FAILED` |
| `minSequence`/`maxSequence` generacije su opseg **primenjenih** mutacija | `ODLUKE.md`, red o MemTable opsegu |
| `retryable` u omotaču greške: `true` samo za `NODE_NOT_READY` i `DEADLINE_EXCEEDED` | `ODLUKE.md`, red o `retryable` |
| HTTP: JDK `HttpServer`, `X-Request-Id` obavezan na 8080, telo ≤ 512 KiB, 404/405 za rute | `ODLUKE.md`, HTTP redovi |
| ugovor engine ↔ održavanje i ulaz za oporavak (razlike od skice u planu §5.1) | `ODLUKE.md`, redovi o `MaintenanceHost` i `Engine.open` |
| oporavljena generacija se flush-uje **posle** `READY` | `ODLUKE.md`, red o `Engine.open` |

Privremeno, samo do isporuke pravih delova (M1): `InMemoryWal`, `InMemoryFlusher`/`MemoryTable` i privremeni delovi statusa u `NodeStatus` (WAL segmenti bez veličine, manifest izveden iz tabela, održavanje uvek `IDLE`).
