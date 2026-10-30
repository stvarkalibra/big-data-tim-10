# CP1-SST-006 — oštećenje objavljenih metapodataka skladišta ili referencirane SSTable

**Scenario:** `dlsm-tests/src/main/java/rs/raf/dlsm/scenarios/Cp1Sst006.java` · **Specifikacija:** CP1 §29.4, §17.3, §21.1 (korak 4), I4, I7, I9 · **Odluke:** `docs/ODLUKE.md`, redovi od 23.10.2026. o dvostrukoj validaciji tabela i o čišćenju pri oporavku

**Dokazuje:** potpuna validacija pri pokretanju: oštećen ili nedostajući referencirani objekat je otkaz baze, a ne odsutan ključ, orphan ni razlog da se manifest rekonstruiše iz imena fajlova. **Ne dokazuje:** automatsku popravku; ispravan ishod je odbijanje rada uz dijagnostiku.

## Pitanja za odbranu

### 1. Zašto Checkpoint 1 plaća O(ukupno SSTable bajtova) pri pokretanju?

Spremnost obećava da je celo objavljeno stanje upotrebljivo. Oštećenje u bloku koji klijent možda nikad ne zatraži ipak krši to obećanje, a bez čitanja svih bajtova ne može da se otkrije. Zato `SSTableValidator.validateFully` strimuje kroz svaki fajl (zaglavlje, svaki blok sa CRC32C, redosled ključeva, indeks, Bloom, footer, granice oblasti, slaganje sa manifestom), bez držanja svih blokova u memoriji. Ta cena je prihvatljiva u CP1; bez nje bi se oštećenje otkrilo tek usred rada, kao pogrešan ili nedostajući odgovor.

Kod: `SSTableValidator.validateFully`, `SSTableRecoveryVerifier.verifyAll` (`dlsm-core/src/main/java/rs/raf/dlsm/sstable/SSTableValidator.java`, `dlsm-core/src/main/java/rs/raf/dlsm/sstable/SSTableRecoveryVerifier.java`).

### 2. Zašto važeća nereferencirana tabela ne može zameniti nedostajuću referenciranu?

Fajl postaje deo baze samo kroz manifest (I4). Drugi ispravan `.sst` u direktorijumu je orphan: možda ostatak prekinutog flush-a ili compaction-a, sa drugim sadržajem. Njegova ispravnost (CRC, struktura) ne dokazuje da sadrži podatke koje je manifest obećao. Zato oporavak ne popunjava prazninu nabrajanjem fajlova, ne pravi „best effort“ manifest, a nedostajuća referenciranu tabelu prijavljuje kao `REFERENCED_FILE_MISSING`, što vodi u `FAILED`. Verifikacija proverava samo tabele iz manifesta; orphan se tek posle toga briše.

Kod: `SSTableRecoveryVerifier.verifyAll`, `RecoveryProcedure` (korak 4 pre čišćenja) (`dlsm-core/src/main/java/rs/raf/dlsm/recovery/RecoveryProcedure.java`).

### 3. Koje granice metapodataka sprečavaju da oštećen offset izazove proizvoljnu dodelu memorije/pozicioniranje?

Footer ima fiksnu veličinu (384 bajta) na `fileSize − 384` i sopstveni CRC32C. Pre bilo kakve alokacije proverava se da oblasti počinju na `16`, da su susedne bez praznina i da se završavaju tačno na početku footera. Dužine blokova i indeksa proveravaju se naspram veličine fajla i najvećih dozvoljenih vrednosti pre `allocate`, a stavke indeksa moraju imati nenegativne offset-e i ne smeju se preklapati. Neuspela provera daje kategoriju (`FOOTER`, `INDEX`, `BLOOM`, `DATA_BLOCK`, `TRUNCATED_REGION`, `MANIFEST_TABLE_MISMATCH`), nikad pokušaj čitanja na slepo.

Kod: `SSTableValidator.validateFully`, `SSTableReader.open`, `BlockCodec` (`dlsm-core/src/main/java/rs/raf/dlsm/sstable/SSTableReader.java`, `dlsm-core/src/main/java/rs/raf/dlsm/sstable/BlockCodec.java`).

## Ako osoblje promeni scenario

| Izmena | Ishod | Zašto |
|---|---|---|
| izmenjen bajt bloka, indeksa, Bloom filtera ili footera | obavezan: `FAILED`, kategorija po oblasti (`DATA_BLOCK`, `INDEX`, `BLOOM`, `FOOTER`) | CRC ili struktura ne prolaze |
| tabela skraćena unutar referencirane oblasti | obavezan: `FAILED`, `TRUNCATED_REGION` | granice oblasti |
| referencirana tabela uklonjena | obavezan: `FAILED`, `REFERENCED_FILE_MISSING` | I4 |
| manifest neispravan ili važeći JSON sa izmenjenim poljem (ID, `sequence`, broj zapisa, veličina) | obavezan: `FAILED`, `MANIFEST_INVALID` ili `MANIFEST_TABLE_MISMATCH` | slaganje sa stvarnom tabelom |
| oštećen samo orphan fajl | dozvoljeno pokretanje; orphan se briše (`orphanFilesRemoved`) | nije referenciran |
| oštećen ključ koji klijent nikad ne traži | obavezan: isti `FAILED` | validacija je potpuna, ne lenja |
| uspešno pokretanje sa zdravim podskupom ili `NOT_FOUND` | zabranjen | I7, I9 |
