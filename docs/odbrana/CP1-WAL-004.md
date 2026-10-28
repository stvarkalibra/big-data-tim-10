# CP1-WAL-004 — ubrizgane greške dopisivanja i force-a

**Scenario:** `dlsm-tests/src/main/java/rs/raf/dlsm/scenarios/Cp1Wal004.java` · **Specifikacija:** CP1 §29.2, §9.3, §26.1 · **Odluke:** `docs/ODLUKE.md`, redovi od 22.10.2026. o odbijanju posle greške u `append`/`force` i o upisu u dva dela, od 28.10.2026. o oracle izborima u CP1-WAL-004

**Dokazuje:** I2 i pravilo fatalnog otkaza puta upisa: mutacija čiji WAL I/O nije uspeo nikad nije potvrđena ni vidljiva, a iza neizvesnog kraja se ništa ne dopisuje. **Ne dokazuje:** da kandidat iz podslučaja (c) mora preživeti ili nestati; pokretanje sme pokazati bilo koje.

## Pitanja za odbranu

### 1. Zašto prijavljena force greška znači neizvestan završetak?

Neuspeo force ne govori koliko je bajtova stiglo na trajni medij: kompletan okvir može ostati u kešu OS-a i preživeti, može biti delimično upisan ili izgubljen. Samo uspeh force-a dokazuje trajnost; njegova greška ne dokazuje ni prisustvo ni odsustvo. Zato klijent ne dobija uspeh i kandidat se ne vidi pre restarta, a posle restarta su dozvoljena tačno dva kompletna modela: kandidat odsutan ili tačno `C`, nikad delimičan. Naš test bira model prema tome šta je `inspect wal` video na disku.

Kod: `WalSegment.force` (greška obeležava neizvestan kraj), `Engine.mutate` (greška WAL-a vodi u `FAILED`).

### 2. Zašto je prelazak u `FAILED` bezbedniji od prostog ponavljanja dopisivanja u isti segment?

Posle greške ne znamo gde je kraj segmenta: tamo može biti prefiks okvira ili ceo neforsiran okvir. Novi okvir iza prefiksa pretvorio bi nepotpun završetak u nepotpun okvir **iza koga postoji okvir**, a to oporavak mora da prijavi kao oštećenje (`WAL_FRAME`), pa čvor više ne bi krenuo. Ponovljen okvir iza celog neforsiranog okvira mogao bi ostaviti dve kopije iste mutacije pod različitim rednim brojevima. `FAILED` zaustavlja upis, a o kraju odlučuje tek oporavak, koji čita disk i seče samo strogi prefiks. Druga, nezavisna zaštita je u samom segmentu: posle prve greške odbija svaki `append` i `force`.

Kod: `WalSegment.append`, `WalSegment.hasUncertainTail` (`dlsm-core/src/main/java/rs/raf/dlsm/wal/WalSegment.java`).

## Ako osoblje promeni scenario

| Izmena | Ishod | Zašto |
|---|---|---|
| `IO_ERROR` na `wal.before-append` | obavezan: nijedan bajt kandidata, kandidat odsutan posle restarta | greška pre prvog upisa; `walBytesWritten` 0 |
| `IO_ERROR` na `wal.after-record-prefix`, bilo koji `prefixBytes` | obavezan: tačno prefiks na disku, oporavak ga seče jednom, kandidat odsutan | strogi prefiks je nepotpun završetak (CP1-WAL-002), ne oštećenje |
| `IO_ERROR` na `wal.after-append.before-sync` | dvosmislen: kandidat odsutan ili tačno `C` | kompletan okvir bez force-a ima nepoznatu trajnost |
| greška na kasnijem pozivu (`afterHits`) | obavezan: isto, za mutaciju koja je pogođena | ranije mutacije su potvrđene posle force-a |
| sledeća operacija je `PUT` ili `DELETE` | obavezan: odbijena (`503 NODE_NOT_READY` kod nas), nije dopisana | mehanizam je `FAILED`; segment odbija rad |
| čitanje kandidata pre restarta | obavezan: nikad vrednost; kod nas `503`, spec dozvoljava i odsustvo | TEAM CHOICE: posle `FAILED` odbijaju se i čitanja |
