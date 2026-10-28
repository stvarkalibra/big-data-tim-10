# AI_USAGE.md — upotreba AI alata

Kratak inženjerski zapis o značajnoj pomoći AI alata (CP1 §31.3, PI §11): koji alati, koji delovi su nastali uz njihovu pomoć, kako je rad pregledan i proveren, i poznate slabosti. Svaki član dopisuje svoj deo tokom rada (plan §15); urednik fajla je Andrija.

AI pomoć ne menja odgovornost: svaki član mora da ume da prati, testira, menja i brani predati kod, uključujući delove nastale uz AI.

---

## Mladen — jezgro mehanizma, javni API i server

### Alati

- **Claude Code** (desktop aplikacija, model Claude Opus 5.5): pisanje koda i testova po koracima, čitanje specifikacije, pregled tuđih PR-ova naspram ugovora.

### Šta je nastalo uz AI pomoć

Gotovo sav kod i testovi iz Mladenovih grana nastali su u radu sa AI alatom, commit po commit:

| Grana | Komponente |
|---|---|
| `mladen/common-types` | `Keys`, `Kind`, `InternalRecord`, `ErrorCode`, `StorageException` |
| `mladen/http-skeleton` | `ErrorEnvelope`, `Json`, `RequestContext`, `Router`, `HttpServerHost`, `HealthRoutes`, `ServerConfig`, `StandaloneNode`, `Main` |
| `mladen/memtable` | `SequenceAllocator`, `MemTable`, `ImmutableMemTable` |
| `mladen/merging-iterator` | `MergingIterator`, `LiveScan` |
| `mladen/inmemory-engine` | `StorageEngine`, `Engine` (put mutacije), `KvRoutes`, `Base64Values` |
| `mladen/maintenance-host` | `TableSource`, `EngineView`, `MaintenanceHost`, `FlushInput`, `FlushScheduler`, rotacija, `WalFactory`, `InMemoryWal` |
| `mladen/backpressure` | backpressure, `reportFatalError`, `InMemoryFlusher`, `MemoryTable` |
| `mladen/engine-status`, `mladen/status-wiring` | `EngineStatus`, `NodeStatus`, povezivanje status ruta |
| `mladen/inspect-cli`, `mladen/engine-open` | `InspectCommand`, `RecoveredState`, `Engine.open` |
| `mladen/sstable-reads` | testovi mehanizma nad pravim SSTable fajlovima |
| `mladen/design-docs` | Mladenovi odeljci `DESIGN.md` (§1–§5, §10) |

### Kako je rad pregledan i proveren

- **Specifikacija pre koda.** Za svaki deo AI je prvo čitao odgovarajući odeljak CP1/PI (npr. §9 za put mutacije, §10 za rotaciju, §12 za SCAN, §24 za status), a kod nosi komentare sa brojem koraka iz specifikacije.
- **Mali commit-ovi, pregled pre svakog.** Svaki commit je jedna celina; Mladen je pre commit-a pregledao šta je urađeno i zašto, a git je radio sam.
- **Testovi uz svaki deo.** Unit testovi za čiste delove, testovi konkurentnosti (npr. 8 niti × 250 upisa istog ključa, I2: čitalac usred force-a ne vidi vrednost), nasumični testovi poređeni sa nezavisnim modelom (`MergingIterator`), HTTP testovi nad pravim čvorom (CP1-API-001/002/003), testovi nad pravim SSTable fajlovima.
- **Ponavljanje testova sa nitima.** Testovi koji zavise od niti i rokova pokretani su više puta zaredom; čekanje u testovima je ograničeno proverom stanja, ne `sleep`-om kao dokazom.
- **Pravi proces.** Posle većih koraka `Main` je pokretan kao pravi proces i proveravan `curl`-om; tako su nađene i ispravljene dve greške: JDK `HttpServer` koji nije oslobađao port, i klijent koji je visio kad handler baci `Error`.
- **Nezavisne provere tuđeg rada.** Heks primer WAL-a iz `FORMAT.md` nezavisno je preračunat (sopstveni CRC32C); Kimijev disk reader i status rute provereni su naspram ugovora pre povezivanja.
- **Odluke zapisane.** Svaki izbor koji spec ne fiksira upisan je u `docs/ODLUKE.md`.

