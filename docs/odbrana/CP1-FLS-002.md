# CP1-FLS-002 — pad pre force-a ili konačnog preimenovanja SSTable

**Scenario:** `dlsm-tests/src/main/java/rs/raf/dlsm/scenarios/Cp1Fls002.java` · **Specifikacija:** CP1 §29.3, §18.1, §19.1, §21.1 (koraci 5–6) · **Odluke:** `docs/ODLUKE.md`, red od 25.10.2026. o oracle-u FLS-002 i red od 18.10.2026. o flush-u oporavljene generacije posle `READY`

**Dokazuje:** nepotpun ili privremen SSTable izlaz nikad nije merodavan, a WAL i dalje štiti potvrđene mutacije. **Ne dokazuje:** `flush.*` hook-ove posle objavljivanja (FLS-003 do FLS-005).

## Pitanja za odbranu

### 1. Zašto privremeni fajl nad kojim je force izvršen još nije tabela baze?

Force čini bajtove fajla trajnim, ali ne menja skup tabela. Tabela postoji tek kada je finalizovana (preimenovana u `sst/`) i **navedena u objavljenom manifestu** (I4). Pad na `sstable.after-write.before-sync` ostavlja delimičan ili nesinhronizovan `tmp/sst-<id>.sst.tmp`, a pad na `sstable.after-sync.before-rename` strukturno kompletan, ali još privremen fajl. U oba slučaja manifest je i dalje `M`, a ispravan CRC privremenog fajla ništa ne menja. Oporavak ga briše kao privremen (`temporaryFilesRemoved`).

Kod: `SSTableFinalizer.finalizeTable` (failpoint-i pre force-a i pre preimenovanja), `RecoveryProcedure` (čišćenje `tmp/`, korak 6) (`dlsm-core/src/main/java/rs/raf/dlsm/sstable/SSTableFinalizer.java`, `dlsm-core/src/main/java/rs/raf/dlsm/recovery/RecoveryProcedure.java`).

### 2. Koji objekat štiti upise u svakom podslučaju?

WAL. Flush sme da obriše WAL segmente tek posle trajne objave manifesta koji pokriva njihove zapise (`flushedThroughSequence`), a ovde je pad bio pre objave. Svi segmenti koji nose potvrđene mutacije ostaju, pa replay oporavlja svih šest mutacija iz scenarija, uključujući brisanje `beta`. Ako se oporavljena generacija posle `READY` ponovo flush-uje, to je novi, uspešan flush iz WAL-a sa novim ID-jem tabele, a ne nastavak prekinutog izlaza.

Kod: `FlushJob` (redosled: tabela → manifest → tek onda brisanje WAL-a), `WalRecovery.run` (`dlsm-core/src/main/java/rs/raf/dlsm/maintenance/FlushJob.java`, `dlsm-core/src/main/java/rs/raf/dlsm/recovery/WalRecovery.java`).

## Ako osoblje promeni scenario

| Izmena | Ishod | Zašto |
|---|---|---|
| drugi hook od dva (`before-sync`, `before-rename`) | obavezan: logičko stanje jednako modelu, `tmp/` prazan posle oporavka | privremeni fajl je uvek nemerodavan |
| veći izlaz ili više blokova | obavezan: isto | pravilo ne zavisi od veličine |
| prethodno objavljene nepovezane tabele | obavezan: ostaju u manifestu, nova se ne objavljuje iz napuštenog fajla | I4 |
| manifest posle oporavka | dozvoljeno: ostaje `M`, ili ga je objavio svež flush iz WAL-a (generacija `M + n`, nijedna tabela nije napušteni fajl) | `ODLUKE.md`, 25.10.2026. |
| ponovni pokušaj flush-a posle oporavka | dozvoljen: uspeva, dobija novi ID tabele | alokator ID-ja računa se pre čišćenja (CP1 §18.3) |
| privremeni fajl objavljen ili tretiran kao tabela | zabranjen | krši I4 |
| obrisan zaštitni WAL ili neuspeh oporavka zbog nepotpunog `tmp/` fajla | zabranjen | gubitak podataka |
