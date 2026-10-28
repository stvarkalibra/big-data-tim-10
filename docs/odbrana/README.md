# Banka odgovora za odbranu

Za svaki od 33 scenarija iz CP1 §29 autor testa piše kratke odgovore na „Pitanja za odbranu“ iz specifikacije (plan §16.2). Na odbrani osoblje bira scenario, menja failpoint, mesto pada ili oštećenja, a tim objašnjava da li je ishod obavezan, zabranjen ili namerno dvosmislen (plan §16.1). Ovi fajlovi su priprema za to i za probnu odbranu.

Fajl nosi ID scenarija: `docs/odbrana/CP1-WAL-001.md`.

## Šablon

```markdown
# CP1-XXX-NNN — naslov iz CP1 §29

**Scenario:** `dlsm-tests/src/main/java/rs/raf/dlsm/scenarios/Cp1XxxNnn.java` · **Specifikacija:** CP1 §29.x · **Odluke:** `docs/ODLUKE.md`, red od <datum> o <temi>

**Dokazuje:** jedna rečenica (invarijanta iz CP1 §7). **Ne dokazuje:** jedno blisko svojstvo.

## Pitanja za odbranu

### 1. <pitanje tačno kao u CP1 §29>

2–5 rečenica. Odgovor se oslanja na specifikaciju i invarijante, a kod je dokaz.

Kod: `Klasa.metoda` (`putanja/do/Klase.java`).

## Ako osoblje promeni scenario

| Izmena | Ishod | Zašto |
|---|---|---|
| … | obavezan / zabranjen / dvosmislen | … |
```

## Pravila

- Pokazivač na kod je `Klasa.metoda` uz putanju, ne broj reda: brojevi redova se pomeraju sa svakom popravkom.
- Svaka tvrdnja mora da važi i za izmenjen scenario. „Tako radi naš kod“ nije obrazloženje; obrazloženje je pravilo iz specifikacije ili odluka iz `docs/ODLUKE.md`.
- Tabela „Ako osoblje promeni scenario“ prati „Varijacije nastavnog osoblja“ iz CP1 §29 za taj scenario.
