# CP1-WAL-003 — oštećenje kompletnog okvira

**Scenario:** `dlsm-tests/src/main/java/rs/raf/dlsm/scenarios/Cp1Wal003.java` · **Specifikacija:** CP1 §29.2, §16.3.1, §16.4, §21.4 · **Odluke:** `docs/ODLUKE.md`, redovi od 18.10.2026. o redosledu provera i kategorijama i o konačnom segmentu kraćem od 24 B, od 23.10.2026. o neuspelom oporavku i o oracle izborima u WAL scenarijima

**Dokazuje:** I7: kompletan oštećen okvir ili zaglavlje segmenta je oštećenje, nikad nepotpun završetak, i čvor ne služi nedokazano stanje. **Ne dokazuje:** popravku podataka, koja se ne traži, ni ponašanje stvarno nepotpunog završetka (WAL-002).

## Pitanja za odbranu

### 1. Zašto „blizu EOF-a“ ne dozvoljava odsecanje loše kontrolne sume?

Pravilo odsecanja zavisi od toga da li je okvir kompletan, a ne od toga koliko je blizu kraja fajla. Kompletan okvir je mogao biti forsiran i potvrđen, pa i fizički poslednji okvir može nositi potvrđenu mutaciju; loš CRC tada znači da su bajtovi promenjeni posle upisa. Odsecanje bi tu mutaciju tiho izgubilo i predstavilo oštećenje kao odsustvo (I7). Zato je ishod `CHECKSUM` i `FAILED`, a fajl se ne dira: oporavak ne može da dokaže da skraćivanje čuva potvrđene upise.

Kod: `WalReader.scanSegment` (CRC tek posle provere da ceo okvir staje u fajl), `WalRecovery.scanLog` (oštećenje prekida pokretanje sa svojom kategorijom).

### 2. Koji bajtovi omogućavaju parseru da zna da je samo kompletno zaglavlje oštećeno?

Prvih 8 bajtova okvira, zaštićeno zaglavlje: `totalFrameLength` i njegov komplement (`FORMAT.md` §3.3). Komplement se proverava **pre** EOF-a (korak 2), pa promenjena dužina daje `WAL_LENGTH_GUARD` i kada pokazuje iza kraja fajla, umesto da izgleda kao nepotpun završetak. Dužinu dodatno vezuje uslov `totalFrameLength = 30 + k + v`, jer su `k` i `v` pod CRC-om. Za zaglavlje segmenta to je CRC32C nad bajtovima `0..19`: konačni segment uvek ima celo zaglavlje, jer ime dobija tek posle force-a, pa je svaka greška tu `WAL_HEADER`.

Kod: `WalCodec.checkFrameGuard`, `WalCodec.validateSegmentHeader` (`dlsm-core/src/main/java/rs/raf/dlsm/wal/WalCodec.java`).

## Ako osoblje promeni scenario

| Izmena | Ishod | Zašto |
|---|---|---|
| bajt ključa ili vrednosti pod CRC-om | obavezan: `FAILED`, `CHECKSUM` (ili `WAL_FRAME` ako ključ postane neispravan) | struktura pre CRC-a (`FORMAT.md` §3.4, koraci 5–8) |
| sačuvani CRC zapisa | obavezan: `FAILED`, `CHECKSUM` | korak 8 |
| dužina ili komplement | obavezan: `FAILED`, `WAL_LENGTH_GUARD` | korak 2, pre EOF-a |
| verzija zapisa, rezervisani bajt, vrsta, `k` ili `v` | obavezan: `FAILED`, `WAL_FRAME` | koraci 5–6 |
| magic, verzija, ID ili CRC zaglavlja segmenta | obavezan: `FAILED`, `WAL_HEADER` | `FORMAT.md` §3.2 |
| oštećen okvir je unutrašnji ili fizički poslednji, ali kompletan | obavezan: isto | odlučuje kompletnost, ne položaj |
| `DELETE` umesto `PUT`, druga bit maska | obavezan: isto | kategorija zavisi od polja, ne od sadržaja |
| u svakom slučaju | obavezan: readiness `503`, KV `503 NODE_NOT_READY`, dužina i bajtovi fajla isti, nov segment ne nastaje | CP1 §21.4; oporavak ne stiže do koraka 12 |
