# Dodatak A — šest failpoint-a WAL-a i oporavka

Tabela iz CP1 Dodatka A („obaveze dokazivanja bezbednosti pri padu“), popunjena za `CRASH` na tri `wal.*` i tri `recovery.*` failpoint-a (plan §8, #1–#3 i #19–#21; vlasnik Matej). Na odbrani osoblje bira hook, a član tima mora umeti da odgovori na svih sedam pitanja (plan §16.4).

## Failpoint-i dopisivanja

Zajedničko za tri `wal.*` failpoint-a:

- Sva tri su u mutaciji koja još nije dobila odgovor, pod `engineLock` write lock-om, posle dodele rednog broja i pre force-a koji bi je učinio trajnom (CP1 §9). Niko je nije video.
- Redni broj pogođene mutacije do tada postoji samo u memoriji. Ako njen okvir ne preživi, taj broj nije zadržan nigde, pa sledeća mutacija sme da ga dobije.
- Posle pada oporavak (CP1 §21.1) učita manifest, odseče samo nepotpun završni okvir, ponovo primeni svaki ispravan okvir sa rednim brojem većim od `flushedThroughSequence` u jednu oporavljenu generaciju, napravi nov aktivni segment i obriše segmente bez okvira ili potpuno pokrivene manifestom.

Kod: `WalSegment.append`, `WalSegment.force`, `Engine.mutate`, `WalRecovery.run`.

### 1. `wal.before-append`

| Pitanje | Odgovor |
|---|---|
| Koje klijentske operacije su potvrđene? | Sve koje su dobile uspeh pre pogođene mutacije. Pogođena mutacija nema odgovor. |
| Koji bajtovi su možda stigli do trajnog skladišta? | Nijedan bajt pogođene mutacije: failpoint je pre prvog upisa okvira. Raniji okviri aktivnog segmenta su forsirani. |
| Koji manifest je merodavan? | Isti kao pre mutacije; mutacija ne menja manifest. |
| Koji WAL zapisi se ponovo primenjuju? | Svaki ispravan okvir sa `sequence > flushedThroughSequence`. Pogođena mutacija nema okvir. |
| Koji fajlovi su privremeni ili orphan? | Nijedan novi; mutacija ne pravi fajlove. |
| Šta sme biti dvosmisleno? | Samo odgovor klijentu. Stanje nije dvosmisleno: pogođena mutacija je odsutna, jer ništa nije upisano. |
| Koja invarijanta uspostavlja bezbednost? | I1 (raniji uspesi su forsirani), I2 (pogođena nije bila vidljiva), I6. |

### 2. `wal.after-record-prefix`

| Pitanje | Odgovor |
|---|---|
| Koje klijentske operacije su potvrđene? | Sve pre pogođene mutacije; pogođena nema odgovor. |
| Koji bajtovi su možda stigli do trajnog skladišta? | Tačno `prefixBytes` bajtova okvira pogođene mutacije na kraju aktivnog segmenta (podrazumevano 8), strogi prefiks. Bez force-a mogu da stignu i manje, ali nikad ceo okvir. |
| Koji manifest je merodavan? | Isti kao pre mutacije. |
| Koji WAL zapisi se ponovo primenjuju? | Svaki ispravan okvir sa `sequence > flushedThroughSequence`. Prefiks nije zapis: oporavak ga prijavljuje kao `TORN_TAIL`, seče ga tačno na početak okvira `L`, radi force i uvećava `walTornTailsTruncated`. |
| Koji fajlovi su privremeni ili orphan? | Nijedan fajl. Nemerodavan je samo sufiks segmenta iza `L`. |
| Šta sme biti dvosmisleno? | Samo odgovor klijentu. Pogođena mutacija je odsutna: kompletan okvir nije postojao, pa delimična vrednost ne može da nastane. |
| Koja invarijanta uspostavlja bezbednost? | I1 (seče se samo nepotvrđeno), I6 (popravka je forsirana, pa se ne ponavlja), I7 (kompletan oštećen okvir se nikad ne seče). |

### 3. `wal.after-append.before-sync`

| Pitanje | Odgovor |
|---|---|
| Koje klijentske operacije su potvrđene? | Sve pre pogođene mutacije; pogođena nema odgovor. |
| Koji bajtovi su možda stigli do trajnog skladišta? | Ceo okvir je predat operativnom sistemu, ali force nije izvršen: na disku može biti ceo okvir, njegov prefiks ili ništa od njega. |
| Koji manifest je merodavan? | Isti kao pre mutacije. |
| Koji WAL zapisi se ponovo primenjuju? | Svaki ispravan okvir sa `sequence > flushedThroughSequence`, uključujući pogođenu mutaciju **ako** je njen okvir ceo na disku. Prefiks se seče kao nepotpun završetak. |
| Koji fajlovi su privremeni ili orphan? | Nijedan novi. |
| Šta sme biti dvosmisleno? | Da li je pogođena mutacija prisutna: dozvoljena su tačno dva kompletna modela, bez nje i sa njom. Delimična vrednost nije dozvoljena, a raniji uspesi ostaju. |
| Koja invarijanta uspostavlja bezbednost? | I1 (uspeha nije bilo, pa ništa obećano nije izgubljeno), I2, I3 (`nextSequence` je iznad svakog zadržanog okvira), I6. |
