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

## Failpoint-i oporavka

Zajedničko za tri `recovery.*` failpoint-a:

- Javni port se otvara tek posle oporavka, pa tokom njega nema novih mutacija. Potvrđene su samo operacije od pre prekida zbog koga se oporavak pokreće.
- Do koraka 15 nema `READY` (I9), a sav rekonstruisan sadržaj je samo u memoriji i nestaje sa procesom.
- Failpoint se pogađa i kada oporavak nema šta da popravi, a aktivira se bootstrap pravilom, jer HTTP API tada ne može da ga naoruža (CP1 §26.3). Sledeći start radi bez pravila i ponavlja oporavak od početka.

Kod: `RecoveryProcedure.recover`, `RecoveryProcedure.openDirectoryAndLoadManifest`, `WalRecovery.run`.

### 4. `recovery.after-manifest-load`

| Pitanje | Odgovor |
|---|---|
| Koje klijentske operacije su potvrđene? | Samo one od pre prekida; ovaj start nije prihvatio nijednu. |
| Koji bajtovi su možda stigli do trajnog skladišta? | Ovaj start nije dirao WAL ni SSTable. Na novoj bazi prva inicijalizacija je već napravila direktorijume, `node.json` i prazan manifest. |
| Koji manifest je merodavan? | Upravo učitani i strukturno provereni `MANIFEST.json`; tabele još nisu validirane. |
| Koji WAL zapisi se ponovo primenjuju? | Još nijedan u ovom startu. Sledeći oporavak primenjuje svaki ispravan okvir sa `sequence > flushedThroughSequence`. |
| Koji fajlovi su privremeni ili orphan? | Isti kao pre starta: sve u `tmp/` i svaki `sst-<id>.sst` koji manifest ne navodi. Čišćenje još nije počelo. Eventualni nepotpun završetak je i dalje na disku. |
| Šta sme biti dvosmisleno? | Ništa novo; ostaje samo ono što je ostavio prethodni prekid. |
| Koja invarijanta uspostavlja bezbednost? | I4 (merodavan je samo manifest), I6, I9. |

### 5. `recovery.after-wal-tail-truncate`

| Pitanje | Odgovor |
|---|---|
| Koje klijentske operacije su potvrđene? | Samo one od pre prekida. |
| Koji bajtovi su možda stigli do trajnog skladišta? | Privremeni i orphan fajlovi su obrisani uz force direktorijuma. Ako je postojao nepotpun završetak, segment je odsečen na `L` i forsiran. Nov segment još ne postoji. |
| Koji manifest je merodavan? | Isti učitani manifest; sve tabele koje navodi su validirane (korak 4). |
| Koji WAL zapisi se ponovo primenjuju? | U ovom startu još nijedan. Sledeći oporavak primenjuje isti skup, svaki ispravan okvir sa `sequence > flushedThroughSequence`, i ne seče ništa više. |
| Koji fajlovi su privremeni ili orphan? | Nijedan: čišćenje je završeno. Fajl nepoznatog imena u `sst/` ostaje, jer nije dokazano privremen. |
| Šta sme biti dvosmisleno? | Ništa novo. Kompletan okvir bez force-a iz prethodnog prekida sada je običan ispravan zapis. |
| Koja invarijanta uspostavlja bezbednost? | I6 (`VALID` segment se ne seče ponovo), I7 (kompletan okvir se nikad ne seče), I9. |

### 6. `recovery.after-wal-replay.before-ready`

| Pitanje | Odgovor |
|---|---|
| Koje klijentske operacije su potvrđene? | Samo one od pre prekida; čvor još nije spreman. |
| Koji bajtovi su možda stigli do trajnog skladišta? | Nov aktivni segment, kompletan i forsiran, sa samo zaglavljem. Prazni i pokriveni stari segmenti su obrisani, uz force `lsm/wal/`. Replay nije upisao ništa. |
| Koji manifest je merodavan? | Isti učitani manifest; replay ga ne menja. |
| Koji WAL zapisi se ponovo primenjuju? | Svi okviri sa `sequence > flushedThroughSequence` su primenjeni samo u memoriji i tu se gube. Sledeći oporavak ih primenjuje ponovo iz istih starih segmenata. |
| Koji fajlovi su privremeni ili orphan? | Nov segment nije ni jedno ni drugo, ali je bez zapisa, pa ga sledeći oporavak briše kao prazan. Njegov ID ostaje potrošen, jer se sledeći ID računa sa diska pre čišćenja. |
| Šta sme biti dvosmisleno? | Ništa novo. ID-jevi segmenata smeju imati praznine, ali logički sadržaj mora biti isti. |
| Koja invarijanta uspostavlja bezbednost? | I6 (replay ne dopisuje kopije), I3 (redni brojevi se ne menjaju), I9 (nema `READY` pre koraka 15). |
