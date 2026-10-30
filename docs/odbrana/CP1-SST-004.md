# CP1-SST-004 — pozicioniranje opsega blizu kraja velike tabele

**Scenario:** `dlsm-tests/src/main/java/rs/raf/dlsm/scenarios/Cp1Sst004.java` · **Specifikacija:** CP1 §29.4, §12, §17.4 · **Odluke:** `docs/ODLUKE.md`, red o brojaču `rangeScanBlocksRead`

**Dokazuje:** `SCAN` se pozicionira kroz retki indeks, pa prolazi samo neophodan uzastopni interval blokova. **Ne dokazuje:** univerzalnu granicu broja blokova za svaki opseg, niti da se SCAN sme zameniti tačkastim upitima.

## Pitanja za odbranu

### 1. Koji prethodnik u indeksu locira prvi blok?

Isto pravilo kao kod tačkastog čitanja: poslednja stavka indeksa čiji je prvi ključ `≤ startInclusive`. Iterator se pozicionira na `offset` te stavke, pročita taj blok i unutar njega preskače ključeve manje od početka. Ako je početak ispred prvog ključa tabele, kreće se od nultog bloka, što je tada ispravno. Zatim ide redom kroz susedne blokove dok ne prikupi `limit` živih ključeva ili ne dođe do `endExclusive`.

Kod: `SSTableIterator` (konstruktor i `hasNext`), `SparseIndex.findBlock`, `DiskSSTableTableSource.iterator` (`dlsm-core/src/main/java/rs/raf/dlsm/sstable/SSTableIterator.java`, `dlsm-core/src/main/java/rs/raf/dlsm/sstable/DiskSSTableTableSource.java`).

### 2. Kako brojači dokazuju da prethodni blokovi nisu čitani?

`rangeScanBlocksRead` se uvećava po bloku pročitanom za `SCAN` i uključen je u `sstableDataBlocksRead`. Posle resetovanja i skeniranja od 20 ključeva u poslednjih 10% tabele od 200+ blokova, brojač mora biti najviše broj blokova koji pokrivaju te ključeve (računa se inspector-om unapred) plus jedan granični blok, i daleko manji od broja blokova tabele. Iterator koji bi krenuo od početka fajla pročitao bi stotine blokova. `SCAN` ne konsultuje Bloom, pa `bloomNegativeSkips` i `bloomPositiveProbes` ostaju nula.

Kod: `SSTableReader.scan`, `StorageStats` (`dlsm-core/src/main/java/rs/raf/dlsm/sstable/SSTableReader.java`, `dlsm-core/src/main/java/rs/raf/dlsm/stats/StorageStats.java`).

## Ako osoblje promeni scenario

| Izmena | Ishod | Zašto |
|---|---|---|
| početak tačno na prvom ključu bloka ili unutar bloka | obavezan: tačni ključevi, blokovi iz inspector-a plus najviše jedan granični | prethodnik je `≤` |
| drugi `limit` ili procenat blizine kraja | obavezan: isto pravilo, drugačiji broj blokova | granica se računa iz pripremljenog skupa |
| tombstone zapisi unutar intervala | dozvoljeni dodatni blokovi; tombstone se ne broji u `limit` | CP1 §12 |
| interval preko više blokova | obavezan: samo uzastopni blokovi | iterator ide redom |
| ključevi iza `endExclusive` | zabranjeni; `end` se nikad ne vraća | poluotvoren interval |
| pojedinačni `GET` za svaki ključ ili materijalizacija cele tabele | zabranjen | krši pozicioniranje kroz indeks |
| brojač `rangeScanBlocksRead` ne raste | zabranjen | SCAN čita blokove, a ne konsultuje Bloom |
