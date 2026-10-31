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
| objavljivanje pri flush-u i compaction-u | §6 | Ilija | popunjeno |
| oporavak i politika oštećenja | §7 | Matej | popunjeno |
| status i brojači | §8 | Kimi | popunjeno |
| failpoint-i i arhitektura testova | §9 | Andrija | čeka |
| namerna odstupanja od preporuka | §10 | svi | popunjeno za Mladenov, Matejev i Ilijin deo |

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

Vlasništvo je u planu §4.3. Ukratko: `common` i `engine` (Mladen), `wal` i oporavak (Matej), `sstable`, `stats`, `log` (Kimi), manifest i održavanje (Ilija), `io`, failpoint-i i `dlsm-tests` (Andrija); `dlsm-server` je Mladenov; admin rute za status (`StorageStatusRoutes`) su Kimijeve, za flush i compaction (`MaintenanceRoutes`) Ilijine, a failpoint API (`FailpointRoutes`) pripada failpoint okviru (Andrija).

### 1.3 Šavovi između modula

Moduli razgovaraju samo preko malih interfejsa u `rs.raf.dlsm.engine`. Svaki šav je u početku imao privremenu implementaciju dok pravi deo nije stigao (plan §11, pravilo stubova); privremene su ostale samo za unit testove mehanizma.

| Šav | Šta prenosi | Koristi | Prava implementacija | Samo u unit testovima |
|---|---|---|---|---|
| `MutationLog` | jedan WAL segment: `append`, `force`, `segmentId`, `close` | engine | `WalSegment` (WAL modul) | `InMemoryWal` |
| `WalFactory` | stvaranje sledećeg segmenta po CP1 §16.1 | engine (rotacija) | `WalSegments` | `InMemoryWal` |
| `TableSource` | jedna objavljena SSTable: `meta`, `get`, `iterator(start)` | engine (GET, SCAN) | `DiskSSTableTableSource` (SSTable modul) | `MemoryTable` |
| `MaintenanceHost` | ono što flush i compaction smeju da traže od mehanizma | održavanje | engine | — |
| `FlushScheduler` | „zamrznuta generacija čeka flush“, provera auto-compaction-a | engine | `MaintenanceWorker` (održavanje) | `InMemoryFlusher`, `FlushScheduler.NONE` |
| `RecoveredState` + `Engine.open` | oporavljeno stanje → spreman mehanizam | oporavak | `RecoveryProcedure` | `RecoveredState.empty` |
| `EngineStatus` | konzistentan snimak polja mehanizma za status | status rute | engine; `NodeStatus` ga spaja sa WAL katalogom, manifestom i stanjem održavanja | — |

Čvor (`StandaloneNode`) uvek otvara pravi mehanizam: `RecoveryProcedure` nad `DLSM_DATA_DIR`, sa `MaintenanceWorker`-om koji izvršava flush i compaction (§6, §7). `Engine.openInMemory` sastavlja mehanizam od privremenih delova i koristi se samo u unit testovima mehanizma.

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

**Failpoint-i na putu mutacije** (CP1 §26.1, `Engine.failpoint`): `wal.after-sync.before-memtable` je posle uspešnog force-a, a pre koraka 8; `memtable.after-apply.before-response` je posle koraka 8, a pre odgovora. Oba su pod write lock-om, pa `PAUSE` na njima zadržava i sledeće pisce i čitaoce (CP1-API-004). Ubrizgane greške (`docs/ODLUKE.md`, redovi od 20.10. i 22.10.2026.):

| Akcija | `wal.after-sync.before-memtable` | ostale tačke mehanizma |
|---|---|---|
| `IO_ERROR` (nije u katalogu za ove tačke) | `FAILED`, `500 INTERNAL_ERROR` | `FAILED`, `500 INTERNAL_ERROR` |
| `RETURN_ERROR` | `FAILED`: zapis je trajan, a nije primenjen | samo ta operacija dobija `500 INTERNAL_ERROR` sa kategorijom iz `errorCode`; mehanizam radi dalje |
| istekao `PAUSE` | `FAILED` | samo ta operacija dobija `504 DEADLINE_EXCEEDED` |

