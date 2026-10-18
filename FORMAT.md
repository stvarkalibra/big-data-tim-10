# FORMAT.md — tačan format trajnih fajlova

Ovaj fajl opisuje tačne bajtove svih fajlova koje mehanizam upisuje na disk (CP1 §15, §31.2). Pisac, čitalac, inspektori i nezavisni fixture alati za testove moraju koristiti **isti** opis; ako se kod i ovaj fajl razlikuju, to je greška koju treba ispraviti u istom PR-u (plan §5.3).

| Deo | Odeljak | Vlasnik |
|---|---|---|
| Zajednička pravila, raspored direktorijuma | §1, §2 | Matej (urednik fajla) |
| WAL segment i okvir zapisa | §3 | Matej |
| `node.json` | §4 | Matej |
| SSTable (Bloom filter, blok podataka, retki indeks) | odeljci „SSTable — …“ ispod | Kimi |
| `MANIFEST.json` | dodaje se | Ilija |

Izmena bilo kog formata je izmena zajedničkog ugovora: najava u grupi, obavezan PR, saglasnost vlasnika i red u `docs/ODLUKE.md` (plan §5.3).

## 1. Zajednička pravila binarnog formata

Važe za svaki binarni format u ovom fajlu (CP1 §15). Zajednički kod za njih je `rs.raf.dlsm.common.Bin`.

| Pravilo | Izbor tima |
|---|---|
| Redosled bajtova | **big-endian** za sve višebajtne cele brojeve. |
| Oznake tipova | `u8`, `u16`, `u32` — neoznačeni celi brojevi od 1, 2 i 4 bajta; `i64` — 64-bitni broj u komplementu dvojke. Redni brojevi mutacija i svi identifikatori (`walSegmentId`, `tableId`) upisuju se kao `i64`, ali su uvek u opsegu `1..2^63-1`. |
| Kontrolna suma | **CRC32C** (Castagnoli, `java.util.zip.CRC32C`), upisana kao `u32`. Svaki format navodi tačan opseg bajtova koji pokriva. Kontrolna vrednost: CRC32C ASCII niza `123456789` je `0xE3069283`. |
| Identifikacija fajla | Fiksni magic bajtovi i eksplicitna verzija formata; čitalac odbija nepoznatu verziju. |
| Polja promenljive dužine | Svakom prethodi dužina sa gornjom granicom. Dužina se proverava **pre** alokacije memorije (`Bin.checkLength`), a deklarisani opseg nikad ne sme da izađe van fizičkog fajla (`Bin.fitsWithin`). |
| Ključ | `1..128` ASCII bajtova iz skupa `[A-Za-z0-9._~-]`, bez terminatora i bez dopune. |
| Vrednost | `0..262144` bajta čiji se sadržaj ne tumači; prazna vrednost (`0` bajtova) nije isto što i tombstone. |
| Zabranjeno | Java serijalizacija objekata, `BitSet` kao format na disku i bilo koji format zavisan od rasporeda klase u memoriji (CP1 §15). |

## 2. Raspored direktorijuma i imena fajlova

Raspored trajnog volumena (CP1 §14):

```text
/var/lib/dlsm/
├── LOCK                      # brava procesa, sadržaj se ne čita
├── node.json                 # identitet čvora, §4
└── lsm/
    ├── MANIFEST.json         # merodavna lista SSTable tabela
    ├── wal/wal-<id>.log      # WAL segmenti, §3
    ├── sst/sst-<id>.sst      # SSTable tabele
    └── tmp/                  # privremeni fajlovi, nikad izvor objavljenog stanja
```

- `<id>` je nenegativan decimalni broj sa tačno **20 cifara**, dopunjen nulama sleva. Primer: segment `1` je `wal-00000000000000000001.log`.
- Ime koje ne odgovara tačno obrascu nije „sintaksno ispravan“ ID i ne učestvuje u dodeli sledećeg ID-ja (CP1 §16.1, §18.3).
- WAL i SSTable ID-jevi su odvojeni prostori imena; praznine u ID-jevima su dozvoljene, ponovna upotreba nije.
- Privremeni i konačni fajlovi su na istom sistemu fajlova, pa je premeštanje iz `tmp/` atomsko.