### Poznate slabosti i otvorena pitanja

- **Bez trajnosti dok ne stigne pravi WAL.** Čvor radi sa `InMemoryWal`; podaci se gube pri restartu dok Matejev `WalSegment` ne stane iza `MutationLog`/`WalFactory`.
- **Privremeni delovi statusa.** `NodeStatus` izvodi WAL segmente (bez veličine), manifest (generacija 0) i održavanje (`IDLE`) iz stanja mehanizma, dok pravi moduli ne isporuče svoje podatke.
- **Failpoint-i još nisu povezani.** Pet mesta u `Engine` je samo obeleženo komentarom; čeka se failpoint okvir.
- **Scenario testovi zavise od harness-a.** CP1-API-004 i API-005 traže failpoint `PAUSE`, pa još nisu napisani kao scenariji.
- **Za potvrdu kod asistenta:** značenje `minSequence` MemTable generacije (opseg primenjenih mutacija) i odbijanje čitanja posle `FAILED` (`docs/ODLUKE.md`).

---

## Matej — WAL, direktorijum i oporavak

### Alati

- **Claude Code** (desktop aplikacija, model Claude Opus 5.5): čitanje specifikacije, pisanje koda i testova po koracima, pregled kako se Matejev kod uklapa sa tuđim modulima.

### Šta je nastalo uz AI pomoć

| Grana | Komponente |
|---|---|
| `matej/common-bin` | `Bin` (big-endian, ograničeno čitanje dužina, CRC32C) |
| `matej/wal-format` | `FORMAT.md` §WAL sa heksadecimalnim primerom |
| `matej/wal-codec`, `matej/wal-reader` | `WalCodec`, `WalReader` (klasifikacija `VALID` / `TORN_TAIL` / `CORRUPT`) |
| `matej/inspect-wal` | `InspectWal` (`inspect wal`) |
| `matej/wal-segment` | `WalSegments`, `WalSegment`, `WalIdAllocator`, failpoint `wal.after-record-prefix` sa `prefixBytes` |
| `matej/data-directory` | `DirectoryLock`, `NodeIdentity`, `StartupClassifier`, `DataDirectory` |
| `matej/recovery-wal`, `matej/recovery` | `WalRecovery`, `RecoveryProcedure` (CP1 §21.1, koraci 1–15) |
| `matej/fix-recording-storage-io` | test `RecordingStorageIO` na Windows-u |
| `matej/durable-node` | `DLSM_DATA_DIR`, trajni čvor kroz oporavak sa `MaintenanceWorker`-om, čvor u `FAILED` posle neuspelog oporavka, `lastFatalError` u `/health/ready` |
| `matej/wal-scenarios` | `WalScenarioNode` i scenariji CP1-WAL-001, 002, 003, 005, 006 |
| `matej/fix-registry-test` | popravka `ScenarioRegistryTest` posle registracije WAL scenarija |
| `matej/wal-status` | `WalCatalog` i pravi niz `walSegments` u statusu (veličina, redni brojevi po segmentu, `COVERED_PENDING_DELETE`); `WalRetirer` u `FlushJob`-u, uz Ilijino odobrenje |
| `matej/n2-cleanup` | provera HTTP 503 u CP1-WAL-003, dopuna ove dokumentacije i plana |

### Kako je rad pregledan i proveren

