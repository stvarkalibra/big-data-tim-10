# CP1-WAL-005 — pad posle force-a, pre objavljivanja u MemTable

**Scenario:** `dlsm-tests/src/main/java/rs/raf/dlsm/scenarios/Cp1Wal005.java` · **Specifikacija:** CP1 §29.2, §9, §21.3 · **Odluke:** `docs/ODLUKE.md`, red od 22.10.2026. o replay-u (korak 10) i redovi od 20.10. i 22.10.2026. o failpoint-ima mehanizma (`wal.after-sync.before-memtable`)

**Dokazuje:** I1 i I2: WAL zapis za koji je force uspeo je merodavan, pa se mutacija vraća posle oporavka i bez odgovora klijentu. **Ne dokazuje:** ništa o mutaciji pre force-a (WAL-006, WAL-004).

## Pitanja za odbranu

### 1. Zašto je ova operacija bez odgovora ipak obavezna posle oporavka?

Failpoint `wal.after-sync.before-memtable` je tačno posle uspešnog force-a, pa je okvir trajan pre pada. Oporavak primenjuje svaki ispravan kompletan okvir iznad `flushedThroughSequence` i ne pita da li je klijent dobio odgovor: WAL je merodavan izvor, a MemTable samo njegova kopija u memoriji. Odsustvo odgovora znači da klijent ne zna ishod, a ne da ishoda nema. Zato `alpha = A` mora da važi posle oba restarta; odsustvo nije dozvoljen dvosmislen ishod.

Kod: `Engine.mutate` (failpoint posle `force`, pre primene u MemTable), `WalRecovery.replay`.

### 2. Gde je bila njena jedina merodavna kopija neposredno posle force-a?

U kompletnom, forsiranom okviru u aktivnom WAL segmentu. MemTable je još nije primila (pad je pre koraka 8), SSTable je ne sadrži, a odgovor nije poslat. Oporavak je čita iz tog okvira i primenjuje u oporavljenu generaciju, bez ponovnog dopisivanja: replay ne podnosi novu mutaciju, nego rekonstruiše postojeću. Sledeći redni broj je zato iznad nje, a nov aktivni segment nastaje pre spremnosti.

Kod: `WalRecovery.replay`, `WalRecovery.lastUsedSequence` (`dlsm-core/src/main/java/rs/raf/dlsm/recovery/WalRecovery.java`).

## Ako osoblje promeni scenario

| Izmena | Ishod | Zašto |
|---|---|---|
| `DELETE` umesto `PUT` | obavezan: ključ odsutan posle restarta | tombstone je pravi zapis (I8) i takođe je forsiran |
| drugi ključ ili vrednost, raniji potvrđeni zapisi | obavezan: sve prisutno, kandidat tačno kakav je poslat | svaki forsiran okvir se ponovo primenjuje |
| više aktiviranja ili drugi zahtev pogođen filtrom | obavezan: svaka mutacija koja je stigla do failpoint-a je prisutna | failpoint je posle force-a |
| `RETURN_ERROR` umesto `CRASH` na istom failpoint-u | obavezan: klijent dobija grešku, mehanizam ide u `FAILED`, posle restarta mutacija je prisutna | zapis je trajan, a nije primenjen (`ODLUKE.md`, 22.10.2026.) |
| drugi pad na `recovery.after-wal-replay.before-ready` | obavezan: posle sledećeg pokretanja isto stanje | oporavak je idempotentan (I6); traži bootstrap failpoint-e (CP1-WAL-008) |
| klijent dobija prekid veze ili nema odgovora | dozvoljeno | pad je pre odgovora; izmišljena greška pre pada nije dozvoljena |
