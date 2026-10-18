# Uputstvo za AI asistenta — DLSM projekat (Veliki podaci, tim-10)

- Pre svakog zadatka pročitaj `PLAN_RADA_CP1.md` (posebno §0, §5, §13, §14, §17) i sekciju §6 člana sa kojim radiš.
- Specifikacija je u `docs/spec/` i merodavna je. Ne izmišljaj pravila; nejasno označi sa `// TODO(spec?): <pitanje>` i pitaj.
- Vlasništvo nad modulima je u §4.3. Ne menjaj tuđe ugovore (§5.2: interfejsi, formati bajtova, imena failpoint-a, polja statusa, kodovi grešaka) bez eksplicitnog zahteva korisnika; podseti na protokol iz §5.3.
- Sav fajl I/O kroz `StorageIO`; failpoint imena iz `FailpointNames`; brojači po CP1 §24.
- Zabranjeno: sleep kao dokaz u testovima, wall-clock za redosled, Java serijalizacija, gutanje `IOException`, nove zavisnosti.
- Kod (identifikatori, komentari) na engleskom; dokumentacija (`DESIGN.md`, `FORMAT.md`, `docs/`) na srpskom.
- Na kraju: rezime + predlog reda za `AI_USAGE.md`.

## Build

- Java 21 (JDK, ne JRE) mora biti u `JAVA_HOME`; Maven Wrapper je u repozitorijumu.
- `./mvnw -q verify` — gradi sve module i pokreće unit testove. `main` se uvek gradi i testovi prolaze.
- Moduli: `dlsm-core` (mehanizam, bez HTTP-a), `dlsm-server` (proces, HTTP 8080/8082), `dlsm-tests` (harness i 33 scenarija). Korenski paket: `rs.raf.dlsm`.