## 3. WAL (write-ahead log)

Tim usvaja **tačno preporučeni raspored iz CP1 §16.3.1**, bez izmena. WAL segment je fajl koji se sastoji od jednog zaglavlja segmenta (§3.2) i zatim niza okvira zapisa (§3.3), bez razmaka i bez dopune između njih. Segment nema footer: kraj segmenta je fizički kraj fajla.

```text
offset 0                 24
       +-----------------+---------+---------+-----+---------+
       | zaglavlje (24 B)| okvir 1 | okvir 2 | ... | okvir n |  EOF
       +-----------------+---------+---------+-----+---------+
```

### 3.1 Imena, ID-jevi i stvaranje segmenta

| Stavka | Vrednost |
|---|---|
| Konačno ime | `lsm/wal/wal-<id>.log`, `<id>` sa 20 cifara (§2) |
| Privremeno ime pri stvaranju | `lsm/tmp/wal-<id>.log.tmp`, isti `<id>` kao konačno ime |
| Prvi ID na novoj bazi | `1` (CP1 §14.2) |
| Sledeći ID pri pokretanju | `max(1, 1 + najveći sintaksno ispravan WAL ID u wal/ ili tmp/)`, računa se **pre** čišćenja (CP1 §16.1) |

Privremeno ime nosi ID da bi alokator video i ID-jeve zaostalih privremenih fajlova i nikad ih ponovo ne dodelio.

Stvaranje segmenta je mali protokol objavljivanja (CP1 §16.1). Segment ne prihvata nijedan zapis pre koraka 4:

1. upisati kompletno zaglavlje od 24 bajta u `lsm/tmp/wal-<id>.log.tmp`;
2. izvršiti force nad privremenim fajlom;
3. atomski ga premestiti na `lsm/wal/wal-<id>.log`; ako odredište već postoji, to je greška i ništa se ne prepisuje;
4. izvršiti force nad direktorijumom `lsm/wal/`.

Pad pre koraka 3 ostavlja samo privremeni fajl, koji oporavak briše. Pad posle koraka 3 ostavlja ispravan prazan segment (samo zaglavlje, bez okvira), koji oporavak klasifikuje i bezbedno povlači (CP1 §21.1, korak 13).

### 3.2 Zaglavlje segmenta (24 bajta)

| Offset | Veličina | Polje | Tip | Validacija |
|---:|---:|---|---|---|
| `0` | `8` | magic | ASCII | Tačno `LSMWAL01` (`4C 53 4D 57 41 4C 30 31`). |
| `8` | `2` | verzija formata fajla | `u16` | Tačno `1`; svaka druga vrednost se odbija. |
| `10` | `2` | rezervisano | `u16` | Tačno `0` u verziji 1. |
| `12` | `8` | ID segmenta | `i64` | Pozitivan i jednak ID-ju iz imena fajla. |
| `20` | `4` | CRC32C zaglavlja | `u32` | CRC32C nad bajtovima `0..19`. |

Redosled provere pri čitanju: dužina fajla (najmanje 24 bajta) → CRC zaglavlja → magic → verzija → rezervisano polje → ID segmenta prema imenu fajla. Svaki neuspeh je oštećenje kategorije `WAL_HEADER` i prekida pokretanje (CP1 §16.4).

To važi i za konačni segment kraći od 24 bajta. Protokol iz §3.1 dobija konačno ime tek posle force-a kompletnog zaglavlja, pa skraćeno zaglavlje ne može nastati padom i ne tretira se kao nepotpun završetak.

### 3.3 Okvir zapisa

Jedna mutacija (`PUT` ili `DELETE`) je tačno jedan okvir. Oznake `k` i `v` su dužine ključa i vrednosti u bajtovima. Ukupna dužina okvira je `30 + k + v`.