- **Specifikacija pre koda.** Za svaki deo AI je prvo čitao odeljak CP1 (§14, §16, §21, §26.1) i plan §6.2; koraci u kodu nose broj koraka iz specifikacije.
- **Mali commit-ovi, pregled pre svakog.** Grana se pravi pre rada, svaki commit je jedna celina; Matej je pregledao izmenu i sam radio git.
- **Testovi uz svaki deo.** Codec za svaku klasu greške, reader nad ručno pravljenim segmentima, oporavak nad pravim fajlovima (novi, postojeći i oštećeni direktorijumi), ponovljeni padovi na svakom failpoint-u oporavka, testovi čvora preko HTTP-a (restart, flush, oštećen WAL, zaključan direktorijum).
- **Pravi I/O na Linux-u.** Na Windows-u testovi preskaču samo force direktorijuma (`RecordingStorageIO`, `WindowsSafeStorageIO`); testovi sa pravim `DefaultStorageIO` su `@EnabledOnOs(LINUX)`. CI se za sada pokreće samo ručno i ne pokreće scenarije, pa se oni proveravaju u Linux kontejneru.
- **Provera isporuka pre treće nedelje.** AI je prošao kroz sve Matejeve isporuke iz N1 i N2 prema planu §6.2 i CP1 §24 i §29.2. Tako su nađeni `main` koji se ne gradi posle registracije WAL scenarija i privremeni prikaz `walSegments` u statusu; oba su popravljena pre rada na WAL-004.
- **Scenariji nad pravim procesom.** WAL scenariji su pokrenuti u Linux kontejneru nad pravim server jar-om, svaki za najmanje 5 seed-ova; iz JSON loga servera je provereno da se desilo ono što scenario tvrdi (replay, odsecanje na tačnom offsetu, kategorija oštećenja). Oracle-i ne koriste produkcioni kod: dužine okvira i offseti se računaju iz `FORMAT.md`, a logičko stanje iz `ReferenceModel`-a.
- **Odluke zapisane.** Svaki izbor koji spec ne fiksira je u `docs/ODLUKE.md`.

### Poznate slabosti i otvorena pitanja

- **WAL-004, 007 i 008 još ne postoje.** WAL-008 čeka bootstrap failpoint-e (`DLSM_TEST_BOOTSTRAP_FAILPOINTS_B64`, CP1 §26.3), koji još nisu implementirani. WAL-007 traži pripremljen volumen, a `fixtures/WalFixtureWriter` još ne piše format iz `FORMAT.md` §3 (nema zaglavlje segmenta).
- **WAL-006 grana „kandidat nedostaje“ nije izvršena.** Pad procesa ne briše keš OS-a, pa je kompletan okvir bez force-a uvek ostao na disku.
- **`RECOVERING` i ubrizgan `IO_ERROR` tokom oporavka nisu testirani na nivou čvora.** Za to treba failpoint naoružan pre pokretanja (`DLSM_TEST_BOOTSTRAP_FAILPOINTS_B64`).
- **Status čvora koji je pao u oporavku je 503.** Dijagnostika je samo u `/health/ready`. Isto tumačenje stoji i u Kimijevom delu: CP1 §24 opisuje status mehanizma koji postoji.
- **Prazan segment neuspele rotacije.** Ostaje u statusu kao `COVERED_PENDING_DELETE` dok ga sledeći oporavak ne obriše (`docs/ODLUKE.md`, 28.10.2026.).
- **Windows.** Pravi server na Windows-u ne prolazi force direktorijuma, pa WAL scenariji rade samo u Docker-u (`./dbctl test checkpoint-1`). CI (`linux-tests.yml`) pokreće samo `./mvnw verify`, ne i scenarije.

---

## Ilija — manifest, flush, compaction i održavanje

### Alati

- **Claude Code** (desktop aplikacija, model Claude Opus 5.5): čitanje specifikacije, pisanje koda i testova po koracima, provera build-a i scenarija nad pravim čvorom.

### Šta je nastalo uz AI pomoć