Za status: `lastAcceptedSequence` je najveći redni broj vidljiv u mehanizmu, a `lastDurableSequence` najveći redni broj nad kojim je force uspeo ili koji je u objavljenoj tabeli (CP1 §24). Oba se čitaju u istom snimku (`Engine.status`).

---

## 3. Brave i redosled preuzimanja

Dizajn je preporučena osnova iz CP1 §13.1: jedna pravedna `ReentrantReadWriteLock` za mehanizam i jedna brava održavanja.

### 3.1 Brave

| Brava | Vrsta | Ko je drži | Šta štiti |
|---|---|---|---|
| `maintenanceLock` | jedna nit `MaintenanceWorker`-a (jednonitni `ExecutorService`, §6.2) | flush ili compaction posao, ceo posao | serijalizuje održavanje; nikad je ne uzima klijentski zahtev |
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

Failpoint `rotation.after-new-wal-sync.before-switch` je između koraka 1 i 2: kod `RETURN_ERROR` se nov segment zatvara, a stari par ostaje važeći. `rotation.after-switch.before-flush` je između koraka 3 i 4: zamena je već izvršena, pa se i posle ubrizgane greške flush ipak traži, da zamrznuta generacija ne bi zauvek držala slot. Mutacija koja je pokrenula rotaciju još nema redni broj, pa posle pada na ovim tačkama mora biti odsutna (`docs/odbrana/dodatak-a-mehanizam.md`).

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

> **Piše Ilija** (CP1 §18–§20, §23, §26.1). Kod je u paketima `manifest` i `maintenance`; format manifesta je u `FORMAT.md` §5.

Jedna rečenica nosi ceo odeljak: **finalizacija čini fajl kompletnim, a tek zamena manifesta čini ga delom baze** (I4). Zato flush i compaction imaju isti oblik: izlaz se piše i trajno finalizuje van engine brave, a jedina tačka u kojoj se stanje baze menja je `ManifestManager.publish` pod engine write lock-om. Ništa se ne briše pre nego što je ta objava trajna.

### 6.1 Manifest i atomska objava (`ManifestManager`)

| CP1 §18.1 | Kod | Šta je bitno |
|---|---|---|
| 1 | `publish(base -> …)` | izmena se računa nad **trenutnim** manifestom u memoriji (rebase), generacija je tačno `+1`; kandidat prolazi `ManifestValidator` i proveru da `nextTableId` i `flushedThroughSequence` ne opadaju, pa se neispravan kandidat odbija pre ikakvog upisa |
| 1–2 | `write` | zaostali `tmp/MANIFEST-<generacija>.tmp` se briše, upiše se ceo nov manifest i nad njim force |
| 3 | `atomicReplace` | failpoint `manifest.after-temp-sync.before-rename`, pa `ATOMIC_MOVE` + `REPLACE_EXISTING` preko `lsm/MANIFEST.json` |
| 4 | `fsyncDirectory(lsm/)` | failpoint `manifest.after-rename.before-directory-sync`, pa force direktorijuma |
| 5 | `current = next` | pogled u memoriji se menja tek posle koraka 4; brisanje WAL-a ili ulaza radi pozivalac, posle povratka |

- Objave su serijalizovane (`synchronized`), pa dve nikad ne idu istovremeno.
- Greška pre preimenovanja daje `ManifestPublishException` sa `renamed() == false`: stari manifest važi, privremeni fajl je obrisan. Greška u koraku 4 daje `renamed() == true`: na disku je možda nov manifest, pa pozivalac mora da prevede mehanizam u `FAILED` (CP1 §26.1), a pogled u memoriji ostaje stari.
- `loadAndValidate` za oporavak čita manifest strogo (`FORMAT.md` §5.2). `publishInitialManifest` je Matejev `ManifestInitializer` za korak 3 prve inicijalizacije.
- `TableIdAllocator.fromDisk` računa `max(manifest.nextTableId, 1 + najveći ID u sst/ ili tmp/)` **pre** čišćenja (CP1 §18.3), pa ID orphan-a ili privremenog izlaza nikad ne dobije nova tabela. Svaka objava upisuje `nextTableId = max(prethodni, sledeći ID alokatora)`.

### 6.2 Radnik održavanja i `maintenanceLock` (`MaintenanceWorker`)

