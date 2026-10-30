# CP1-SST-003 — ograničena pretraga retkim indeksom u velikoj tabeli

**Scenario:** `dlsm-tests/src/main/java/rs/raf/dlsm/scenarios/Cp1Sst003.java` · **Specifikacija:** CP1 §29.4, §11.2, §17.4 · **Odluke:** `FORMAT.md` §8 (retki indeks)

**Dokazuje:** retki indeks, a ne puno skeniranje ni potpuna kopija podataka, bira ograničen kandidat za blok. **Ne dokazuje:** brzinu merenu štopericom niti ponašanje opsežnog čitanja (SST-004).

## Pitanja za odbranu

### 1. Pokažite računanje prethodnika binarnom pretragom za izabrani ključ.

Indeks ima po jednu stavku (`firstKey`, `offset`, `length`) za svaki blok, uređenu po prvom ključu. Binarna pretraga traži poslednju stavku čiji je `firstKey ≤` traženi ključ: u svakom koraku se gleda srednja stavka, ako je njen ključ veći od traženog pretraga ide ulevo, inače se ta stavka pamti i ide se udesno. Ključ koji je tačno jednak prvom ključu bloka pada u taj blok. Ključ ispred prve stavke nema kandidata, pa nema ni čitanja bloka.

Kod: `SparseIndex.findBlock` (`dlsm-core/src/main/java/rs/raf/dlsm/sstable/SparseIndex.java`).

### 2. Koja stavka indeksa i offset fajla su korišćeni u tragu?

Izabrana stavka daje `offset` i `length` jednog bloka. Čitanje je tačno taj opseg bajtova, pa `sstableDataBytesRead` raste za veličinu jednog uokvirenog bloka, ne za veličinu tabele, a `sstableDataBlocksRead` za jedan (najviše dva za pretragu, CP1 §11.2). Validacija pri pokretanju čita sve blokove, ali se računa pre resetovanja brojača, pa ne može da lažira pretragu. Pogodak u kešu OS-a ne menja brojač, jer on beleži logičko čitanje bloka.

Kod: `SSTableReader.get`, `DiskSSTableTableSource.get` (`dlsm-core/src/main/java/rs/raf/dlsm/sstable/SSTableReader.java`, `dlsm-core/src/main/java/rs/raf/dlsm/sstable/DiskSSTableTableSource.java`).

## Ako osoblje promeni scenario

| Izmena | Ishod | Zašto |
|---|---|---|
| ključ blizu početka, sredine ili kraja | obavezan: tačna vrednost, jedan blok (najviše dva) | rad ne zavisi od položaja |
| ključ tačno jednak prvom ključu bloka | obavezan: pogađa taj blok | prethodnik je `≤`, ne `<` |
| veća tabela ili drugačija veličina zapisa | obavezan: isti broj čitanja, drugačiji bajtovi bloka | bajtovi zavise od bloka, ne od tabele |
| zapis veći od ciljne veličine bloka | obavezan: sopstveni blok, jedno čitanje | `FORMAT.md` §7 |
| dodatni restart pre pretrage | obavezan: isto, validacija se računa pre resetovanja | brojači se resetuju nezavisno |
| mereni brojač nula ili nepromenjen | zabranjen | izmišljen brojač ne dokazuje put pristupa |
| čitanje počinje od nultog bloka ili skenira više blokova | zabranjen | krši ograničeno čitanje |