| Offset | Veličina | Polje | Tip | Validacija |
|---:|---:|---|---|---|
| `0` | `4` | `totalFrameLength` | `u32` | `31..262302` i tačno `30 + k + v`. |
| `4` | `4` | komplement dužine | `u32` | Tačno `totalFrameLength XOR 0xFFFFFFFF`. |
| `8` | `2` | verzija zapisa | `u16` | Tačno `1`. |
| `10` | `1` | vrsta | `u8` | `1` = `PUT`, `2` = `DELETE`. |
| `11` | `1` | rezervisano | `u8` | Tačno `0` u verziji 1. |
| `12` | `8` | `mutationSequence` | `i64` | Pozitivan; strogo raste kroz ceo WAL (§3.4). |
| `20` | `2` | dužina ključa `k` | `u16` | `1..128`. |
| `22` | `4` | dužina vrednosti `v` | `u32` | `0..262144` za `PUT`; tačno `0` za `DELETE`. |
| `26` | `k` | bajtovi ključa | ASCII | Važeći ključ projekta (§1). |
| `26 + k` | `v` | bajtovi vrednosti | bajtovi | Samo za `PUT`; prazan `PUT` ima `v = 0`. |
| `26 + k + v` | `4` | CRC32C zapisa | `u32` | CRC32C nad bajtovima `8..(25 + k + v)`. |

Prvih 8 bajtova (dužina i njen komplement) čine **zaštićeno zaglavlje okvira**. Ono omogućava da se dužina proveri pre nego što joj se poveruje (§3.4).

**Šta pokriva CRC zapisa** (TEAM CHOICE iz CP1 §16.3): bajtove od verzije zapisa do poslednjeg bajta ključa ili vrednosti, dakle vrstu, redni broj, obe dužine, ključ i vrednost. Zaštićeno zaglavlje (bajtovi `0..7`) **nije** pokriveno CRC-om; njega štiti komplement, a dodatno ga vezuje uslov `totalFrameLength = 30 + k + v`, jer su `k` i `v` pod CRC-om.

**Granice veličine okvira:**

| Slučaj | `k` | `v` | `totalFrameLength` |
|---|---:|---:|---:|
| Najmanji okvir (`DELETE` ili prazan `PUT` sa ključem od 1 bajta) | `1` | `0` | `31` |
| Najveći okvir (`PUT`, najduži ključ i najveća vrednost) | `128` | `262144` | `262302` |

**`DELETE` i prazan `PUT`** imaju isti raspored i istu dužinu (`v = 0`); razlikuje ih samo polje vrsta. Tombstone je pravi zapis sa sopstvenim rednim brojem, ne prazna vrednost (CP1 I8). Kodovi vrste `1`/`2` isti su kao u SSTable bloku podataka.

### 3.4 Validacija i nepotpun završetak

Čitanje jednog segmenta daje tačno jedan od tri ishoda (CP1 §16.4):

| Ishod | Značenje | Šta se prijavljuje |
|---|---|---|
| `VALID` | Zaglavlje i svi okviri su ispravni, poslednji okvir se završava tačno na EOF-u. Segment sa samo zaglavljem je `VALID` bez zapisa. | lista zapisa |
| `TORN_TAIL` | Svi okviri su ispravni osim poslednjeg, koji je samo prefiks: dopisivanje je prekinuto pre nego što je okvir bio kompletan. | lista ispravnih zapisa, offset početka nepotpunog okvira, dužina fajla |
| `CORRUPT` | Zaglavlje ili neki kompletan okvir ne prolazi proveru. | offset i kategorija prve greške |

#### Algoritam čitanja okvira

Neka je `S` fizička dužina fajla, a `p` offset na kome počinje sledeći okvir (prvi je na `p = 24`). Dok je `p < S`, za svaki okvir se izvršavaju koraci tačno ovim redom. Prvi korak koji ne uspe određuje ishod i čitanje staje; bajtovi iza greške se nikad ne preskaču niti tumače (CP1 §16.4).