`maintenanceLock` iz §3 je **jedna nit** radnika (jednonitni `ExecutorService`), a ne zaseban objekat brave: svaki posao održavanja, ručni ili automatski, izvršava se na toj niti, pa se flush i compaction nikad ne preklapaju (RECOMMENDED BASELINE iz CP1 §20.1, §23; nema `409 MAINTENANCE_CONFLICT`). Engine bravu posao uzima samo kroz `MaintenanceHost`, pa redosled ostaje `maintenanceLock → engineLock`.

| Posao | Ko ga pokreće | Pravilo |
|---|---|---|
| automatski flush | `flushNeeded()` posle rotacije (engine je pod write lock-om) | samo stavi posao u red i vrati se; ako je flush već u redu, ne dodaje drugi |
| ručni flush | `POST /admin/v1/storage/flush` → `flush(ctx)` | CP1 §23.1: uhvati `targetSequence = maxSequenceInEngine`, pa redom flush postojeće zamrznute generacije i rotacija aktivne dok `flushedThroughSequence < target`; `PUBLISHED` ili `NO_OP` |
| automatski compaction | `checkAutoCompaction()` posle oporavka, posle svake objave flush-a i compaction-a | u red ide samo ako je objavljeno ≥ 4 tabele i nijedan automatski compaction nije u redu ni u toku; po završetku se proverava ponovo |
| ručni compaction | `POST /admin/v1/storage/compact` → `compact(ctx)` | ≥ 2 tabele, inače `NO_OP` sa viđenim ID-jem (ili bez njega) |

- Ručni poziv čeka do roka zahteva. Ako rok istekne, vraća `DEADLINE_EXCEEDED`, a prihvaćeni posao se ipak završi u pozadini (CP1 §23).
- Svaka greška posla se loguje (`MAINTENANCE_FAILED`), čuva u `lastError()` i pokazuje u statusu kao `maintenance.lastError` (`code`, `message`). Automatski posao se posle greške ne ponavlja sam; sledeći okidač (rotacija, objava, ručni poziv) pokušava ponovo.
- `MaintenanceProgress` drži `operation`, `phase`, `targetSequence` i `inputTableIds` za status (CP1 §24). Faze idu `CAPTURE → WRITE_OUTPUT → FINALIZE_OUTPUT → PUBLISH_MANIFEST → RETIRE_INPUTS → IDLE`, a pri neuspehu `CLEANUP`. `SYNC_OUTPUT` se ne prijavljuje zasebno, jer force i preimenovanje izlaza radi jedan poziv Kimijevog `SSTableDiskWriter`-a.

### 6.3 Flush (`FlushJob.flushOldest`, CP1 §19.1)

| Korak | Kod | Šta je bitno |
|---:|---|---|
| 1 | `MaintenanceWorker.manualFlush` | ručni flush rotira aktivnu generaciju (`rotateIfActiveContainsUpTo`) tek kad nema zamrznute; automatski flush radi samo nad već zamrznutom |
| 2 | `host.oldestImmutable()` | FIFO; u CP1 postoji najviše jedna zamrznuta generacija, a oporavljenu može da štiti više segmenata |
| 3 | `tableIds.next()` | `flushAttempts++` |
| 4–8 | `SSTableDiskWriter.writeAndFinalize` (Kimi) | piše `tmp/sst-<id>.sst.tmp` strimovanjem, validira ga, force, `atomicMoveNoReplace` u `sst/`, force `sst/`; tombstone-i ulaze kao zapisi |
| — | failpoint, `DiskSSTableTableSource.open` | `flush.after-sstable-finalize.before-manifest`; izlaz se otvara i ponovo validira **pre** objave |
| 9–10 | `underEngineWriteLock` → `manifests.publish` | proveri da je ista generacija i dalje najstarija; rebase: dodaj tabelu, `flushedThroughSequence = max(prethodni, maxSequence generacije)` |
| 11 | `installFlushResult` | u istoj zameni pogleda povlači generaciju i dodaje tabelu, budi pisce; `flushPublications++` |
| 12–13 | `retireWal` | failpoint `flush.after-manifest-publish.before-wal-delete`, brisanje segmenata te generacije (`FlushInput.walSegmentIds`, svi su `≤` nove granice), force `wal/` |

Posle povratka iz koraka 11 nijedan čitalac ne koristi zamrznutu generaciju, jer čitanja drže read lock (§3). Zaštita se tako prenosi bez rupe: do koraka 10 podatke štiti WAL, a od koraka 10 objavljena tabela.

