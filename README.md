# tim-10
Big Data 2026/27 — projekat (tim-10)

# DLSM (Distributed Log-Structured Merge-tree) - Milestone 1 (M1)

Ovaj projekat predstavlja implementaciju LSM (Log-Structured Merge-tree) skladišta 
razvijenog u okviru predmeta na RAF-u (Java 21, Maven, Docker).

## Arhitektura projekta
Projekt je podeljen u više Maven modula:
- **`dlsm-core`** — Jezgro sistema, modeli i failpoint mehanizam.
- **`dlsm-server`** — Server, konfiguracija, HTTP rute i API za administraciju.
- **`dlsm-tests`** — Test harness, klijenti i testni scenariji (`CP1-MDL-001`).

## Preduslovi
- **Java 21** (JDK 21)
- **Maven** (projekat koristi lokalni Maven Wrapper `./mvnw` / `mvnw.cmd`)
- **Docker & Docker Compose** (za pokretanje kontejnera)

## 1. Build (Kompilacija projekta)
Za kompilaciju celog projekta bez pokretanja testova, koristi Maven Wrapper:

**Na Linux/macOS/Git Bash:**
```bash
./mvnw clean install -DskipTests
& "C:\Program Files\Git\bin\bash.exe" ./dbctl build --no-cache
& "C:\Program Files\Git\bin\bash.exe" ./dbctl test checkpoint-1 --case CP1-MDL-001 --seed 12345

## Pokretanje testova i komande (`dbctl`)

Sve operacije nad čvorom i testnim okruženjem vrše se preko POSIX skripte `dbctl`:

- **Kompilacija:** `./dbctl build`
- **Pokretanje standalone čvora:** `./dbctl up standalone`
- **Pregled statusa:** `./dbctl status`
- **Pokretanje pojedinačnog testa:** `./dbctl test checkpoint-1 --case CP1-MDL-001 --seed 42`
- **Pregled WAL-a / Manifesta / SSTable-a:** `./dbctl inspect wal` / `manifest` / `sstable <id>`
- **Zaustavljanje čvora:** `./dbctl down`
- **Resetovanje volumena i izveštaja:** `./dbctl reset`