| Korak | Provera | Ako ne uspe |
|---:|---|---|
| 1 | Ostalo je najmanje 8 bajtova: `S - p ≥ 8`. | `TORN_TAIL` na offset-u `p` (EOF preseca zaštićeno zaglavlje). |
| 2 | Komplement je ispravan: bajtovi `4..7` = `totalFrameLength XOR 0xFFFFFFFF`. | `CORRUPT`, `WAL_LENGTH_GUARD`. |
| 3 | `totalFrameLength` je u opsegu `31..262302`. | `CORRUPT`, `WAL_LENGTH_GUARD`. |
| 4 | Ceo okvir staje u fajl: `p + totalFrameLength ≤ S`. | `TORN_TAIL` na offset-u `p` (ispravno zaštićeno zaglavlje, a okvir prelazi EOF). |
| 5 | Tek sada se alocira i čita `totalFrameLength` bajtova. Verzija zapisa je `1`, rezervisani bajt je `0`, vrsta je `1` ili `2`. | `CORRUPT`, `WAL_FRAME`. |
| 6 | Unutrašnje dužine objašnjavaju okvir: `k` je u `1..128`, `v` u `0..262144` (`0` za `DELETE`) i `30 + k + v = totalFrameLength`. | `CORRUPT`, `WAL_FRAME`. |
| 7 | Bajtovi ključa čine važeći ključ projekta (§1). | `CORRUPT`, `WAL_FRAME`. |
| 8 | CRC32C nad bajtovima `8..(25 + k + v)` jednak je sačuvanom CRC-u. | `CORRUPT`, `CHECKSUM`. |
| 9 | `mutationSequence` je pozitivan i strogo veći od rednog broja prethodnog ispravnog zapisa, i u ovom i u prethodnim segmentima (§3.5). | `CORRUPT`, `WAL_SEQUENCE_ORDER`. |
| 10 | Okvir je ispravan; `p` se pomera za `totalFrameLength`. | — |

Redosled je namerno ovakav (CP1 §16.3.1): dužina se proverava pre alokacije, a strukturne provere pre CRC-a. Redni broj se proverava posle CRC-a, pa se oštećen redni broj prijavljuje kao `CHECKSUM`, a ne kao loš redosled.

#### Kada je završetak nepotpun, a kada oštećen

Završetak je nepotpun **samo** u dva slučaja iz koraka 1 i 4: kada EOF preseca zaštićeno zaglavlje, ili kada je zaštićeno zaglavlje kompletno i ispravno, ali deklarisani okvir prelazi EOF. Sve ostalo je oštećenje, i kada je pogođen poslednji okvir u fajlu: kompletan okvir sa lošim CRC-om nikad se ne odseca, jer je možda već potvrđen klijentu (CP1 §16.4).

Posledice za testove:

- **Presek bilo gde u okviru** (`wal.after-record-prefix`, CP1-WAL-002): unutar prvih 8 bajtova daje `TORN_TAIL` u koraku 1, a posle njih u koraku 4. U oba slučaja offset nepotpunog okvira je dužina fajla pre tog dopisivanja.
- **Promena jednog bajta u zaštićenom zaglavlju** (CP1-WAL-003 podslučaj c): komplement više ne odgovara, pa korak 2 prijavljuje `WAL_LENGTH_GUARD` **pre** provere EOF-a u koraku 4. Zato oštećena dužina nikad ne može da se predstavi kao nepotpun završetak, čak ni kada pokazuje iza kraja fajla.
- **Promena jednog bajta u ostatku okvira** (podslučajevi a i b): korak 5, 6 ili 7 prijavljuje `WAL_FRAME` kada se pokvari struktura, a inače korak 8 prijavljuje `CHECKSUM`.

#### Gde sme da postoji nepotpun završetak

Nepotpun završetak je dozvoljen samo u **poslednjem okviru celog WAL-a**: nijedan segment sa većim ID-jem ne sme da sadrži okvir. Prekinuto dopisivanje znači da je proces pao ili prešao u `FAILED` i više ništa nije dopisao, a oporavak odseca završetak pre nego što stvori nov segment (CP1 §21.1, koraci 9 i 12). Nepotpun okvir iza koga u kasnijem segmentu postoji okvir zato ne može nastati padom; prijavljuje se kao `CORRUPT`, `WAL_FRAME`, na offset-u nepotpunog okvira. Kasniji segmenti sa samo zaglavljem su dozvoljeni.