| Grana | Komponente |
|---|---|
| `ilija/manifest-model` | `Manifest`, `ManifestValidator`, `ManifestManager`, `ManifestPublishException`, `TableIdAllocator`, `FORMAT.md` §5 |
| `ilija/flush` | `FlushJob`, `MaintenanceWorker`, `MaintenanceProgress`, ruta `/admin/v1/storage/flush` |
| `ilija/compaction`, `ilija/compaction-corruption` | `CompactionJob`, automatski okidač, ruta `/admin/v1/storage/compact`, oštećen ulaz vodi u `FAILED` |
| `ilija/fix-worker-wiring`, `ilija/windows-tests` | radnik sa compaction-om u čvoru, testovi kroz `RecordingStorageIO` / `WindowsSafeStorageIO` |
| `ilija/status-and-decisions` | `maintenance.lastError` u statusu, redovi u `docs/ODLUKE.md` |
| `ilija/inspect-manifest` | `InspectManifest` (`inspect manifest`), `FORMAT.md` §5.5 |
| `ilija/scenario-fls-001`, `003` do `006` | `FlushScenarioChecks` i scenariji CP1-FLS-001, 003, 004, 005, 006 |
| `ilija/scenario-cmp-001` do `004` | scenariji CP1-CMP-001, 002, 003, 004 |
| `ilija/design-maintenance` | `DESIGN.md` §6, uređivanje §3.1 i §10 |

### Kako je rad pregledan i proveren

- **Specifikacija pre koda.** Za svaki deo AI je prvo čitao odeljak CP1 (§18–§20, §23, §24, §26.1, §29.3, §29.5) i plan §6.4.
- **Mali commit-ovi, pregled pre svakog.** Grana se pravi pre rada, svaki deo je jedna celina.
- **Testovi uz svaki deo.** Unit testovi nad pravim `Engine`-om i pravim SSTable fajlovima, sa `IO_ERROR` na svakoj granici objave i ponovnim pokušajem; testovi radnika za ugovor ručnog flush-a i compaction-a, istek roka i serijalizaciju.
- **Scenariji nad pravim procesom.** Svaki scenario je pokrenut nad pravim server jar-om sa više seed-ova, a zatim je jedan oracle namerno pokvaren da se potvrdi da test zaista pada. Oracle-i ne koriste produkcioni kod: logičko stanje je iz `ReferenceModel`-a, a fizičko iz statusa, inspektora i fajlova na disku.
- **Windows.** Testovi su pokrenuti i u simulaciji Windows-a (`os.name`), gde se preskače samo force direktorijuma.
- **Odluke zapisane.** Svaki izbor koji spec ne fiksira je u `docs/ODLUKE.md`.

### Poznate slabosti i otvorena pitanja

- **Bootstrap failpoint-i (CP1 §26.3) ne postoje.** Automatski flush posle restarta ne može da se zaustavi, pa FLS-003 i FLS-005 prihvataju oba dozvoljena krajnja stanja.
- **FLS-006 grana „stari manifest“ nije izvršena.** Pad procesa ne briše keš OS-a, pa oporavak uvek zatiče nov manifest.
- **Za potvrdu kod asistenta:** stroga provera nepoznatih polja manifesta i tumačenje provere high-water mark-a, `READY` posle greške pri brisanju WAL-a i polje `maintenance.lastError` (`docs/ODLUKE.md`).

---

## Kimi — SSTable, statistike, status i logovi

### Alati

- **Claude Code** (desktop aplikacija, model Claude Opus 5.5 i Sonnet 5.5): čitanje specifikacije, pisanje koda i testova po koracima, pregled usklađenosti sa ugovorima drugih modula.

### Šta je nastalo uz AI pomoć

