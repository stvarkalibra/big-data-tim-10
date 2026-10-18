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
