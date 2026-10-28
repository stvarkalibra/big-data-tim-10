# CP1-WAL-002 — nepotpun završni okvir na više mesta preseka

**Scenario:** `dlsm-tests/src/main/java/rs/raf/dlsm/scenarios/Cp1Wal002.java` · **Specifikacija:** CP1 §29.2, §16.4, §21.1 (korak 9) · **Odluke:** `docs/ODLUKE.md`, redovi od 18.10.2026. o nepotpunom završetku i od 22.10.2026. o odsecanju (korak 9) i o upisu okvira u dva dela

**Dokazuje:** odseca se samo strogo nepotpun završni okvir, tačno na njegov početak `L`, jednom i trajno; raniji okviri ostaju. **Ne dokazuje:** ništa o kompletnom oštećenom okviru (WAL-003).

## Pitanja za odbranu

### 1. Koji dokaz dozvoljava bezbedno odbacivanje ovog završetka?

Da okvir fizički nije kompletan: ili EOF preseca 8-bajtno zaštićeno zaglavlje dužine (korak 1 iz `FORMAT.md` §3.4), ili je zaštićeno zaglavlje ispravno, a okvir dužine koju ono deklariše prelazi EOF (korak 4). Takav okvir nikad nije forsiran kao celina, a mehanizam odgovara tek posle force-a, pa njegova mutacija nikad nije potvrđena i odbacivanje ne gubi ništa obećano klijentu. Uz to mora biti poslednji okvir **celog** WAL-a: okvir iza koga postoji okvir u kasnijem segmentu ne može nastati padom. Oporavak seče tačno na `L`, odmah radi force i uvećava `walTornTailsTruncated`, pa sledeći oporavak vidi `VALID` i ne seče ništa.

Kod: `WalReader.scanSegment` (koraci 1 i 4), `WalReader.scanSegments` (nepotpun okvir samo na kraju dnevnika), `WalRecovery.truncateTornTail` (`dlsm-core/src/main/java/rs/raf/dlsm/wal/WalReader.java`, `dlsm-core/src/main/java/rs/raf/dlsm/recovery/WalRecovery.java`).

### 2. Zašto bi kompletan okvir sa lošom kontrolnom sumom zahtevao drugačiji ishod?

Kompletan okvir je možda bio forsiran i potvrđen klijentu, pa loš CRC ne znači prekinut upis, već da su upisani bajtovi kasnije promenjeni. Odsecanje bi tiho izgubilo potvrđenu mutaciju i sve iza nje (krši I1 i I6), a oštećenje bi se predstavilo kao odsustvo (I7). Zato je to `CHECKSUM`: pokretanje staje u `FAILED`, a fajl ostaje bajt po bajt isti (CP1-WAL-003). Parser razlikuje dva slučaja redosledom provera: CRC se proverava tek kada je zaštićeno zaglavlje ispravno i ceo okvir staje u fajl.

Kod: `WalCodec.checkFrameGuard`, `WalCodec.decodeFrame` (`dlsm-core/src/main/java/rs/raf/dlsm/wal/WalCodec.java`).

## Ako osoblje promeni scenario

| Izmena | Ishod | Zašto |
|---|---|---|
| bilo koji `prefixBytes` od 1 do dužine okvira − 1 | obavezan: `TORN_TAIL` na `L`, odsecanje tačno na `L`, `torn` odsutan | presek unutar 8 B je korak 1, posle njih korak 4 |
| `prefixBytes` van opsega (0, negativan, ≥ dužina okvira) | obavezan: isto, sa prefiksom svedenim na `1..dužina−1` i logom `WAL_RECORD_PREFIX` | `ODLUKE.md`, red od 22.10.2026. o upisu u dva dela |
| `DELETE` umesto `PUT`, druge dužine ključa ili vrednosti, drugi ID segmenta | obavezan: isto | pravilo zavisi samo od kompletnosti okvira |
| ponovljen pad ili restart posle popravke | obavezan: ništa se više ne seče, `walTornTailsTruncated` je 0 | popravka je forsirana; oporavak je idempotentan (I6) |
| popravljeni segment obrisan kasnijim flush-om | dozvoljeno | segment je pokriven manifestom (CP1 §19.1, korak 12) |
| nepotpun okvir iza koga postoji okvir u kasnijem segmentu (ručno pripremljen) | obavezan: `FAILED`, `WAL_FRAME` | ne može nastati padom (`ODLUKE.md`, 18.10.2026.) |
