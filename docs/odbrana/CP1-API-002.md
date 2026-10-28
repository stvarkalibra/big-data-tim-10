# CP1-API-002 — brisanje postojećih i nepostojećih ključeva

**Scenario:** `dlsm-tests/src/main/java/rs/raf/dlsm/scenarios/Cp1Api002.java` · **Specifikacija:** CP1 §29.1, §9, §11, §19 · **Odluke:** nema posebne odluke; pravila su iz CP1 §29.1 i I3/I8

**Dokazuje:** I3 i I8: `DELETE` je mutacija sa rednim brojem, i za ključ koji nikad nije postojao, a tombstone ostaje pravi zapis kroz flush i oporavak. **Ne dokazuje:** tombstone kroz više tabela ni kroz compaction (SST-005, CMP-002).

## Pitanja za odbranu

### 1. Zašto brisanje nepostojećeg ključa i dalje mora dodeliti redni broj?

`DELETE` je mutacija kao i `PUT`: ide istim putem (redni broj, WAL okvir, force, MemTable), pa dobija mesto u jednom potpunom redosledu mutacija. Mehanizam ne proverava da li ključ postoji, jer bi to bilo čitanje svih izvora u putu upisa, a odgovor bi zavisio od trenutka provere. Tombstone mora da zaseni svaku stariju verziju, i onu u SSTable koju niko nije pogledao. Zato i `DELETE(ghost)` dobija redni broj, a scenario proverava da su posle `PUT` potrošena još tačno dva.

Kod: `Engine.delete` → `Engine.mutate`.

### 2. Kako bi izbacivanje tombstone zapisa moglo otkriti stariju vrednost iz SSTable?

Čitanje bira pobednika po najvećem rednom broju iz svih izvora (I3): aktivne i nepromenljive MemTable i svake SSTable čiji opseg pokriva ključ. Ako bi tombstone `alpha` nestao iz MemTable ili iz novije tabele, pobednik bi postao stariji `PUT(alpha, A)` iz starije SSTable, pa bi obrisani ključ „oživeo“. Zato flush zapisuje tombstone kao zapis, a CP1 zahteva da se tombstone zadrži kroz ceo CP1 (I8). Scenario to proverava preko `inspect sstable`: posle flush-a tombstone postoji za oba ključa i nijedan nije zapisan kao prazan `PUT`.

Kod: `Engine.get` i `Engine.newer`, `LiveScan.collect` (tombstone pobeđuje, pa se ključ ne emituje).

## Ako osoblje promeni scenario

| Izmena | Ishod | Zašto |
|---|---|---|
| druga imena postojećeg i nepostojećeg ključa | obavezan: oba `DELETE` vraćaju `200`, oba `GET` vraćaju `404 NOT_FOUND` | brisanje ne zavisi od postojanja ključa |
| ponovljeno brisanje istog ključa | obavezan: svako dobija svoj redni broj i `200` | svaki `DELETE` je mutacija |
| prepisivanja pre brisanja | obavezan: `404` posle brisanja, i posle restarta | pobednik je tombstone sa najvećim rednim brojem |
| pregled pre restarta umesto posle | obavezan: tombstone u SSTable za oba ključa | flush zapisuje tombstone kao zapis (I8) |
| stari `PUT(alpha)` nedostaje u tabeli nastaloj flush-om | dozvoljeno | tombstone ga je zamenio unutar iste MemTable generacije |
| `PUT(alpha)` ponovo vidljiv posle flush-a ili restarta | zabranjeno | to bi značilo izgubljen tombstone (I8) |
