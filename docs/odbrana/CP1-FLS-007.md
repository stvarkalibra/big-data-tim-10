# CP1-FLS-007 — vidljiv backpressure dok je flush pauziran

**Scenario:** `dlsm-tests/src/main/java/rs/raf/dlsm/scenarios/Cp1Fls007.java` · **Specifikacija:** CP1 §29.3, §10, §13.1, §22 · **Odluke:** `docs/ODLUKE.md`, red od 18.10.2026. o `MaintenanceHost` i `reportFatalError` i red od 23.10.2026. o radniku održavanja

**Dokazuje:** granica od jedne nepromenljive generacije: pun mehanizam čeka slobodno mesto umesto da odbaci upis ili napravi još MemTable, pisac koji čeka pušta bravu koju flush treba, a objava flush-a ga budi. **Ne dokazuje:** propusnost.

## Pitanja za odbranu

### 1. Koju bravu pisac koji čeka mora osloboditi?

`engineLock` write lock. Pisac drži write lock od koraka 2 puta mutacije, a kada vidi da je aktivna generacija puna i da je jedino mesto za nepromenljivu generaciju zauzeto, čeka na `rotationSlotFree`, uslov vezan za taj write lock. `awaitNanos` atomski pušta write lock dok čeka i ponovo ga uzima pre nove provere. Flush objavljuje rezultat pod istim write lock-om (`underEngineWriteLock` → `installFlushResult`), oslobađa mesto i signalizira uslov. Da pisac drži bravu dok čeka, čekao bi posao čiji završetak sam sprečava, pa bi to bio deadlock.

Kod: `Engine.awaitRotationSlot`, `Engine.installFlushResult`, `FlushJob.publish`.

### 2. Zašto čitanja smeju nastaviti iako nova rotacija nije moguća?

Backpressure ograničava samo nove upise koji bi tražili rotaciju; postojeći izvori su i dalje ispravni i zaštićeni. Pisac koji čeka nije u redu za `engineLock` (pustio ga je), pa čitanja uzimaju read lock i čitaju aktivnu i nepromenljivu MemTable i objavljene tabele. Nepromenljiva generacija se čita normalno dok je flush ne objavi, a posle objave isti podaci dolaze iz nove SSTable. Zato je `GET` tačan i tokom pauze i posle nje.

Kod: `Engine.get`, `Engine.scan`, `Engine.updatePressureLocked` (`writeBackpressure`).

## Ako osoblje promeni scenario

| Izmena | Ishod | Zašto |
|---|---|---|
| drugi prag ili veličine zapisa (svaki zapis ≥ prag) | obavezan: isto ponašanje | rotacija zavisi od logičke veličine (`32 + ključ + vrednost`) |
| više pisaca koji čekaju | obavezan: `backpressuredWriters` raste; posle otpuštanja svi uspevaju, svaki sa svojim rednim brojem | svi čekaju na isti uslov i svi se bude |
| pauza u drugoj fazi flush-a (npr. `sstable.after-sync.before-rename`) | obavezan: isti backpressure | mesto je zauzeto dok flush ne objavi |
| čitanja i skeniranja tokom backpressure-a | obavezan: uspevaju i tačna su | pisac koji čeka ne drži bravu |
| rok trećeg zahteva kraći od pauze | dozvoljeno: `DEADLINE_EXCEEDED` bez upisa | rok važi i za čekanje (CP1 §13.1); zahtev nije dobio redni broj |
| treći upis potvrđen pre otpuštanja | zabranjeno | nema bezbednog aktivnog para dok je mesto zauzeto |
| druga nepromenljiva generacija tokom pauze | zabranjeno | najviše jedna nepromenljiva generacija (CP1 §10) |
| `CRASH` umesto `PAUSE` | obavezan: posle restarta sve potvrđene vrednosti, privremeni SSTable obrisan | stari WAL je merodavan dok manifest ne objavi tabelu (vidi CP1-FLS-002) |
