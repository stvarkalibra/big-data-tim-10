# Dodatak A — pet failpoint-a mehanizma

Tabela iz CP1 Dodatka A („obaveze dokazivanja bezbednosti pri padu“), popunjena za `CRASH` na pet failpoint-a u `Engine`-u (plan §8, #4–#8; vlasnik Mladen). Na odbrani osoblje bira hook, a član tima mora umeti da odgovori na svih sedam pitanja (plan §16.4).

Zajedničko za svih pet:

- Mutacija drži `engineLock` write lock od koraka 2 do 10 (CP1 §9), pa niko drugi ne vidi njen međustanje pre pada.
- Rotacija se radi u istoj mutaciji, **pre** dodele rednog broja: mutacija koja je pokrenula rotaciju još nema ni redni broj ni WAL okvir.
- Posle pada oporavak (CP1 §21.1) učita manifest, odseče samo nepotpun završni okvir, ponovo primeni svaki ispravan okvir sa rednim brojem većim od `flushedThroughSequence` u jednu oporavljenu generaciju, napravi nov aktivni segment i obriše segmente bez okvira ili potpuno pokrivene manifestom.

Kod: `Engine.mutate`, `Engine.rotateLocked`, `Engine.scan`, `WalRecovery.run`, `RecoveryProcedure.recover`.

## 1. `wal.after-sync.before-memtable`

| Pitanje | Odgovor |
|---|---|
| Koje klijentske operacije su potvrđene? | Sve koje su dobile uspeh pre pogođene mutacije. Pogođena mutacija nema odgovor. |
| Koji bajtovi su možda stigli do trajnog skladišta? | Ceo okvir pogođene mutacije u aktivnom segmentu `lsm/wal/wal-<id>.log`, i force je uspeo. SSTable i manifest nisu dirani. |
| Koji manifest je merodavan? | Isti kao pre mutacije; mutacija ne menja manifest. |
| Koji WAL zapisi se ponovo primenjuju? | Svaki ispravan okvir sa `sequence > flushedThroughSequence`, uključujući pogođenu mutaciju. |
| Koji fajlovi su privremeni ili orphan? | Nijedan novi; mutacija ne pravi fajlove. |
| Šta sme biti dvosmisleno? | Samo odgovor klijentu. Stanje nije dvosmisleno: pogođena mutacija **mora** biti prisutna posle oporavka, jer je force završen. |
| Koja invarijanta uspostavlja bezbednost? | I1 (posle force-a mutacija je trajna), I2 (nije bila vidljiva pre force-a), I6 (ponovni oporavak daje isto). |

## 2. `memtable.after-apply.before-response`

| Pitanje | Odgovor |
|---|---|
| Koje klijentske operacije su potvrđene? | Sve pre pogođene mutacije; pogođena nema odgovor. |
| Koji bajtovi su možda stigli do trajnog skladišta? | Ceo forsiran okvir pogođene mutacije u aktivnom segmentu. Primena na MemTable je samo u memoriji i nestaje sa procesom. |
| Koji manifest je merodavan? | Isti kao pre mutacije. |
| Koji WAL zapisi se ponovo primenjuju? | Svaki ispravan okvir sa `sequence > flushedThroughSequence`, uključujući pogođenu mutaciju. |
| Koji fajlovi su privremeni ili orphan? | Nijedan novi. |
| Šta sme biti dvosmisleno? | Samo odgovor klijentu; mutacija **mora** biti prisutna. Pre pada je niko nije video, jer je write lock još bio zauzet. |
| Koja invarijanta uspostavlja bezbednost? | I1, I2, I6. |

## 3. `rotation.after-new-wal-sync.before-switch`

| Pitanje | Odgovor |
|---|---|
| Koje klijentske operacije su potvrđene? | Sve pre mutacije koja je pokrenula rotaciju; ona nema odgovor. |
| Koji bajtovi su možda stigli do trajnog skladišta? | Nov segment `wal-<novi id>.log` samo sa zaglavljem, napravljen preko `lsm/tmp/`, forsiran i preimenovan pre failpoint-a. Stari segmenti su nepromenjeni; mutacija još nema okvir. |
| Koji manifest je merodavan? | Isti kao pre; rotacija ne menja manifest. |
| Koji WAL zapisi se ponovo primenjuju? | Svaki ispravan okvir sa `sequence > flushedThroughSequence` iz starih segmenata, u jednu oporavljenu generaciju. |
| Koji fajlovi su privremeni ili orphan? | Nov prazan segment ništa ne štiti: oporavak ga briše u koraku 13, a pravi svoj nov aktivni segment. Zaostali fajlovi u `lsm/tmp/` brišu se u koracima 5–6. |
| Šta sme biti dvosmisleno? | Ništa. Mutacija koja je pokrenula rotaciju **mora** biti odsutna, jer nije dobila redni broj ni okvir (CP1 Dodatak A.1: pre force-a sme biti odsutna, ovde nema ni okvira). |
| Koja invarijanta uspostavlja bezbednost? | I6, I9 (`READY` tek sa ispravnim novim aktivnim segmentom), I1 za ranije potvrđene. |

## 4. `rotation.after-switch.before-flush`

| Pitanje | Odgovor |
|---|---|
| Koje klijentske operacije su potvrđene? | Sve pre mutacije koja je pokrenula rotaciju; ona nema odgovor. |
| Koji bajtovi su možda stigli do trajnog skladišta? | Stari segmenti sa svim okvirima stare generacije i nov prazan aktivni segment. Flush nije ni zatražen, pa nema SSTable. |
| Koji manifest je merodavan? | Isti kao pre; `flushedThroughSequence` ne pokriva zamrznutu generaciju. |
| Koji WAL zapisi se ponovo primenjuju? | Svi ispravni okviri sa `sequence > flushedThroughSequence` iz svih segmenata, redom po ID-ju segmenta, u **jednu** oporavljenu generaciju. Podela na aktivnu i zamrznutu generaciju se gubi, i to je u redu: stanje određuju redni brojevi (CP1 §26.2). |
| Koji fajlovi su privremeni ili orphan? | Prazan nov segment se briše u koraku 13. Nema privremenih SSTable, jer flush nije počeo. |
| Šta sme biti dvosmisleno? | Ništa: mutacija koja je pokrenula rotaciju **mora** biti odsutna (nema redni broj), a sve ranije potvrđene prisutne. |
| Koja invarijanta uspostavlja bezbednost? | I3 (pobednik po rednom broju, bez obzira na generaciju), I6. |

## 5. `scan.after-snapshot.before-merge`

| Pitanje | Odgovor |
|---|---|
| Koje klijentske operacije su potvrđene? | Sve mutacije potvrđene pre pada. Skeniranje nema odgovor. Mutacije koje su čekale write lock iza skeniranja nisu dobile redni broj. |
| Koji bajtovi su možda stigli do trajnog skladišta? | Nijedan novi: skeniranje samo čita. |
| Koji manifest je merodavan? | Isti kao pre skeniranja. |
| Koji WAL zapisi se ponovo primenjuju? | Uobičajeno: svaki ispravan okvir sa `sequence > flushedThroughSequence`. |
| Koji fajlovi su privremeni ili orphan? | Nijedan novi. |
| Šta sme biti dvosmisleno? | Ništa u stanju. Mutacije koje su čekale iza skeniranja **moraju** biti odsutne, jer nisu stigle ni do rednog broja ni do WAL-a. |
| Koja invarijanta uspostavlja bezbednost? | I1 i I6: čitanje nema trajni sporedni efekat, pa oporavak vraća tačno potvrđeno stanje. |
