# CP1-SST-002 — lažno negativni i lažno pozitivni rezultati Bloom filtera

**Scenario:** `dlsm-tests/src/main/java/rs/raf/dlsm/scenarios/Cp1Sst002.java` · **Specifikacija:** CP1 §29.4, §17.5 · **Odluke:** `FORMAT.md` §6 (parametri filtera)

**Dokazuje:** Bloom je ograničen probabilistički filter bez lažno negativnih rezultata i sa tombstone ključevima unutra. **Ne dokazuje:** da lažno pozitivnih nema, niti da tombstone ulazi u filter samo na osnovu odgovora `GET`.

## Pitanja za odbranu

### 1. Zašto lažno negativan rezultat može izgubiti podatke, dok lažno pozitivan samo košta I/O?

Negativan rezultat preskače tabelu. Ako je pogrešan, preskače se stvarna verzija ključa, pa čitanje vraća stariju vrednost iz druge tabele ili `NOT_FOUND` iako je ključ upisan. To menja odgovor. Lažno pozitivan rezultat samo šalje pretragu u indeks i blok, gde tačno poređenje ključa utvrdi odsustvo, pa je cena jedno ili dva čitanja bloka. Zato projektujemo filter tako da lažno negativnih nema (svaki upisani ključ postavlja svih 7 bitova), a lažno pozitivne ograničavamo na 5% (`10` bitova po ključu, `k=7`).

Kod: `BloomFilter.add`, `BloomFilter.mightContain` (`dlsm-core/src/main/java/rs/raf/dlsm/sstable/BloomFilter.java`).

### 2. Zašto ključevi sa tombstone zapisima moraju ući u filter?

Tombstone je prava verzija ključa (I8). Kada bi bio izostavljen iz filtera, pretraga bi tu tabelu preskočila, pa bi stara vrednost iz starije tabele pobedila i obrisan ključ bi „oživeo“. Zato `SSTableWriter` dodaje u filter svaki sačuvan ključ, bez obzira na vrstu zapisa, a test proverava svaki fizički ključ, uključujući tombstone i prazne vrednosti.

Kod: `SSTableWriter` (dodavanje u filter pri pisanju), `SSTableValidator.validateFully` (provera da nema lažno negativnih za sačuvane ključeve) (`dlsm-core/src/main/java/rs/raf/dlsm/sstable/SSTableWriter.java`, `dlsm-core/src/main/java/rs/raf/dlsm/sstable/SSTableValidator.java`).

## Ako osoblje promeni scenario

| Izmena | Ishod | Zašto |
|---|---|---|
| drugi seed, `N`, `M` ili raspodela ključeva | obavezan: nijedan lažno negativan, `bloomPositiveProbes / M ≤ 0,05` | granica važi za svaki deterministički skup |
| veći udeo tombstone zapisa | obavezan: svi su „možda prisutni“ | filter ne razlikuje vrste zapisa |
| više tabela uz ciljnu tabelu | obavezan: brojači se mere po ciljnoj tabeli | `bloomNegativeSkips + bloomPositiveProbes = M` za jednu konsultovanu tabelu |
| ključ van opsega tabele | ne računa se kao Bloom negativan | odbacuje ga provera opsega |
| filter koji uvek odgovara pozitivno | zabranjen: obara odnos od 5% | zamenski filter nije dozvoljen |
| drugačija verzija heša u metapodacima | dozvoljeno ako je zapisana u fajlu i validirana | CP1 §17.5 |