Oporavak odseca nepotpun završetak tačno na offset nepotpunog okvira, odmah radi force skraćenog fajla i jednom uvećava `walTornTailsTruncated` (CP1 §21.1, korak 9). Posle toga je segment `VALID`, pa ponovljeni oporavak ne odseca ništa više (CP1-WAL-008).

#### Kategorije grešaka WAL-a

Stabilne vrednosti za `StorageException.category()`, `lastFatalError.code` i logove (`docs/ODLUKE.md`, 18.10.2026.):

| Kategorija | Kada |
|---|---|
| `WAL_HEADER` | Zaglavlje segmenta: kraće od 24 bajta, loš CRC, magic, verzija, rezervisano polje ili ID različit od imena fajla (§3.2). |
| `WAL_LENGTH_GUARD` | Zaštićeno zaglavlje okvira: loš komplement ili dužina van `31..262302` (koraci 2–3). |
| `WAL_FRAME` | Struktura kompletnog okvira: verzija, rezervisani bajt, vrsta, unutrašnje dužine ili ključ (koraci 5–7), kao i nepotpun okvir koji nije na kraju WAL-a. |
| `CHECKSUM` | Kompletan okvir sa ispravnom strukturom i lošim CRC-om (korak 8). |
| `WAL_SEQUENCE_ORDER` | Redni broj nije pozitivan ili ne raste strogo (korak 9, §3.5). |

### 3.5 Redosled kroz više segmenata

Segmenti se čitaju po rastućem ID-ju, nikad po redosledu listanja direktorijuma niti po vremenu izmene. Redni brojevi strogo rastu kroz **ceo** WAL: prvi zapis u segmentu mora imati redni broj veći od poslednjeg zapisa u prethodnom segmentu koji ima zapise. Praznine u ID-jevima segmenata i u rednim brojevima su dozvoljene; jednak ili manji redni broj nije, i oporavak ga nikad ne „popravlja“ sortiranjem (CP1-WAL-007).

### 3.6 Primer: kompletan segment sa tri zapisa

Fajl `lsm/wal/wal-00000000000000000001.log` dužine **129 bajtova** sadrži zaglavlje segmenta `1` i tri zapisa:

| Offset u fajlu | Sadržaj | Dužina |
|---:|---|---:|
| `0` | zaglavlje segmenta, ID `1` | `24` |
| `24` | `PUT(alpha, "A")`, redni broj `1` | `36` |
| `60` | `DELETE(beta)`, redni broj `2` | `34` |
| `94` | `PUT(gamma, "")` (prazna vrednost), redni broj `3` | `35` |

Bajtovi su izračunati nezavisnim programom (JDK `ByteBuffer` i `CRC32C`, bez produkcionog koda). Svi brojevi su heksadecimalni.

**Zaglavlje segmenta** (offset `0`, 24 bajta):

| Bajtovi | Heks | Polje | Vrednost |
|---|---|---|---|
| `0..7` | `4C 53 4D 57 41 4C 30 31` | magic | `LSMWAL01` |
| `8..9` | `00 01` | verzija formata | `1` |
| `10..11` | `00 00` | rezervisano | `0` |
| `12..19` | `00 00 00 00 00 00 00 01` | ID segmenta | `1` |
| `20..23` | `E8 56 78 A9` | CRC32C bajtova `0..19` | `0xE85678A9` |

**`PUT(alpha, "A")`, redni broj 1** (offset u fajlu `24`, `k = 5`, `v = 1`, `totalFrameLength = 30 + 5 + 1 = 36`). Offset-i su relativni u odnosu na početak okvira:

| Bajtovi | Heks | Polje | Vrednost |
|---|---|---|---|
| `0..3` | `00 00 00 24` | `totalFrameLength` | `36` |
| `4..7` | `FF FF FF DB` | komplement | `0x24 XOR 0xFFFFFFFF` |
| `8..9` | `00 01` | verzija zapisa | `1` |
| `10` | `01` | vrsta | `PUT` |
| `11` | `00` | rezervisano | `0` |
| `12..19` | `00 00 00 00 00 00 00 01` | `mutationSequence` | `1` |
| `20..21` | `00 05` | `k` | `5` |
| `22..25` | `00 00 00 01` | `v` | `1` |
| `26..30` | `61 6C 70 68 61` | ključ | `alpha` |
| `31` | `41` | vrednost | `A` |
| `32..35` | `F2 20 F3 D8` | CRC32C bajtova `8..31` | `0xF220F3D8` |

**`DELETE(beta)`, redni broj 2** (offset u fajlu `60`, `k = 4`, `v = 0`, 34 bajta). Razlike u odnosu na `PUT`: vrsta je `02`, `v` je `0` i nema bajtova vrednosti.

```text
00 00 00 22  FF FF FF DD  00 01  02  00  00 00 00 00 00 00 00 02  00 04  00 00 00 00  62 65 74 61  18 19 3F 8C
dužina=34    komplement   ver=1  DEL rez redni broj = 2           k=4    v=0          "beta"       CRC
```

**`PUT(gamma, "")`, redni broj 3** (offset u fajlu `94`, `k = 5`, `v = 0`, 35 bajtova). Ima isti oblik kao `DELETE`; prazna vrednost se od tombstone-a razlikuje **samo** po bajtu vrste (`01`).

```text
00 00 00 23  FF FF FF DC  00 01  01  00  00 00 00 00 00 00 00 03  00 05  00 00 00 00  67 61 6D 6D 61  7E 1E 14 BF
dužina=35    komplement   ver=1  PUT rez redni broj = 3           k=5    v=0          "gamma"         CRC
```

**Ceo fajl** (129 bajtova, 16 po redu):

```text
00000000  4C 53 4D 57 41 4C 30 31 00 01 00 00 00 00 00 00
00000010  00 00 00 01 E8 56 78 A9 00 00 00 24 FF FF FF DB
00000020  00 01 01 00 00 00 00 00 00 00 00 01 00 05 00 00
00000030  00 01 61 6C 70 68 61 41 F2 20 F3 D8 00 00 00 22
00000040  FF FF FF DD 00 01 02 00 00 00 00 00 00 00 00 02
00000050  00 04 00 00 00 00 62 65 74 61 18 19 3F 8C 00 00
00000060  00 23 FF FF FF DC 00 01 01 00 00 00 00 00 00 00
00000070  00 03 00 05 00 00 00 00 67 61 6D 6D 61 7E 1E 14
00000080  BF
```

**Šta bi čitanje (§3.4) prijavilo za izmenjene kopije ovog fajla:**

| Izmena | Ishod | Zašto |
|---|---|---|
| bez izmene | `VALID`, tri zapisa | poslednji okvir se završava tačno na EOF-u (`94 + 35 = 129`) |
| skraćen na `98` bajtova | `TORN_TAIL` na `94` | posle offset-a `94` ostala su 4 bajta, manje od 8 (korak 1) |
| skraćen na `120` bajtova | `TORN_TAIL` na `94` | zaštićeno zaglavlje je ispravno, ali `94 + 35 > 120` (korak 4) |
| bajt `97` promenjen iz `23` u `22` | `CORRUPT`, `WAL_LENGTH_GUARD` na `94` | dužina `34` ne odgovara komplementu `FF FF FF DC` (korak 2) |
| bajt `55` (vrednost `A`) promenjen iz `41` u `42` | `CORRUPT`, `CHECKSUM` na `24` | struktura je ispravna, CRC nije (korak 8) |
| bajt `20` (CRC zaglavlja) promenjen | `CORRUPT`, `WAL_HEADER` na `0` | CRC zaglavlja segmenta ne odgovara (§3.2) |

## 4. `node.json` i `LOCK`

