# Dnevnik odluka tima

Jedan red po odluci koja utiče na više od jedne osobe ili odstupa od preporuke specifikacije (PLAN_RADA_CP1.md §12.1, §5.3). Izbori označeni sa `TEAM CHOICE` u specifikaciji (CP1 §37) obavezno se upisuju ovde i ulaze u `DESIGN.md`.

Format: datum · odluka · ko · zašto. Izmena postojeće odluke je novi red koji se poziva na stari, a stari red se ne briše.

| Datum | Odluka | Ko | Zašto |
|---|---|---|---|
| 18.10.2026. | Zavisnosti: Jackson `jackson-databind` 2.17.2 i JUnit Jupiter 5.11.4, verzije fiksirane u korenskom `pom.xml`. | zatečeno u `pom.xml` | JSON za API, status, manifest i logove; JUnit 5 za unit testove (plan §3.2). Nisu zabranjene zamene iz PI §8.3. |
| 18.10.2026. | `ErrorCode` u `dlsm-core` sadrži samo imena kodova; preslikavanje na HTTP status je u `dlsm-server` (`ErrorEnvelope`). | Mladen | Mehanizam ne sme da zna za HTTP (CP1 §2.1); tabela statusa je iz PI §18.7. |
| 18.10.2026. | `StorageException.category()` je `String` u obliku `UPPER_SNAKE_CASE`, a ne zajednički enum. Spisak kategorija vodi modul koji grešku otkriva (WAL: Matej, SSTable: Kimi, manifest: Ilija). | Mladen | Prati skicu ugovora iz plana §5.1 i vlasništvo iz §5.2; provera oblika hvata greške u kucanju. |
| 18.10.2026. | `retryable` u omotaču greške (TEAM CHOICE, specifikacija ga ne fiksira za CP1 kodove): `true` za `NODE_NOT_READY` i `DEADLINE_EXCEEDED`, `false` za `INVALID_REQUEST`, `VALUE_TOO_LARGE`, `NOT_FOUND`, `STORAGE_IO` i `INTERNAL_ERROR`. | Mladen | Ponovni pokušaj ima smisla samo kad je uzrok prolazan (oporavak u toku, istekao rok). `STORAGE_IO` u CP1 vodi u `FAILED`, iz kog nema povratka bez restarta (CP1 §22), pa ponavljanje istom procesu ne pomaže. Napomena: posle `DEADLINE_EXCEEDED` ishod mutacije može biti nepoznat (PI §17.3). |
