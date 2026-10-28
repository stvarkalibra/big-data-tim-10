# CP1-API-005 — skeniranje konkurentno sa mutacijama

**Scenario:** `dlsm-tests/src/main/java/rs/raf/dlsm/scenarios/Cp1Api005.java` · **Specifikacija:** CP1 §29.1, §28.2, §12, §13.1 · **Odluke:** `docs/ODLUKE.md`, red od 20.10.2026. o pet failpoint-a mehanizma i red od 23.10.2026. o radniku održavanja

**Dokazuje:** skeniranje vraća jedan zaštićen snapshot, nikad mešavinu stanja koja nisu postojala u isto vreme, a mutacije sa kojima se preklopilo nisu izgubljene ni posle restarta. **Ne dokazuje:** zaštitu snapshot-a od compaction-a koji bi obrisao ulaze (CMP scenariji).

## Pitanja za odbranu

### 1. Koji objekat ili brava štiti preuzeti pogled?

`engineLock` read lock, koji `Engine.scan` drži tokom celog skeniranja (preporučena osnova iz CP1 §12, korak 1 i §13.1). Pod njim se uzmu izvori: aktivna MemTable, nepromenljiva MemTable i tabele iz trenutnog `EngineView`. Pošto svaka mutacija i svaka objava održavanja traže write lock, nijedan od tih izvora ne može da se promeni dok skeniranje traje. Zato `PUT(beta)` i `DELETE(gamma)` čekaju, pa pauzirano skeniranje vidi tačno `[(alpha,A),(gamma,G)]`, a sledeće tačno `[(alpha,A),(beta,B)]`.

Kod: `Engine.scan`, `EngineView`.

### 2. Kada compaction sme obrisati fajlove koje koristi pauzirano skeniranje?

Tek posle svoje objave, a objava traži write lock, pa ne može da se desi dok bilo koje skeniranje drži read lock. Compaction prvo trajno objavi manifest bez ulaza i zameni pogled pod write lock-om, pa tek onda briše ulazne fajlove (CP1 Dodatak A.3). Svako skeniranje koje počne posle toga dobija novi pogled sa izlazom compaction-a, pa obrisane fajlove više niko ne čita.

Kod: `CompactionJob.publish` (`underEngineWriteLock`) i `CompactionJob.retireInputs`, `Engine.installCompactionResult`.

## Ako osoblje promeni scenario

| Izmena | Ishod | Zašto |
|---|---|---|
| ključevi na granicama blokova ili tabela | obavezan: isti snapshot, tačan redosled | snapshot obuhvata sve izvore pod jednim read lock-om |
| drugi ubačeni ili obrisani ključ | obavezan: pauzirano vidi staro stanje, sledeće novo | mutacije čekaju write lock |
| drugi opseg ili limit | obavezan: tačno stanje iz snapshot-a, sa `limit` posle filtriranja | vidi CP1-API-003 |
| održavanje objavi tabelu dok je skeniranje pauzirano | kod nas objava čeka kraj skeniranja; pauzirano skeniranje je nepromenjeno | objava traži write lock |
| mutacije završe pre otpuštanja | dozvoljeno samo u dizajnu sa odvojenim snapshot-om; kod nas čekaju | spec dozvoljava oba dizajna |
| mešavina `[(alpha,A),(beta,B),(gamma,G)]` | zabranjeno | stanje koje nikad nije postojalo |
| restart posle mutacija | obavezan: `[(alpha,A),(beta,B)]` | obe mutacije su potvrđene posle force-a (I1) |
