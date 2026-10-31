# CP1-WAL-008 — ponavljani padovi tokom oporavka

**Scenario:** `dlsm-tests/src/main/java/rs/raf/dlsm/scenarios/Cp1Wal008.java` · **Specifikacija:** CP1 §29.2, §21.1, §21.3, §26.3 · **Odluke:** `docs/ODLUKE.md`, redovi od 22.10.2026. o mestima `recovery.*` failpoint-a i o koraku 13, od 31.10.2026. o oracle izborima u CP1-WAL-008

**Dokazuje:** I6: učitavanje manifesta, popravka završetka i replay mogu da se ponavljaju, bilo kojim redom, bez gomilanja sporednih efekata. **Ne dokazuje:** da su direktorijumi bajt po bajt isti posle svakog pokušaja, što se ne traži, ni ponašanje sa oštećenim okvirom (WAL-003).

## Pitanja za odbranu

### 1. Koji koraci oporavka smeju menjati disk?

Samo oni koji imaju dokaz da su bezbedni: prva inicijalizacija dokazano nove baze (koraci 1–3), brisanje privremenih i orphan fajlova (5–6), odsecanje nepotpunog završetka sa force-om (9), pravljenje novog aktivnog segmenta (12) i brisanje praznih i pokrivenih segmenata (13). Klasifikacija, učitavanje manifesta, validacija tabela, skeniranje WAL-a, replay i izbor sledećeg rednog broja samo čitaju. Svaki korak koji piše je ponovljiv: posle odsecanja je segment `VALID`, pa sledeći oporavak ne seče ništa, a nov segment bez zapisa sledeći oporavak klasifikuje kao prazan i briše. Zato pad između bilo koja dva koraka ostavlja stanje iz kog se oporavak samo ponavlja.

Kod: `DataDirectory.open`, `RecoveryProcedure.recover`, `WalRecovery.truncateTornTail`, `WalSegments.createSegment`, `WalRecovery.retireSegments`.

### 2. Zašto je replay namerno odvojen od dopisivanja u nov aktivni WAL?

Replay rekonstruiše memoriju iz zapisa koji su već trajni u starim segmentima, pa nema šta da doda dnevniku. Kada bi ih prepisivao u nov segment, svaki prekinut oporavak bi umnožio zapise i WAL bi rastao sa svakim padom, a isti redni broj u novom segmentu bio bi `WAL_SEQUENCE_ORDER`, pa čvor sledeći put ne bi krenuo. Ovako nov aktivni segment ostaje prazan do prve nove mutacije, a stari segmenti štite oporavljenu generaciju dok je flush ne objavi. Scenario to proverava: posle završnog starta su `walAppendAttempts` i `walBytesWritten` nula, a svi segmenti osim popravljenog imaju samo zaglavlje.

Kod: `WalRecovery.replay`, `WalRecovery.run` (redosled koraka 10–13).

## Ako osoblje promeni scenario

| Izmena | Ishod | Zašto |
|---|---|---|
| drugi redosled hook-ova | obavezan: isti logički rezultat kao kontrola | svaki korak je ponovljiv; scenario pokriva i redosled iz seed-a |
| više ponavljanja istog hook-a | obavezan: isto; ID segmenta raste, a segmenti bez zapisa se brišu | `WalIdAllocator` računa ID sa diska, korak 13 briše prazne |
| pad samo na `recovery.after-manifest-load` | obavezan: završetak ostaje nepotpun na `L` | failpoint je pre koraka 9 |
| pad na `recovery.after-wal-tail-truncate` | obavezan: fajl je tačno `L`, a novog segmenta još nema | popravka je forsirana pre failpoint-a, korak 12 dolazi kasnije |
| pad na `recovery.after-wal-replay.before-ready` | obavezan: nov segment sa samo zaglavljem, a zapisi su samo u memoriji | failpoint je posle koraka 13 (`ODLUKE.md`, 22.10.2026.) |
| bez nepotpunog završetka | obavezan: ništa se ne seče, `walTornTailsTruncated` je 0 | failpoint posle koraka 9 se pogađa pri svakom pokretanju |
| više WAL segmenata ili SSTable tabela | obavezan: isti logički rezultat; replay samo iznad `flushedThroughSequence` | CP1 §21.1, korak 10 |
| drugačiji ID-jevi fajlova nego u kontroli | dozvoljeno | praznine u ID-jevima su bezopasne; poredi se logički sadržaj |
| spremnost tokom prekinutog pokretanja | zabranjeno | `READY` je tek korak 15; scenario proverava da `/health/ready` nikad nije 200 |