### 4.1 `node.json`

`node.json` čuva stabilan identitet čvora i verziju formata direktorijuma, a ne sadržaj LSM-a (CP1 §14.1). Nalazi se u korenu trajnog direktorijuma (`/var/lib/dlsm/node.json`).

Tačan sadržaj koji mehanizam upisuje (UTF-8 JSON objekat, bez BOM-a):

```json
{"formatVersion":1,"nodeId":"standalone"}
```

| Polje | JSON tip | Pravilo |
|---|---|---|
| `formatVersion` | ceo broj | Tačno `1`. Svaka druga vrednost je nepodržana verzija. |
| `nodeId` | string | `1..64` znaka iz `[A-Za-z0-9._-]` (ista pravila kao `NODE_ID` u `ServerConfig`) i **jednak** konfigurisanom `NODE_ID`. |

Čitalac odbija fajl koji nije ispravan JSON objekat, kome nedostaje neko od dva polja, čije polje ima pogrešan tip ili koji sadrži nepoznato polje. Razmaci i redosled polja nisu bitni za čitanje.

**Upis** (samo pri prvoj inicijalizaciji, CP1 §14.2 korak 2), istom disciplinom kao ostali mali trajni metapodaci:

1. upisati kompletan sadržaj u `lsm/tmp/node.json.tmp`;
2. izvršiti force nad privremenim fajlom;
3. atomski ga premestiti na `node.json` u korenu direktorijuma (isti sistem fajlova);
4. izvršiti force nad korenskim direktorijumom.

Posle prve inicijalizacije `node.json` se **nikad** ne prepisuje niti ponovo stvara (CP1 §14.1).

### 4.2 `LOCK`

`LOCK` je prazan fajl na stabilnoj putanji `/var/lib/dlsm/LOCK`. Njegov sadržaj se ne upisuje i ne čita. Vlasništvo nad direktorijumom daje isključiva brava operativnog sistema (`FileChannel.tryLock()`), koja se drži dok proces živi; samo postojanje fajla ne znači da drugi proces radi, i fajl se ne briše pri gašenju (CP1 §14.1).

### 4.3 Odluke pri pokretanju koje zavise od ovih fajlova

Klasifikacija iz CP1 §14.2. „Stabilni podaci“ su `MANIFEST.json`, bilo koji WAL segment u `wal/` ili bilo koja SSTable u `sst/`.

| Zatečeno stanje | Ishod | Kategorija |
|---|---|---|
| Brava `LOCK` ne može da se dobije | `FAILED` | `DIRECTORY_LOCKED` |
| Nema `node.json` i nema stabilnih podataka (direktorijumi smeju da postoje) | prva inicijalizacija od koraka 1 | — |
| Ispravan i odgovarajući `node.json`, nema manifesta, nema WAL-a ni SSTable | nastavak inicijalizacije od koraka 3 | — |
| Ispravan `node.json` i prazan manifest generacije 0, nema upotrebljivog WAL-a | dodela i force novog WAL segmenta | — |
| Nema `node.json`, a postoje stabilni podaci | `FAILED` | `NODE_IDENTITY_MISMATCH` |
| Ispravan `node.json`, nema `MANIFEST.json`, a postoji bilo koji WAL segment ili SSTable | `FAILED` | `MANIFEST_INVALID` |
| Nedostaje obavezni direktorijum (`lsm/`, `lsm/wal/`, `lsm/sst/`, `lsm/tmp/`), a `node.json` ili manifest već postoji | `FAILED` | `DIRECTORY_LAYOUT` |
| `node.json` je neispravan (JSON, polja, tipovi, nepodržana verzija) | `FAILED` | `NODE_IDENTITY_MISMATCH` |
| `nodeId` se razlikuje od konfigurisanog `NODE_ID` | `FAILED` | `NODE_IDENTITY_MISMATCH` |

Zaostali `lsm/tmp/node.json.tmp` nikad ne nadjačava konačni `node.json` i briše se tek posle ove klasifikacije (CP1 §14.2).

