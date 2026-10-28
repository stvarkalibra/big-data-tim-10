# CP1-API-001 — upis, čitanje, prepisivanje i prazna vrednost

**Scenario:** `dlsm-tests/src/main/java/rs/raf/dlsm/scenarios/Cp1Api001.java` · **Specifikacija:** CP1 §29.1, §9, §11 · **Odluke:** `docs/ODLUKE.md`, redovi od 18.10.2026. o HTTP API-ju (`X-Request-Id`, telo zahteva) i o opsegu `minSequence`/`maxSequence` generacije

**Dokazuje:** I1–I3: svaki potvrđen `PUT` je forsiran pre odgovora, pobeđuje najveći redni broj, a prazna vrednost je sačuvan `PUT`, ne odsustvo. **Ne dokazuje:** preživljavanje pada (WAL scenariji) ni čitanje kroz SSTable.

## Pitanja za odbranu

### 1. Zašto je prazno različito od tombstone zapisa?

Zapis nosi vrstu operacije, a ne samo bajtove: `PUT` sa nula bajtova je vrednost koja postoji, a `DELETE` je tombstone koji kaže da ključ ne postoji. Kada bi se odsustvo zaključivalo iz dužine vrednosti, `PUT(alpha, "")` bi se čitao kao obrisan ključ i `GET` bi vratio `404` umesto `200` sa praznim Base64. Zato `GET` vraća `NOT_FOUND` samo kada je pobednički zapis tombstone, a prazan `PUT` vraća normalno. Ista razlika postoji i na disku: WAL okvir i SSTable zapis čuvaju vrstu, pa se prazna vrednost ne pretvara u brisanje ni posle flush-a ni posle oporavka.

Kod: `Engine.get` (`newest.isTombstone()`), `InternalRecord` i `Kind` (`dlsm-core/src/main/java/rs/raf/dlsm/common/`).

### 2. Zašto MemTable sme zadržati jedan zapis iako WAL sadrži tri?

WAL i MemTable imaju različite poslove. WAL je dnevnik za oporavak: svaka potvrđena mutacija mora biti forsirana pre odgovora (I1), pa su tu sva tri okvira dok ih flush ne pokrije. MemTable je indeks trenutnog stanja: za čitanje u CP1 treba samo pobednička verzija ključa (I3, najveći redni broj), jer nema čitanja po starom rednom broju. Zato `MemTable.apply` zamenjuje prethodnu verziju istog ključa, a redni brojevi rastu strogo. Posle pada replay primeni sva tri okvira redom i opet ostane samo poslednji, pa je rezultat isti.

Kod: `MemTable.apply` (`dlsm-core/src/main/java/rs/raf/dlsm/engine/MemTable.java`), `Engine.mutate` (koraci 5–8).

## Ako osoblje promeni scenario

| Izmena | Ishod | Zašto |
|---|---|---|
| drugi ključ ili drugi binarni obrasci bajtova | obavezan: `GET` vraća tačno poslate bajtove | vrednost se nigde ne pretvara u tekst; Base64 je samo prenos |
| više prepisivanja | obavezan: poslednja vrednost, `nextSequence` = broj mutacija + 1, jedan zapis u MemTable | svaka mutacija troši jedan redni broj; MemTable čuva samo pobednika |
| prazna vrednost na početku ili u sredini | obavezan: tada `GET` vraća prazno (`200`), kasnije novu vrednost | prazan `PUT` je obična verzija u nizu |
| vrednost do javnog maksimuma (262 144 B) | obavezan: uspeh i tačni bajtovi | ograničenje je na dekodiranoj vrednosti (PI §18) |
| vrednost veća od maksimuma | obavezan: `400 VALUE_TOO_LARGE`, bez rednog broja i bez WAL zapisa | validacija je pre bilo kakvog sporednog efekta (CP1 §9, korak 1) |
