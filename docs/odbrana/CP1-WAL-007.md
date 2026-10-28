# CP1-WAL-007 — replay više WAL segmenata redom

**Scenario:** `dlsm-tests/src/main/java/rs/raf/dlsm/scenarios/Cp1Wal007.java` · **Specifikacija:** CP1 §29.2, §16.1, §16.4, §21.1 · **Odluke:** `docs/ODLUKE.md`, redovi od 18.10.2026. o redosledu provera i kategorijama i od 28.10.2026. o pripremljenom volumenu i oracle izborima u CP1-WAL-007

**Dokazuje:** I3 i I6 preko granica segmenata: pobeđuje najveći redni broj, tombstone iz kasnijeg segmenta ostaje brisanje, praznine u ID-jevima su bezopasne, a ispravni CRC-ovi pojedinačnih okvira ne čine prihvatljivim dnevnik sa pokvarenim redosledom. **Ne dokazuje:** ništa o oštećenom okviru (WAL-003) ni o nepotpunom završetku (WAL-002).

## Pitanja za odbranu

### 1. Šta uspostavlja redosled ID-jeva segmenata, a šta redni broj mutacije?

ID segmenta uređuje **fajlove**: oporavak čita segmente rastućim ID-jem iz imena fajla, nikad redosledom listanja ni po vremenu izmene, a nov segment dobija ID iznad svakog ID-ja nađenog na disku. Redni broj mutacije uređuje **verzije zapisa**: za isti ključ pobeđuje najveći `sequence` (I3), bez obzira na to u kom je segmentu. Dodeljuje ga mehanizam jednom po mutaciji, a posle oporavka nastavlja od `lastUsedSequence + 1`. Oporavak proverava da se ova dva redosleda slažu: čita po ID-ju i traži strogo rastuće redne brojeve i preko granica segmenata. Ako se ne slažu, prijavljuje `WAL_SEQUENCE_ORDER` na tačnom segmentu i offsetu i ne sortira ništa.

Kod: `WalReader.scanSegments` (redosled po ID-ju i prenos poslednjeg rednog broja u sledeći segment), `WalReader.scanSegment` (korak 9), `WalIdAllocator.fromDisk`, `SequenceAllocator` u mehanizmu, `WalRecovery.lastUsedSequence`.

### 2. Zašto su to odvojeni prostori imena?

Zato što opisuju različite stvari i ne rastu zajedno. Segment nosi mnogo mutacija, a ID segmenta raste i kada segment ne nosi nijedan zapis: novi aktivni segment pri svakom oporavku, neuspela rotacija. Zato u ID-jevima nastaju praznine, a praznine su moguće i u rednim brojevima. Kada bi jedan prostor zamenio drugi, redosled fajlova bi odlučivao koja verzija pobeđuje, što I3 izričito zabranjuje: ID segmenta ili tabele nikad ne sme da zameni poređenje rednih brojeva. Odvojeni prostori dozvoljavaju da se praznine u jednom nikad ne tumače kao greška u drugom.

Kod: `WalFileNames` (ID u imenu fajla), `WalRecovery.replay` (primena redom iz dnevnika, pobeđuje najveći `sequence`).

## Ako osoblje promeni scenario

| Izmena | Ishod | Zašto |
|---|---|---|
| drugi neuzastopni ID-jevi segmenata ili drugi redosled pisanja fajlova | obavezan: isto logičko stanje | redosled određuje ID iz imena, vrednost određuje `sequence` |
| druga raspodela zapisa po segmentima, prepisivanja i tombstone na granicama | obavezan: pobeđuje najveći `sequence`, tombstone ostaje brisanje | I3, I8 |
| prazan segment (samo zaglavlje) između ostalih | obavezan: zanemaren i obrisan u koraku 13 | segment bez zapisa ne štiti ništa |
| praznina u rednim brojevima (npr. 1, 2, 5, 6) | obavezan: prihvaćeno, `nextSequence` iznad najvećeg | praznine su dozvoljene (`FORMAT.md` §3.5) |
| ponovljen redni broj preko granice segmenta | obavezan: `FAILED`, `WAL_SEQUENCE_ORDER` na prvom okviru tog segmenta | ne raste strogo |
| opadajući redni broj preko granice ili unutar segmenta | obavezan: `FAILED`, `WAL_SEQUENCE_ORDER` na tom okviru | isto; nikad se ne sortira |
| novi aktivni segment posle oporavka | obavezan: ID iznad najvećeg nađenog (posle 9 je 10), nikad ponovo upotrebljen | `WalIdAllocator.fromDisk` (CP1 §16.1) |
