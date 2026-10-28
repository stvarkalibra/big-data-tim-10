# CP1-CMP-005 — trka flush-a i izbora ulaza za compaction

**Scenario:** `dlsm-tests/src/main/java/rs/raf/dlsm/scenarios/Cp1Cmp005.java` · **Specifikacija:** CP1 §29.5, §20, §13.1, Dodatak A.3 · **Odluke:** `docs/ODLUKE.md`, redovi od 23.10.2026. o radniku održavanja i o compaction-u

**Dokazuje:** compaction zamenjuje tačno ulaze koje je izabrao i čuva tabelu koju je flush objavio u međuvremenu, pa flush nikad ne gubi podatke zbog zastarelog manifesta. **Ne dokazuje:** padove unutar compaction-a (CMP-003, CMP-004).

## Pitanja za odbranu

### 1. Identifikujte tačno koje ID-jeve compaction sme ukloniti.

Samo ID-jeve koje je izabrao u trenutku izbora: u scenariju dve tabele nastale flush-om pre compaction-a. Skup se pamti (`inputTableIds`, vidljiv u statusu tokom pauze), a pri objavi compaction ponovo proverava da su svi ti ID-jevi i dalje u pogledu i u manifestu. Tabela koju je flush napravio posle izbora nije u tom skupu, pa ostaje. Nova lista tabela je trenutna lista bez izabranih ID-jeva, plus izlaz; isto pravilo važi i za pogled u memoriji.

Kod: `CompactionJob.publish`, `CompactionJob.replace`, `Engine.installCompactionResult`.

### 2. Zašto viša generacija manifesta i dalje dozvoljava bezbedan rebase umesto automatskog prekida?

Bezbednost zavisi od toga da li su izabrani ulazi i dalje objavljeni i nepromenjeni, a ne od toga da li je neko drugi objavio u međuvremenu. `ManifestManager.publish` primenjuje izmenu na trenutni manifest (rebase): uzme najnoviju generaciju, ukloni samo izabrane ulaze, zadrži sve ostalo, uključujući novu flush tabelu, doda izlaz i objavi generaciju +1. Prekid samo zato što je generacija porasla bi odbacio ispravan posao, a poređenje samo generacije bi bilo nedovoljno. Spec to izričito zabranjuje kao jedino pravilo. U našem dizajnu održavanje ionako izvršava jedna nit, pa flush čeka kraj compaction-a; rebase i provera ulaza su dodatna zaštita.

Kod: `ManifestManager.publish` (izmena nad trenutnim manifestom), `MaintenanceWorker` (jedna nit), `CompactionJob.publish`.

## Ako osoblje promeni scenario

| Izmena | Ishod | Zašto |
|---|---|---|
| više izabranih tabela | obavezan: sve izabrane zamenjene jednim izlazom, ostale zadržane | zamena je tačno po skupu izbora |
| flush preklapa opseg ključeva ulaza | obavezan: logičko stanje = stari model + novi ključevi; noviji redni broj pobeđuje | čitanje bira najveći redni broj iz svih tabela (I3) |
| flush počne prvi | obavezan: isti konačni skup referenci | compaction bira od onoga što je tada objavljeno |
| dodatna nepovezana objava | obavezan: ta tabela ostaje u manifestu | rebase čuva sve što nije izabrano |
| flush dobije `409 MAINTENANCE_CONFLICT` | dozvoljeno ako je dokumentovano; scenario ponavlja | kod nas se ne dešava: poslovi čekaju u redu jedne niti |
| flush objavi dok je compaction pauziran | dozvoljeno u preklopljenom dizajnu; kod nas flush čeka | spec dozvoljava oba |
| konačni manifest bez flush tabele ili sa ulazima | zabranjeno | izgubljena tabela ili dvostruka zamena |