## SSTable — Bloom Filter Format (Version 1)

### Parameters

- Hash algorithm: SHA-256
- Hash version: 1
- Number of hash probes: 7
- Bit count: max(64, 10 * entryCount), rounded up to a multiple of 8
- All multi-byte integers: big-endian
- Bit numbering: least-significant-bit first within each byte

### Hashing

Keys are encoded as ASCII bytes.

The first 8 bytes of the SHA-256 digest form h1.
The following 8 bytes form h2.

h2 is made odd using:

h2 = h2 | 1

For each probe i, where 0 <= i < 7:

position = Long.remainderUnsigned(h1 + i * h2, m)

Java long arithmetic uses 64-bit overflow.

### Binary Format

| Field | Size |
|---|---:|
| Magic ("BLM1") | 4 bytes |
| Hash version | 4 bytes |
| Bit count | 4 bytes |
| Hash function count | 4 bytes |
| Bit array length | 4 bytes |
| Bit array | Variable |
| CRC32C checksum | 4 bytes |

Total serialized size: 24 + bitArrayLength bytes.

CRC32C covers every byte except the final 4-byte checksum.

### Validation

Deserialization rejects:

- Invalid magic
- Unsupported hash version
- Incorrect hash function count
- Invalid bit count
- Incorrect bit array length
- Truncated data
- CRC32C mismatch

### Semantics

Bloom filters may produce false positives but must never
produce false negatives for inserted keys.

Deleted keys (tombstones) must also be included.

A negative Bloom result allows skipping SSTable data-block reads.

A positive Bloom result requires checking the relevant data block.


## SSTable — Data Block Format (v1)

### Constants

- Magic: `DBL1`
- Format version: 1
- Target block payload size: 4096 bytes
- Maximum value size: 262144 bytes
- Byte order: big-endian
- Checksum: CRC32C

### Data Block Layout

| Field | Size |
|---|---:|
| Magic (`DBL1`) | 4 bytes |
| Version | 4 bytes |
| Record count | 4 bytes |
| Payload length | 4 bytes |
| Records | Variable |
| CRC32C | 4 bytes |

CRC32C covers all bytes except the final checksum.

### Record Layout

| Field | Size |
|---|---:|
| Key length | 4 bytes |
| Sequence number | 8 bytes |
| Record kind | 1 byte |
| Value length | 4 bytes |
| Key bytes | Variable |
| Value bytes | Variable |

Record kinds:
- `1` = PUT
- `2` = DELETE (tombstone)

For DELETE, value length is -1 and no value bytes are stored.

For an empty PUT, value length is 0.

Records must be sorted by key in strictly increasing order. Duplicate keys are forbidden inside a block.

An oversized record is stored in its own block.

### Validation

The decoder rejects invalid magic, unsupported versions, truncated records, invalid lengths, duplicate or unsorted keys, invalid sequence numbers, and CRC mismatches.

## SSTable — Sparse Index Format (v1)

### Constants

- Magic: `IDX1`
- Format version: 1
- Byte order: big-endian
- Checksum: CRC32C

### Index Layout

| Field | Size |
|---|---:|
| Magic (`IDX1`) | 4 bytes |
| Version | 4 bytes |
| Entry count | 4 bytes |
| Index entries | Variable |
| CRC32C | 4 bytes |

### Index Entry Layout

| Field | Size |
|---|---:|
| First key length | 4 bytes |
| First key bytes | Variable |
| Block offset | 8 bytes |
| Block length | 4 bytes |

Each entry identifies the first key and physical position of one data block.

Index keys must be strictly increasing. Block ranges must not overlap.

Point lookup uses binary search to locate the entry with the greatest first key less than or equal to the requested key.

If the requested key precedes the first index key, no candidate block exists.

The index does not store every user key.

### Validation

The decoder checks the checksum, format version, entry count, key validity, strictly increasing keys, positive block lengths, nonnegative offsets, and nonoverlapping block ranges.

These formats are version 1 team-defined encodings. SSTableWriter and SSTableReader must use these exact encodings consistently.