### 6.4 Compaction (`CompactionJob.compact`, CP1 §20.3)

| Korak | Kod | Šta je bitno |
|---:|---|---|
| 1 | `select` | ulazi su **sve** tabele iz trenutnog manifesta, upoređene sa pogledom engine-a; razlika je greška, ne pretpostavka; `compactionAttempts++`; failpoint `compaction.after-selection.before-output` |
| 2 | `count`, pa `writeOutput` | `MergingIterator` (Mladen) daje jedan zapis po ključu, onaj sa najvećim `sequence`, i **zadržava tombstone-e** (I8). Prolazi se **dva puta**: prvo se prebroje zapisi, pa se upisuju, jer writer traži tačan broj unapred; ništa se ne drži u memoriji |
| 3–5 | `SSTableDiskWriter.writeAndFinalize` | isto kao flush; Bloom filter se pravi za tačan broj izlaznih zapisa, pa sadrži i ključeve tombstone-a |
| — | failpoint, `open` | `compaction.after-output-finalize.before-manifest` |
| 6–7 | `underEngineWriteLock` → `publish` | svaki izabrani ulaz mora i dalje biti i u manifestu i u pogledu; nov manifest uklanja **tačno** ulaze, dodaje izlaz, čuva svaku drugu tabelu i **ne** menja `flushedThroughSequence` |
| 8 | `installCompactionResult` | ulazi napuštaju pogled, izlaz ulazi; `compactionPublications++` |
| 9–10 | `retireInputs` | failpoint `compaction.after-manifest-publish.before-input-delete`, brisanje ulaznih fajlova, force `sst/` |

Compaction ne menja ono što vraćaju `GET` i `SCAN`, samo fajlove. Tombstone se ne uklanja ni kada je jedini zapis ključa, jer CP1 ne daje dokaz pokrivenosti koji bi to učinio bezbednim (CP1 §20.2).

### 6.5 Greške i granice pada

| Gde | Tokom rada | Posle restarta |
|---|---|---|
| pre preimenovanja manifesta (pisanje, force, preimenovanje izlaza, oba `*.before-manifest` failpoint-a, `manifest.after-temp-sync.before-rename`) | posao vraća grešku, svoj izlaz briše (`temporaryFilesRemoved` za privremeni, `orphanFilesRemoved` za finalizovan), mehanizam ostaje `READY`, ponovni pokušaj je dozvoljen | stari manifest je merodavan; flush: WAL se ponovo primenjuje; compaction: ulazi ostaju; zaostali izlaz je orphan (CP1-FLS-002, 003, 005, CMP-003) |
| posle preimenovanja, pre force-a `lsm/` (`manifest.after-rename.before-directory-sync`) | `reportFatalError`, mehanizam ide u `FAILED`; ništa se ne briše | oporavak vidi kompletan stari ili kompletan nov manifest; oba su zaštićena jer ništa nije obrisano (CP1-FLS-006) |
| posle trajne objave, pri brisanju WAL-a ili ulaza (oba `*.after-manifest-publish.*` failpoint-a) | posao vraća grešku, ali mehanizam ostaje `READY`; greška je u logu i u `maintenance.lastError` | nov manifest je merodavan; pokriveni WAL se ne primenjuje (`walRecordsReplayed = 0`) i briše se; stari ulazi su orphan-i (CP1-FLS-004, CMP-004) |
| oštećen ulaz tokom compaction-a | `reportFatalError` sa originalnom kategorijom (npr. `DATA_BLOCK`), mehanizam ide u `FAILED`, isto kao `GET`/`SCAN` (§7.3) | oporavak proverava sve tabele i staje sa istom kategorijom (I7) |

Pad (`CRASH`) na bilo kojoj od ovih granica ostavlja kompletan stari ili kompletan nov manifest. Do preimenovanja manifesta podatke štite stari WAL i stare tabele, a posle njega nova tabela; brisanje uvek dolazi poslednje.

### 6.6 Testovi

