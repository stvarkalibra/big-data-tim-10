# CP1-WAL-001 — prekid posle potvrđenih mutacija

**Scenario:** `dlsm-tests/src/main/java/rs/raf/dlsm/scenarios/Cp1Wal001.java` · **Specifikacija:** CP1 §29.2, §9, §21 · **Odluke:** `docs/ODLUKE.md`, redovi od 22.10.2026. o replay-u (korak 10) i od 23.10.2026. o oracle izborima u WAL scenarijima

**Dokazuje:** I1 i I6: svaka potvrđena mutacija preživljava `SIGKILL`, a redni brojevi se posle oporavka nastavljaju bez sudara. **Ne dokazuje:** ishod mutacije bez odgovora (WAL-005, WAL-006) ni ponašanje sa oštećenim WAL-om (WAL-002, WAL-003).

## Pitanja za odbranu

### 1. Koji trajni objekat štiti poslednju potvrđenu operaciju u trenutku prekida?

Njen kompletan okvir u aktivnom WAL segmentu, nad kojim je `force` uspeo pre nego što je odgovor poslat. MemTable je samo u memoriji i nestaje sa procesom, a manifest i SSTable je još ne pokrivaju: njen redni broj je iznad `flushedThroughSequence`. Pri restartu je oporavak čita iz tog okvira i ponovo primenjuje u oporavljenu generaciju (CP1 §21.1, koraci 7–10).

Kod: `Engine.mutate` (`dlsm-core/src/main/java/rs/raf/dlsm/engine/Engine.java`), `WalSegment.force` (`dlsm-core/src/main/java/rs/raf/dlsm/wal/WalSegment.java`), `WalRecovery.replay` (`dlsm-core/src/main/java/rs/raf/dlsm/recovery/WalRecovery.java`).

### 2. Zašto je uspešan odgovor jači dokaz od „write poziv se završio“?

Završen `write` znači samo da je operativni sistem preuzeo bajtove u svoj keš; pad sistema ili napajanja može ih izgubiti. Mehanizam šalje uspešan odgovor tek posle `force`-a (`FileChannel.force(true)` kroz `StorageIO`), pa su bajtovi tada na trajnom mediju (I1, I2; CP1 §9, korak 7). Brojači to razdvajaju: `walAppendsCompleted` raste posle upisa, a `walForcesSucceeded` tek posle uspešnog force-a. WAL-001 zato proverava da je `walForcesSucceeded` tačno jednak broju potvrđenih mutacija.

Kod: `WalSegment.append` i `WalSegment.force`; redosled koraka u `Engine.mutate` (append → force → MemTable → odgovor, sve pod write lock-om).

## Ako osoblje promeni scenario

| Izmena | Ishod | Zašto |
|---|---|---|
| drugi broj ili mešavina mutacija (prazne vrednosti, brisanja, binarne vrednosti) | obavezan: sve potvrđeno je prisutno, brisanja ostaju odsutna | svaka potvrđena mutacija ima forsiran okvir; tombstone je pravi zapis (I8) |
| `SIGKILL` posle bilo kog potvrđenog odgovora | obavezan: isto | odgovor dolazi tek posle force-a |
| manji `MEMTABLE_MAX_BYTES`, pa deo podataka ode u SSTable pre pada | obavezan: isto logičko stanje; `walRecordsReplayed` broji samo zapise iznad `flushedThroughSequence` | replay preskače ono što manifest već pokriva (CP1 §21.1, korak 10) |
| oporavljena generacija se flush-uje pre drugog pada ili ne | dozvoljeno oboje; logičko stanje isto | oporavljena generacija ide na flush posle `READY` (`ODLUKE.md`, red o `Engine.open`) |
| nova mutacija posle restarta | obavezan: dobija `max(flushedThrough, najveći WAL seq) + 1` i preživljava sledeći pad | `WalRecovery.lastUsedSequence`, I3 |
