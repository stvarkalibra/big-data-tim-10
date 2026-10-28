# CP1-WAL-006 — pad posle kompletnog dopisivanja, pre force-a

**Scenario:** `dlsm-tests/src/main/java/rs/raf/dlsm/scenarios/Cp1Wal006.java` · **Specifikacija:** CP1 §29.2, §9, §21.1 (korak 11) · **Odluke:** `docs/ODLUKE.md`, redovi od 22.10.2026. o replay-u i `lastUsedSequence` i od 23.10.2026. o oracle izborima u WAL scenarijima (WAL-006 vezan za disk)

**Dokazuje:** dvosmislen ishod pre force-a nikad ne daje delimičnu vrednost, ne gubi ranije potvrđene podatke i ne ponavlja redni broj. **Ne dokazuje:** da kandidat mora biti prisutan ili odsutan; pokretanje sme pokazati bilo koje.

## Pitanja za odbranu

### 1. Zašto test ne sme insistirati na odsustvu?

Dopisivanje je završeno, a force nije počeo: bajtovi su predati operativnom sistemu, ali njihova trajnost nije dokazana. Okruženje ih sme sačuvati (pad procesa ostavlja keš OS-a) ili izgubiti (pad sistema), pa specifikacija dozvoljava oba kompletna modela, `{base}` i `{base, candidate = C}`. Zahtev za odsustvom bi oborio ispravan sistem koji je bajtove sačuvao. Obavezno je samo ono što važi u oba modela: `base` je uvek prisutan, kandidat nikad nije delimičan, a redni brojevi se ne sudaraju. Naš test je stroži, ali ne pogrešno: ako je `inspect wal` pre oporavka video kompletan okvir, kandidat mora biti `C`, jer replay nikad ne odbacuje ispravan kompletan okvir (CP1 §21.3).

Kod: `Cp1Wal006.run` (oracle vezan za `inspect wal`), `WalRecovery.replay`.

### 2. Kako oporavak bira `nextSequence` ako je kompletan okvir bez force-a prisutan?

Svaki ispravan kompletan okvir učestvuje: `lastUsedSequence = max(flushedThroughSequence, najveći ispravan WAL seq)`, a `nextSequence` je za jedan veći. Ako je kandidat sa rednim brojem 2 preživeo, sledeći je 3; ako nije, sledeći je 2, i to je dozvoljeno, jer broj 2 tada nije zadržan nigde. Nepotpun okvir ne učestvuje, jer nikad nije postao zapis. Tako kasnija mutacija nikad ne dobija redni broj okvira koji je oporavak zadržao.

Kod: `WalRecovery.lastUsedSequence` (`dlsm-core/src/main/java/rs/raf/dlsm/recovery/WalRecovery.java`).

## Ako osoblje promeni scenario

| Izmena | Ishod | Zašto |
|---|---|---|
| okruženje zadrži bajtove kandidata | dozvoljeno: kandidat tačno `C`, `nextSequence` iznad njega | kompletan okvir se ponovo primenjuje |
| okruženje izgubi bajtove (pad sistema, ne procesa) | dozvoljeno: kandidat odsutan, `nextSequence` iznad `base` | okvir nikad nije bio forsiran |
| okruženje zadrži samo deo okvira | obavezan: nepotpun završetak se seče, kandidat odsutan | CP1-WAL-002 |
| `DELETE` umesto `PUT`, druga veličina zapisa, više prethodnih segmenata | obavezan: isti par dozvoljenih modela | pravilo ne zavisi od vrste ni položaja |
| ponovljen restart | obavezan: kandidat ostaje kakav je bio posle prvog oporavka | oporavak je idempotentan (I6) |
| kasnija potvrđena mutacija | obavezan: preživljava, redni broj iznad svakog zadržanog okvira | I3 |