- Unit testovi: `ManifestTest`, `ManifestManagerTest` (redosled `StorageIO` poziva i oba `manifest.*` failpoint-a), `TableIdAllocatorTest`, `FlushJobTest` i `CompactionJobTest` (pravi `Engine` i prave SSTable, `IO_ERROR` na svakoj granici i ponovni pokušaj), `MaintenanceWorkerTest` i `MaintenanceWorkerCompactionTest` (ugovor §23, rok, okidač, serijalizacija), `MaintenanceProgressTest`, `InspectManifestTest`.
- Scenariji nad pravim čvorom: CP1-FLS-001 (flush, `NO_OP`, restart, čitanja iz SSTable), FLS-003 do FLS-006 (svaka granica objave), CMP-001 (najveći `sequence` po ključu, high-water nepromenjen), CMP-002 (tombstone-i u izlazu i Bloom filteru), CMP-003 i CMP-004 (pad pre i posle compaction manifesta). Zajednički pomoćnik je `scenarios/FlushScenarioChecks`.
- Bootstrap failpoint-i (CP1 §26.3) još ne postoje, pa automatski flush posle restarta ne može da se zaustavi; scenariji zato proveravaju brojače oporavka i prihvataju oba dozvoljena krajnja stanja (stari manifest ili kasniju pravu objavu).

---

## 7. Oporavak i politika oštećenja

> **Piše Matej** (CP1 §14, §16.4, §21).

Oporavak dokazuje stanje direktorijuma iz tačno tri izvora: manifesta, tabela koje manifest navodi i WAL sufiksa koji manifest ne pokriva. Listing direktorijuma nikad ne odlučuje šta je objavljeno (I4). On služi samo za dve stvari: da se nađu privremeni i orphan fajlovi i da alokatori ID-jeva preskoče svaki ID koji postoji na disku (CP1 §16.1, §18.3). Ništa ne postaje `READY` dok svi koraci ne uspeju.

### 7.1 Koraci i kod

Ulaz je `RecoveryProcedure.openEngine`; `recover` radi korake 1–14, a `Engine.open` korak 15.

| CP1 §21.1 | Kod | Šta je bitno |
|---|---|---|
| 1–2 | `DataDirectory.open` | `LOCK` kroz `tryLock()`, bez čekanja; zatečeno stanje se klasifikuje (`StartupClassifier`) pre bilo kakvog pisanja; `node.json` i direktorijumi se proveravaju ili se prva inicijalizacija bezbedno nastavlja |
| 3 | `ManifestManager.loadAndValidate` (Ilija) | prazan manifest nastaje samo za dokazano novu bazu, kroz `ManifestInitializer`; posle učitavanja failpoint `recovery.after-manifest-load` |
| 4 | `SSTableRecoveryVerifier.verifyAll` (Kimi) | svaka navedena tabela se strimovanjem proverava **pre** čišćenja, da neispravan izvor ne odluči šta se briše |
| — | `WalIdAllocator.fromDisk`, `TableIdAllocator.fromDisk` | računaju se pre čišćenja, pa ID obrisanog ostatka ostaje zauzet |
| 5–6 | `removeTemporaryAndOrphanFiles` | briše se sve iz `tmp/` i svaki `sst-<id>.sst` koji manifest ne navodi; fajl nepoznatog imena ostaje (`RECOVERY_UNKNOWN_FILE`); force direktorijuma |
| 7–9 | `WalRecovery`, `WalReader` | segmenti rastućim ID-jem; odseca se samo nepotpun završni okvir celog WAL-a, pa force; failpoint `recovery.after-wal-tail-truncate` |
| 10–11 | `WalRecovery` | zapisi iznad `flushedThroughSequence` idu u oporavljenu generaciju 1; sledeći redni broj je `max(flushedThroughSequence, najveći ispravan WAL seq) + 1` |
| 12–13 | `WalRecovery` | nov aktivni segment protokolom iz CP1 §16, tek onda brisanje praznih i pokrivenih segmenata; zadržani segmenti ulaze u WAL katalog (§7.6); failpoint `recovery.after-wal-replay.before-ready` |
| 14 | `openTables` | čitač (indeks i Bloom filter u memoriji) za svaku tabelu iz manifesta |
| 15 | `Engine.open` (Mladen) | mehanizam je `READY`; oporavljena generacija odmah ide radniku na flush |

