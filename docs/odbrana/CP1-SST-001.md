# CP1-SST-001 — čitanje prvog, srednjeg, poslednjeg i odsutnih ključeva

**Scenario:** `dlsm-tests/src/main/java/rs/raf/dlsm/scenarios/Cp1Sst001.java` · **Specifikacija:** CP1 §29.4, §11.2, §17 · **Odluke:** `docs/ODLUKE.md`, red od 18.10.2026. o kategorijama grešaka

**Dokazuje:** lanac pretrage opseg → Bloom → retki indeks → jedan blok → CRC → tačno poređenje ključa i semantiku greške iz I7. **Ne dokazuje:** stopu lažno pozitivnih rezultata (SST-002) ni pozicioniranje u velikoj tabeli (SST-003, SST-004).

## Pitanja za odbranu

### 1. Zašto Bloom prethodi I/O čitanju indeksiranog bloka?

Bloom filter i indeks su u memoriji, pa negativan odgovor košta samo nekoliko heš izračunavanja, a čitanje bloka je I/O. Ako filter kaže „sigurno odsutan“, blok se uopšte ne čita i uvećava se `bloomNegativeSkips`. Ako kaže „možda prisutan“, uvećava se `bloomPositiveProbes` i tek tada se koristi indeks. Pre Bloom filtera ide još jeftinija provera opsega `[minKey, maxKey]`: ključ van opsega ne konsultuje ni filter.

Kod: `DiskSSTableTableSource.get`, `BloomFilter.mightContain` (`dlsm-core/src/main/java/rs/raf/dlsm/sstable/DiskSSTableTableSource.java`, `dlsm-core/src/main/java/rs/raf/dlsm/sstable/BloomFilter.java`).

### 2. Zašto tačno poređenje i dalje mora slediti pozitivan Bloom rezultat?

Filter dozvoljava lažno pozitivne rezultate, pa „možda prisutan“ nije dokaz da ključ postoji. Tek posle čitanja bloka, provere CRC32C i pretrage unutar bloka zna se da li zapis sa tačno tim ključem postoji. Ako ga nema, odgovor je `NOT_FOUND`, a pogrešna proba je koštala najviše dva čitanja bloka. Ako blok ne prolazi proveru, poziv baca `StorageException` (`DATA_BLOCK`), a nikad `NOT_FOUND` (I7).

Kod: `SSTableReader.get` (`dlsm-core/src/main/java/rs/raf/dlsm/sstable/SSTableReader.java`).

## Ako osoblje promeni scenario

| Izmena | Ishod | Zašto |
|---|---|---|
| prvi ili poslednji ključ tabele | obavezan: tačna vrednost, najviše dva bloka | granice opsega su uključene |
| ključ unutar opsega, odsutan | obavezan `NOT_FOUND`; Bloom negativan (0 blokova) ili lažno pozitivan (najviše 2) | oba puta su dozvoljena |
| ključ van opsega | obavezan `NOT_FOUND`, 0 blokova, Bloom ne mora biti konsultovan | provera opsega ide prva |
| prazna vrednost ili ključ čiji je poslednji zapis tombstone | obavezan: prazna vrednost je pronađena, tombstone daje `NOT_FOUND` | prazna vrednost nije odsustvo (CP1 §8) |
| drugačiji raspored blokova ili veličina tabele | obavezan: isto ponašanje, drugačiji brojevi | pravilo ne zavisi od veličine |
| oštećen blok koji ciljni ključ pogađa | zabranjen `NOT_FOUND`; obavezan neuspeh sa kategorijom | I7 |
