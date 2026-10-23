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

### Kako je rad pregledan i proveren

- **Specifikacija pre koda.** Za svaki deo AI je prvo čitao odeljak CP1 (§14, §16, §21, §26.1) i plan §6.2; koraci u kodu nose broj koraka iz specifikacije.
- **Mali commit-ovi, pregled pre svakog.** Grana se pravi pre rada, svaki commit je jedna celina; Matej je pregledao izmenu i sam radio git.
- **Testovi uz svaki deo.** Codec za svaku klasu greške, reader nad ručno pravljenim segmentima, oporavak nad pravim fajlovima (novi, postojeći i oštećeni direktorijumi), ponovljeni padovi na svakom failpoint-u oporavka, testovi čvora preko HTTP-a (restart, flush, oštećen WAL, zaključan direktorijum).
- **Pravi I/O na Linux-u.** Na Windows-u testovi preskaču samo force direktorijuma (`RecordingStorageIO`, `WindowsSafeStorageIO`); testovi sa pravim `DefaultStorageIO` su `@EnabledOnOs(LINUX)`. CI se za sada pokreće samo ručno, pa se oni proveravaju u Linux kontejneru.
- **Scenariji nad pravim procesom.** WAL scenariji su pokrenuti u Linux kontejneru nad pravim server jar-om, svaki za najmanje 5 seed-ova; iz JSON loga servera je provereno da se desilo ono što scenario tvrdi (replay, odsecanje na tačnom offsetu, kategorija oštećenja). Oracle-i ne koriste produkcioni kod: dužine okvira i offseti se računaju iz `FORMAT.md`, a logičko stanje iz `ReferenceModel`-a.
- **Odluke zapisane.** Svaki izbor koji spec ne fiksira je u `docs/ODLUKE.md`.

### Poznate slabosti i otvorena pitanja

- **WAL-001, 002, 003, 005, 006 čekaju registraciju.** Dok ih Andrija ne doda u `ScenarioRegistry`, runner ih prijavljuje kao `SKIPPED`. WAL-004, 007 i 008 još ne postoje.
- **WAL-006 grana „kandidat nedostaje“ nije izvršena.** Pad procesa ne briše keš OS-a, pa je kompletan okvir bez force-a uvek ostao na disku.
- **`RECOVERING` i ubrizgan `IO_ERROR` tokom oporavka nisu testirani na nivou čvora.** Za to treba failpoint naoružan pre pokretanja (`DLSM_TEST_BOOTSTRAP_FAILPOINTS_B64`).
- **Status čvora koji je pao u oporavku je 503.** Dijagnostika je samo u `/health/ready`, dok Kimi ne odluči da li status sme da ima `activeMemtable: null`.
- **Windows.** Pravi server na Windows-u ne prolazi force direktorijuma, pa WAL scenariji lokalno rade samo u Docker-u ili CI-ju.