Engine strana: `Engine.open(config, RecoveredState, maintenance)` dobija `lastUsedSequence`, oporavljenu generaciju, tabele iz manifesta i nov aktivni segment; odbija stanje koje bi ponovo upotrebilo redni broj ili WAL segment, vraća spreman mehanizam, odmah traži flush oporavljene generacije i proveru auto-compaction-a. Radnika održavanja pravi `RecoveryProcedure.MaintenanceFactory`, koja uz mehanizam dobija i `RecoveryResult`; tako Ilijin `FlushJob` nastavlja sa alokatorom ID-jeva tabela iz oporavka.

Pad u bilo kom koraku je bezbedan: svaki korak ili ne menja disk, ili ostavlja stanje koje sledeći oporavak klasifikuje isto, pa ponovljen oporavak daje isti logički rezultat (CP1-WAL-008; testovi `RecoveryFailpointTest` i `WalRecoveryFailpointTest`).

### 7.2 Nepotpun završetak naspram oštećenja

- Odseca se samo okvir koji je poslednji u **celom** WAL-u i fizički nepotpun. Okvir iza koga postoji drugi okvir nije nastao padom, pa je oštećenje (`WAL_FRAME`).
- Komplement dužine se proverava pre EOF-a. Oštećena dužina zato nikad ne izgleda kao nepotpun završetak (`WAL_LENGTH_GUARD`).
- Kompletan okvir sa lošim CRC-om je oštećenje (`CHECKSUM`) i kada je fizički poslednji u fajlu, jer može da sadrži potvrđenu mutaciju (I7, CP1-WAL-003).
- Redni brojevi strogo rastu kroz ceo WAL, i preko granica segmenata. Ponovljen ili opadajući redni broj je oštećenje (`WAL_SEQUENCE_ORDER`) i kada su svi okviri i CRC-ovi ispravni: zapisi se nikad ne sortiraju da bi se dobila neka „moguća“ istorija (CP1-WAL-007). Praznine u rednim brojevima i u ID-jevima segmenata su dozvoljene.
- Zaglavlje segmenta (magic, verzija, ID jednak imenu fajla, CRC) mora biti ispravno (`WAL_HEADER`). To važi i za konačni segment kraći od 24 B, jer segment dobija konačno ime tek posle force-a kompletnog zaglavlja.
- Oštećenje se nikad ne popravlja i ne preskače: fajl ostaje bajt po bajt isti, novi segment se ne pravi, a pokretanje staje. Redosled provera i kategorije su u `FORMAT.md` §3.4.

### 7.3 Čvor i neuspeh oporavka

`StandaloneNode` pokreće admin server (8082), zatim oporavak nad `DLSM_DATA_DIR`, pa javni server (8080). Životni ciklus je `STARTING → RECOVERING → READY`, ili `FAILED` (CP1 §22).

Kada oporavak ne uspe (CP1 §21.4), proces **ostaje živ** u `FAILED`:

- `/health/live` je 200, a `/health/ready` je 503 sa `lifecycle: FAILED` i `lastFatalError`. `code` je stabilna kategorija (npr. `CHECKSUM`, `DIRECTORY_LOCKED`, `MANIFEST_INVALID`), a `message` za WAL navodi segment i offset, bez apsolutnih putanja;
- javni port je otvoren, ali svaki KV zahtev dobija `503 NODE_NOT_READY`. Ni ključevi zapisani pre oštećenja se ne služe, jer stanje nije dokazano;
- status rute su 503, jer bez mehanizma nema snimka;
- `LOCK` ostaje zauzet do kraja procesa, pa drugi proces ne može da menja isti direktorijum;
- iz `FAILED` nema povratka u istom procesu; ponovo pokušava tek restart.

Za vreme rada: oštećenje ili I/O greška na objavljenoj tabeli, kao i greška u dopisivanju ili force-u WAL-a (§7.4), vodi u `FAILED`; posle toga se odbijaju i mutacije i čitanja (§10), a status ostaje dostupan.

### 7.4 Greška u putu upisa

`WalSegment.append` piše okvir u dva poziva `StorageIO.writeFully`: strogi prefiks (podrazumevano 8 B zaštićene dužine, ili `prefixBytes` iz pravila failpoint-a), pa ostatak. `force` je tačka trajnosti (§2). Svaka greška u `append` ili `force`, prava ili ubrizgana, znači da je kraj segmenta neizvestan (CP1 §9.3):