| Grana | Komponente |
|---|---|
| `kimi/storage-stats`, `kimi/json-log-hardening` | `StorageStats` (brojači iz CP1 §24, `resetEpoch`), `JsonLog` |
| `kimi/bloom-filter`, `kimi/sstable-blocks`, `kimi/sstable-format-contract` | `BloomFilter`, `BlockCodec`, `SparseIndex`, `FORMAT.md` §SSTable |
| `kimi/sstable-writer`, `kimi/sstable-writer-self-check`, `kimi/sstable-stream-encoder` | `SSTableWriter`, `SSTableStreamEncoder`, samoprovera writer-a |
| `kimi/sstable-persistence`, `kimi/sstable-disk-reader`, `kimi/sstable-read-statistics` | `SSTableFinalizer`, `SSTableReader`, `SSTableIterator`, brojači čitanja |
| `kimi/sstable-streaming-validator`, `kimi/sstable-recovery-verification`, `kimi/sstable-hardening`, `kimi/sstable-five-fixes` | `SSTableValidator.validateFully`, `SSTableRecoveryVerifier`, kategorije oštećenja |
| `kimi/sstable-table-source`, `kimi/sstable-flush-record-adapter` | `DiskSSTableTableSource`, `SSTableRecordAdapter` (šav prema mehanizmu i flush-u) |
| `kimi/sstable-inspect-cli` | `inspect sstable` |
| `kimi/storage-status`, `kimi/admin-status-routes`, `kimi/status-failed-fix` | `StatusBuilder`, `StandaloneStatusBuilder`, `StorageStatusRoutes` |
| `kimi/sst001-runner`, `kimi/sst-scenario-runner`, `kimi/sstable-large-scenarios`, `kimi/sstable-merge-corruption`, `kimi/sstable-multisource-corruption`, `kimi/sstable-disk-integration-tests` | scenariji CP1-SST-001 do SST-006 i testovi nad pravim fajlovima |
| `kimi/cp1-fls-002` | scenario CP1-FLS-002 |

### Kako je rad pregledan i proveren

- **Specifikacija pre koda.** Za svaki deo AI je prvo čitao CP1 §11, §12, §17, §18.1, §24, §25 i §29.4; format bajtova je prvo zapisan u `FORMAT.md`, pa tek onda kod.
- **Mali commit-ovi, pregled pre svakog.** Svaki commit je jedna celina; izmenu je pregledao Kimi pre nego što je ušla u git.
- **Testovi uz svaki deo.** Round-trip i svaka klasa greške za blokove, indeks, Bloom i footer; statistički test Bloom filtera (bez lažno negativnih); nezavisni oracle za Bloom i tačkasto čitanje; veliki testovi tačkastog čitanja i skeniranja opsega (broj pročitanih blokova); oštećenja nad objavljenim tabelama (CRC, skraćenje, indeks, footer, nepodudaranje sa manifestom).
- **Oštećenje nije odsustvo.** Svaki test oštećenja traži `StorageException` sa tačnom kategorijom, nikad prazan rezultat (I7).
- **Scenariji nad pravim procesom.** SST-001 do SST-006 i FLS-002 pokrenuti su u Linux kontejneru preko `./dbctl test checkpoint-1`; oracle-i koriste nezavisni `ReferenceModel` i čitaju bajtove po `FORMAT.md`, ne produkcioni kod.
- **Nalaz pri završnoj proveri.** FLS-002 je padao u Docker-u jer je proveravao da je manifest posle oporavka još `M`, a oporavljena generacija se flush-uje odmah posle `READY`. Oracle sada čeka da taj flush završi (status, ne `sleep`) i dozvoljava samo dva ishoda: manifest je i dalje `M`, ili je objavljen svež flush iz WAL-a koji nikad ne koristi napušteni privremeni fajl (`docs/ODLUKE.md`).
- **Odluke zapisane.** Svaki izbor koji spec ne fiksira je u `docs/ODLUKE.md`.

### Poznate slabosti i otvorena pitanja

- **Windows.** `fsyncDirectory` ne radi na Windows-u, pa scenariji koji pokreću pravi server (FLS-002, WAL scenariji) lokalno rade samo u Docker-u ili CI-ju; jedinični testovi tabela rade svuda.
- **Status posle neuspelog oporavka.** `/admin/v1/storage/status` i `/admin/v1/status` vraćaju 503 dok mehanizam ne postoji; dijagnostika je u `/health/ready` (`lastFatalError`). Spec traži status samo za mehanizam koji postoji (CP1 §24).
- **Zavisnost od odluke o flush-u posle oporavka.** FLS-002 pretpostavlja da se oporavljena generacija flush-uje posle `READY`; ako tim to promeni, oracle i dalje prihvata oba ishoda, ali čekanje na status traži da immutable generacija nestane.
- **Compaction čitanje.** Brojač `rangeScanBlocksRead` je proveren za `SCAN`; njegovo ponašanje pod compaction-om (CMP testovi) proverava Ilija.
