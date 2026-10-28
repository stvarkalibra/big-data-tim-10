# CP1-API-004 — konkurentne mutacije istog ključa

**Scenario:** `dlsm-tests/src/main/java/rs/raf/dlsm/scenarios/Cp1Api004.java` · **Specifikacija:** CP1 §29.1, §28.2, §9, §13 · **Odluke:** `docs/ODLUKE.md`, red od 20.10.2026. o pet failpoint-a mehanizma i red od 22.10.2026. o `RETURN_ERROR` i isteku pauze

**Dokazuje:** I2 i I3: mutacija pozvana posle tuđeg WAL force-a dobija veći redni broj, a odložena primena starije mutacije na MemTable ne može da pregazi noviju, ni u memoriji ni posle oporavka. **Ne dokazuje:** snapshot skeniranja (API-005).

## Pitanja za odbranu

### 1. Zašto je A već linearizovan na pauzi?

Tačka linearizacije mutacije je uspešan WAL force (CP1 §9, korak 7). Failpoint `wal.after-sync.before-memtable` je odmah posle force-a, pa kada pređe u `TRIGGERED`, okvir za A je trajan i A ima redni broj. A još nije vidljiv (nije u MemTable) i klijent nema odgovor, ali njegovo mesto u redosledu je određeno: posle pada bi ga oporavak vratio (I1). Zato scenario poziva B tek posle `TRIGGERED`, i B mora dobiti kasniji redni broj.

Kod: `Engine.mutate` (koraci 4–7, pa failpoint), `Engine.failpoint`.

### 2. Zašto poređenje rednih brojeva štiti od kasne primene na MemTable?

Pobednik se nikad ne bira po tome ko je poslednji upisao u memoriju, nego po najvećem rednom broju (I3). Ako bi se A primenio na MemTable posle B, i dalje bi pobedio B, jer ima veći redni broj, i isto važi za SSTable i za replay posle pada. U našem osnovnom dizajnu B ionako čeka: A drži `engineLock` write lock od koraka 2 do 10, pa B ne može ni da dobije redni broj pre nego što A završi. `MemTable.apply` dodatno odbija redni broj koji nije veći od poslednjeg. Scenario proverava i disk: `inspect wal` posle `kill -9` vidi dva okvira u rastućem redosledu, prvo A pa B.

Kod: `Engine.newer`, `MemTable.apply`, `Engine.mutate` (write lock).

## Ako osoblje promeni scenario

| Izmena | Ishod | Zašto |
|---|---|---|
| druge vrednosti ili ključ | obavezan: `GET` vraća B pre pada i posle restarta | B je pozvan posle linearizacije A |
| pauziran zahtev B umesto A (B posle A) | obavezan: pobednik je onaj sa većim rednim brojem | redosled određuje force, ne trenutak odgovora |
| treći pisac posle otpuštanja | obavezan: njegova vrednost pobeđuje | dobija najveći redni broj |
| B dovršen pre otpuštanja A | dozvoljeno samo u konkurentnijem dizajnu; kod nas ne može | kod nas B čeka write lock koji A drži |
| `CRASH` umesto `PAUSE` na istom failpoint-u | obavezan: A prisutan posle restarta, B nije ni poslat | A je forsiran pre pada (vidi CP1-WAL-005) |
| `RETURN_ERROR` na istom failpoint-u | obavezan: A dobija grešku, mehanizam ide u `FAILED`, posle restarta A je prisutan | zapis je trajan, a nije primenjen (`ODLUKE.md`, 22.10.2026.) |
