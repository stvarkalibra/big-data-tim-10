# CP1-API-003 — ograničeno uređeno skeniranje

**Scenario:** `dlsm-tests/src/main/java/rs/raf/dlsm/scenarios/Cp1Api003.java` · **Specifikacija:** CP1 §29.1, §12 · **Odluke:** nema posebne odluke; pravila su iz CP1 §12

**Dokazuje:** poluotvorene granice, redosled po bajtovima ključa, jednu verziju po ključu, izbacivanje tombstone zapisa, čuvanje praznih vrednosti i `limit` posle filtriranja. **Ne dokazuje:** pozicioniranje u velikoj tabeli (SST-004) ni snapshot pod konkurentnim upisima (API-005).

## Pitanja za odbranu

### 1. Zašto je gornja granica isključiva?

`[start, end)` se može deliti na susedne opsege bez preklapanja i bez rupa: `[a, m)` i `[m, z)` zajedno pokrivaju tačno `[a, z)`, svaki ključ jednom. Sa uključivom gornjom granicom ključ na granici bi se pojavio u oba dela, ili bi morao da se izmišlja „sledeći“ ključ. Zato `end` nikad nije emitovan, čak i kada ga izvor vrati, a `start` mora biti strogo manji od `end`. Poređenje je po bajtovima, a ključevi su ASCII, pa je redosled `String.compareTo` isti kao redosled bajtova.

Kod: `MergingIterator` (granica `endExclusive`), `Keys.compare`, `KvRoutes` (provera `start < end`).

### 2. U kojoj fazi se primenjuje `limit` u odnosu na izbor verzije i tombstone zapise?

Poslednji. Spajanje izvora prvo grupiše sve verzije istog ključa i bira pobednika po najvećem rednom broju, zatim izbacuje ključ čiji je pobednik tombstone, i tek onda broji žive ključeve prema `limit`. Da se `limit` primenjuje ranije, zamenjene verzije ili tombstone zapisi bi trošili mesta, pa bi skeniranje vratilo manje živih ključeva nego što je traženo. Scenario to proverava sa `limit=2` posle prepisanog `delta` i obrisanog `beta`.

Kod: `LiveScan.collect`, `MergingIterator`.

## Ako osoblje promeni scenario

| Izmena | Ishod | Zašto |
|---|---|---|
| granični prefiksi (`alpha` / `alphb`, `a` / `alpha`) | obavezan: tačan redosled po bajtovima | ASCII redosled, kraći prefiks ide pre |
| ključ jednak `end` | obavezan: nije u rezultatu | gornja granica je isključiva |
| obrisan ključ u opsegu | obavezan: ne pojavljuje se i ne troši `limit` | tombstone se izbacuje pre brojanja |
| prepisan ključ | obavezan: jedna stavka sa najnovijom vrednošću | jedna verzija po ključu, najveći redni broj |
| prazna vrednost | obavezan: stavka sa praznim Base64 | prazan `PUT` je živ ključ |
| `limit` od 1 do 1000 | obavezan: najviše `limit` živih ključeva, `count` tačan | `limit` posle filtriranja |
| `start >= end` ili `limit` van opsega | obavezan: `400 INVALID_REQUEST` | provera pre čitanja |