- segment od tada odbija svaki `append` i `force` (`hasUncertainTail`), pa iza neizvesnog kraja nikad ne nastaje nov okvir;
- mehanizam prelazi u `FAILED` sa `lastFatalError.code = STORAGE_IO`; klijent dobija `500 STORAGE_IO`, a svaka kasnija mutacija i svako čitanje `503 NODE_NOT_READY`;
- šta je na disku odlučuje tek oporavak posle restarta:

| Mesto greške | Na disku | Posle oporavka |
|---|---|---|
| `wal.before-append` | ništa od kandidata | kandidat odsutan |
| `wal.after-record-prefix` | strogi prefiks okvira | prefiks se odseca jednom (`walTornTailsTruncated`), kandidat odsutan |
| `wal.after-append.before-sync` | kompletan okvir bez force-a | kandidat prisutan ako je okvir preživeo, inače odsutan; nikad delimičan |

Brojači prate granice iz CP1 §24: `walAppendAttempts` pre prvog upisa, `walBytesWritten` posle svakog uspešnog upisa (i prefiksa), `walAppendsCompleted` tek posle celog okvira, `walForcesSucceeded` tek posle uspešnog force-a (CP1-WAL-004).

### 7.5 Više segmenata

- Segmenti se čitaju rastućim ID-jem iz imena fajla, nikad redosledom listanja ni po vremenu izmene; ID u zaglavlju mora biti jednak ID-ju iz imena.
- Oporavljenu generaciju može da štiti više starih segmenata; svi ostaju dok flush ne objavi njihove zapise (CP1 §19.1, korak 2).
- Nov aktivni segment dobija ID iznad svakog ID-ja nađenog u `wal/` ili `tmp/`, pa se ID nikad ne koristi dvaput. U CP1-WAL-007, posle segmenata 2, 5 i 9, aktivni je 10.
- Replay ništa ne dopisuje u WAL: posle oporavka su `walAppendAttempts` i `walBytesWritten` nula, a stari segmenti su bajt po bajt isti.

### 7.6 WAL segmenti u statusu

Niz `walSegments` (CP1 §24) dolazi iz `wal.WalCatalog`, spiska segmenata koji su na disku:

- puni ga `WalSegments.createSegment` (čim je segment premešten u `wal/`), `WalSegment.append` (veličina posle svakog upisa, redni broj tek za kompletan okvir) i oporavak (zadržani segmenti, sa dužinom posle odsecanja); prazni ga `WalSegments.retire`, kroz koji flush briše pokrivene segmente (`WalRetirer` u `FlushJob`);
- katalog ne čita disk. `NodeStatus` ga čita pod istim engine read lock-om kao deo mehanizma, a mehanizam dopisuje pod write lock-om, pa se veličine i redni brojevi slažu sa MemTable-ovima iz istog odgovora;
- uloga je `ACTIVE` za segment aktivne MemTable, `IMMUTABLE` za segmente zamrznute generacije i `COVERED_PENDING_DELETE` za svaki drugi segment na disku: brisanje posle objave flush-a čeka ili nije uspelo, ili je to prazan segment neuspele rotacije. Takve segmente sledeći oporavak briše kao pokrivene ili prazne.

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

Mesta failpoint-a u mehanizmu su pozivi `Engine.failpoint(ime, ctx)`: `wal.after-sync.before-memtable` i `memtable.after-apply.before-response` (`mutate`), `rotation.after-new-wal-sync.before-switch` i `rotation.after-switch.before-flush` (`rotateLocked`), `scan.after-snapshot.before-merge` (`scan`). Ponašanje ubrizganih grešaka na njima je u §2 i §4.2.

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
| HTTP: `TCP_NODELAY` na svakoj prihvaćenoj vezi (`sun.net.httpserver.nodelay=true`, postavlja `HttpServerHost`) | `ODLUKE.md`, red o `TCP_NODELAY` od 31.10.2026. |
| ugovor engine ↔ održavanje i ulaz za oporavak (razlike od skice u planu §5.1) | `ODLUKE.md`, redovi o `MaintenanceHost` i `Engine.open` |
| oporavljena generacija se flush-uje **posle** `READY` | `ODLUKE.md`, red o `Engine.open` |
| posle neuspelog oporavka čvor ostaje živ u `FAILED`; dijagnostika je u `/health/ready` (`lastFatalError`), javni port vraća 503 | `ODLUKE.md`, red o neuspelom oporavku od 23.10.2026. |
| `DLSM_DATA_DIR` (podrazumevano `/var/lib/dlsm`) bira direktorijum podataka | `ODLUKE.md`, red o `DLSM_DATA_DIR` |
| manifest se čita strogo; high-water provera je `maxSequence ≤ flushedThroughSequence` | `ODLUKE.md`, red o čitanju `MANIFEST.json` od 23.10.2026. |
| održavanje je jedna nit radnika; automatski poslovi se posle greške ne ponavljaju sami | `ODLUKE.md`, red o radniku održavanja |
| greška posle trajne objave flush-a (brisanje WAL-a) ostavlja mehanizam `READY` | `ODLUKE.md`, red o grešci posle objave flush-a |
| compaction: oštećen ulaz vodi u `FAILED`, izlaz u dva prolaza, brojači čišćenja | `ODLUKE.md`, red o compaction-u |

