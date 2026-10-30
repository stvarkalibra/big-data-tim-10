# CP1-SST-005 — spajanje aktivne i nepromenljive MemTable i više SSTable

**Scenario:** `dlsm-tests/src/main/java/rs/raf/dlsm/scenarios/Cp1Sst005.java` · **Specifikacija:** CP1 §29.4, §11, §12, I3, I8 · **Odluke:** `docs/ODLUKE.md`, red od 18.10.2026. o ugovoru engine ↔ održavanje

**Dokazuje:** pobednika verzije određuje najveći `sequence`, a ne položaj izvora ni ID tabele; tombstone je prava verzija. **Ne dokazuje:** da je dovoljno pogledati samo aktivni izvor, niti ispravnost opštih pravila zaštićenog pogleda.

## Pitanja za odbranu

### 1. Pratite jedan ključ kroz sve izvore i objasnite izbor pobednika.

Primer `alpha`: `A1` u tabeli 1, `A2` u tabeli 2 i `A4` u aktivnoj MemTable. `GET` sakuplja kandidate iz svih izvora koji mogu da sadrže ključ (aktivna, immutable, svaka tabela čiji opseg pokriva ključ) i bira onaj sa najvećim `sequence`, što je `A4`. Položaj izvora i ID tabele se nikad ne porede (zlatno pravilo 3). Isti ključ posle flush-a ili compaction-a samo menja izvor pobednika: `A4` se seli iz aktivne MemTable u tabelu, a logički odgovor ostaje `A4`.

Kod: `Engine.get`, `MergingIterator` (`dlsm-core/src/main/java/rs/raf/dlsm/engine/Engine.java`, `dlsm-core/src/main/java/rs/raf/dlsm/engine/MergingIterator.java`).

### 2. Zašto se tombstone mora razmotriti pre odluke da se pretraga nastavi?

Tombstone je zapis sa svojim `sequence`. Ako je najnoviji kandidat za ključ tombstone, odgovor je `NOT_FOUND`, a pretraga ne sme da nastavi do starije vrednosti, jer bi `beta` ili `gamma` „oživeli“. Zato SSTable čuva tombstone kao zapis i stavlja ga u Bloom filter, a `SSTableReader.get` ga vraća kao kandidata. Tek `Engine` posle poređenja `sequence` vrednosti zaključuje da je ključ obrisan. `SCAN` isto: tombstone pobeđuje starije verzije, pa se ključ ne emituje i ne računa u `limit`.

Kod: `DiskSSTableTableSource.get` (vraća i tombstone), `Engine.get`, `LiveScan` (`dlsm-core/src/main/java/rs/raf/dlsm/sstable/DiskSSTableTableSource.java`, `dlsm-core/src/main/java/rs/raf/dlsm/engine/LiveScan.java`).

## Ako osoblje promeni scenario

| Izmena | Ishod | Zašto |
|---|---|---|
| drugi izvor pobednika za neki ključ | obavezan: pobednik je verzija sa najvećim `sequence` | I3 |
| preklopljeni opsezi tabela ili više tabela | obavezan: isto živo stanje | opseg ne određuje pobednika |
| prazna vrednost ili tombstone kao pobednik | obavezan: prazna vrednost je pronađena, tombstone daje `NOT_FOUND` | I8 |
| `limit` ili granice skeniranja | obavezan: tombstone se ne broji, `end` se ne vraća | CP1 §12 |
| drugačiji ID-jevi ili redosled objavljenih tabela | dozvoljeno | ID tabele ne određuje svežinu |
| `A2` umesto `A4` ili oživeo `beta`/`gamma` | zabranjen | poređenje po redosledu tabela krši I3 |
| rupa u čitanju tokom objavljivanja nepromenljive generacije | zabranjena | zamena pogleda ide pod write lock-om |