Izbori za WAL, direktorijum i oporavak (Matej; obrazloženja u `docs/ODLUKE.md`). Usvojene preporučene osnove: `LOCK` kroz `tryLock()` i `node.json` iz CP1 §14.1, protokol stvaranja segmenta iz §16.1, oporavak u 15 koraka iz §21.1.

| Izbor | Gde je zapisan |
|---|---|
| privremeni segment je `tmp/wal-<id>.log.tmp` sa istim ID-jem; stvaranje segmenta nema failpoint | `ODLUKE.md`, redovi o privremenom segmentu (18.10.) i o pravljenju segmenta (22.10.) |
| nepotpun završetak se odseca samo kao poslednji okvir **celog** WAL-a; konačni segment kraći od 24 B je `WAL_HEADER` | `ODLUKE.md`, redovi od 18.10.2026. |
| redosled provera okvira i kategorije `WAL_HEADER`, `WAL_LENGTH_GUARD`, `WAL_FRAME`, `CHECKSUM`, `WAL_SEQUENCE_ORDER` | `ODLUKE.md`, red od 18.10.2026.; `FORMAT.md` §3.4 |
| okvir se piše u dva upisa (prefiks, ostatak), podrazumevani `prefixBytes` je 8; `walBytesWritten` ne broji zaglavlje segmenta | `ODLUKE.md`, redovi od 22.10.2026. |
| posle prve greške u `append` ili `force` segment odbija sav dalji rad, nezavisno od `FAILED` u mehanizmu | `ODLUKE.md`, red od 22.10.2026. |
| `node.json` se čita strogo; svaki neispravan ili tuđ identitet je `NODE_IDENTITY_MISMATCH`; nedostajući direktorijum uz postojeći `node.json` je `DIRECTORY_LAYOUT` | `ODLUKE.md`, redovi od 18.10.2026.; `FORMAT.md` §4 |
| „stabilni podaci“ pri klasifikaciji su manifest ili bilo koji unos u `wal/` ili `sst/`; `LOCK` se otvara mimo `StorageIO` i drži do kraja procesa, i u `FAILED` | `ODLUKE.md`, redovi od 22.10. i 23.10.2026. |
| čišćenje briše sve iz `tmp/` i orphan `sst-<id>.sst`; fajl nepoznatog imena ostaje; tabele se validiraju i u koraku 4 i pri otvaranju čitača | `ODLUKE.md`, redovi o čišćenju i validaciji od 23.10.2026. |
| `recovery.after-wal-tail-truncate` se pogađa pri svakom pokretanju, a `recovery.after-wal-replay.before-ready` posle koraka 13 | `ODLUKE.md`, red od 22.10.2026. |
| `walSegments` dolazi iz WAL kataloga; segment na disku koji ne štiti nijednu generaciju je `COVERED_PENDING_DELETE` | `ODLUKE.md`, red od 28.10.2026.; §7.6 |

Samo za unit testove mehanizma: `Engine.openInMemory`, `InMemoryWal` i `InMemoryFlusher`/`MemoryTable`; čvor ih ne koristi. U statusu su svi delovi pravi: WAL segmenti dolaze iz WAL kataloga (§7.6), manifest i održavanje iz `ManifestManager` i `MaintenanceProgress`.
