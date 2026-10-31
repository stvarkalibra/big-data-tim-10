# FORMAT.md — tačan format trajnih fajlova

Ovaj fajl opisuje tačne bajtove svih fajlova koje mehanizam upisuje na disk (CP1 §15, §31.2). Pisac, čitalac, inspektori i nezavisni fixture alati za testove moraju koristiti **isti** opis; ako se kod i ovaj fajl razlikuju, to je greška koju treba ispraviti u istom PR-u (plan §5.3).

| Deo | Odeljak | Vlasnik |
|---|---|---|
| Zajednička pravila, raspored direktorijuma | §1, §2 | Matej (urednik fajla) |
| WAL segment i okvir zapisa | §3 | Matej |
| `node.json` | §4 | Matej |
| `MANIFEST.json` i dodela ID-ja tabela | §5 | Ilija |
| SSTable (Bloom filter, blok podataka, retki indeks, ceo fajl) | §6–§9 | Kimi |

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

### 3.7 Pregled: `inspect wal`

`./dbctl inspect wal` prikazuje ceo WAL zaustavljenog volumena (CP1 §25). Implementacija je `rs.raf.dlsm.inspect.InspectWal.run(dataDir, out)`; čita isključivo kroz produkcioni `WalReader`, pa klasifikuje tačno kao oporavak (§3.4, §3.5).

**Samo čita.** Ne skraćuje nepotpun završetak, ne briše privremene ni nepoznate fajlove, ne popravlja oštećenje i ne pokreće čišćenje iz oporavka. Bajtovi vrednosti se nikad ne ispisuju, samo njihova dužina.

**Izlaz** je jedan JSON objekat (UTF-8, polja uvek istim redom):

| Polje | Značenje |
|---|---|
| `walDirectory` | uvek `lsm/wal`, relativno u odnosu na trajni direktorijum |
| `format` | uvek `LSMWAL01` |
| `status` | `VALID`, `TORN_TAIL` ili `CORRUPT` za ceo WAL; `MISSING` ili `IO_ERROR` kada pregled ne može da se izvrši, i tada izlaz ima samo `walDirectory`, `status` i `message` |
| `segmentCount`, `recordCount`, `lastSequence` | broj segmenata, broj ispravnih zapisa i najveći ispravan redni broj (`null` bez zapisa) |
| `tornSegmentId`, `corruptSegmentId` | segment sa nepotpunim završetkom ili sa oštećenjem, inače `null` |
| `otherFiles` | fajlovi u `wal/` koji nisu `wal-<id>.log`, sortirani po imenu |
| `temporarySegments` | zaostali `wal-<id>.log.tmp` u `lsm/tmp/`, sortirani po imenu |
| `segments` | jedan objekat po segmentu, po rastućem numeričkom ID-ju |

Svaki segment sadrži: `file`, `segmentId`, sirova polja zaglavlja (`headerMagic` u heksu, `format`, `formatVersion`, `headerSegmentId`), `headerCrcStatus` (`VALID`, `INVALID` ili `TRUNCATED`), `sizeBytes`, `status`, `validLength`, `tornTail` (`frameOffset`, `eof`, `strayBytes`) ili `null`, `corruption` (`offset`, `category`, `message`) ili `null`, `recordCount`, `minSequence`, `maxSequence` i `records`. Segmenti iza prvog oštećenog se ne dekodiraju i imaju samo `file`, `segmentId`, `status: NOT_SCANNED` i `reason`.

Svaki zapis u `records` sadrži `offset` i `length` okvira u fajlu, `sequence`, `kind` (`PUT` ili `DELETE`), `key`, `tombstone`, `valueLength` i `crcStatus` (uvek `VALID`, jer se navode samo ispravni okviri). **Tombstone ima `tombstone: true` i `valueLength: null`; prazna vrednost ima `tombstone: false` i `valueLength: 0`.**

**Izlazni kodovi:**

| Kod | Kada |
|---:|---|
| `0` | `VALID` ili `TORN_TAIL`; nepotpun završetak se prijavljuje, ali je pregled uspešan |
| `1` | `CORRUPT`, uključujući nepodržanu verziju formata |
| `2` | `lsm/wal` ne postoji (`MISSING`) |
| `3` | čitanje nije uspelo iz drugog razloga (`IO_ERROR`) |

**Primer:** segment iz §3.6 skraćen na 120 bajtova. Izlazni kod je `0`.

```json
{
  "walDirectory" : "lsm/wal",
  "format" : "LSMWAL01",
  "status" : "TORN_TAIL",
  "segmentCount" : 1,
  "recordCount" : 2,
  "lastSequence" : 2,
  "tornSegmentId" : 1,
  "corruptSegmentId" : null,
  "otherFiles" : [ ],
  "temporarySegments" : [ ],
  "segments" : [ {
    "file" : "wal-00000000000000000001.log",
    "segmentId" : 1,
    "headerMagic" : "4C 53 4D 57 41 4C 30 31",
    "format" : "LSMWAL01",
    "formatVersion" : 1,
    "headerSegmentId" : 1,
    "headerCrcStatus" : "VALID",
    "sizeBytes" : 120,
    "status" : "TORN_TAIL",
    "validLength" : 94,
    "tornTail" : {
      "frameOffset" : 94,
      "eof" : 120,
      "strayBytes" : 26
    },
    "corruption" : null,
    "recordCount" : 2,
    "minSequence" : 1,
    "maxSequence" : 2,
    "records" : [ {
      "offset" : 24,
      "length" : 36,
      "sequence" : 1,
      "kind" : "PUT",
      "key" : "alpha",
      "tombstone" : false,
      "valueLength" : 1,
      "crcStatus" : "VALID"
    }, {
      "offset" : 60,
      "length" : 34,
      "sequence" : 2,
      "kind" : "DELETE",
      "key" : "beta",
      "tombstone" : true,
      "valueLength" : null,
      "crcStatus" : "VALID"
    } ]
  } ]
}
```

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

Klasifikacija iz CP1 §14.2. „Stabilni podaci“ su `MANIFEST.json` ili **bilo koji** unos u `lsm/wal/` ili `lsm/sst/`, i kada ime nije ispravno ime segmenta ili tabele. Fajlovi u `lsm/tmp/` se nikad ne računaju. Klasifikacija se radi pod `LOCK`-om i samo čita; tek posle nje se bilo šta pravi, briše ili popravlja (implementacija: `recovery.StartupClassifier`).

| Zatečeno stanje | Ishod | Kategorija |
|---|---|---|
| Brava `LOCK` ne može da se dobije | `FAILED` | `DIRECTORY_LOCKED` |
| Nema `node.json` i nema stabilnih podataka (direktorijumi smeju da postoje) | prva inicijalizacija od koraka 1 | — |
| Ispravan i odgovarajući `node.json`, nema manifesta, nema WAL-a ni SSTable | nastavak inicijalizacije od koraka 3 | — |
| Ispravan `node.json` i prazan manifest generacije 0, nema upotrebljivog WAL-a | normalan oporavak, koji u koraku 12 dodeljuje i radi force novog WAL segmenta | — |
| Nema `node.json`, a postoje stabilni podaci | `FAILED` | `NODE_IDENTITY_MISMATCH` |
| Ispravan `node.json`, nema `MANIFEST.json`, a postoji bilo koji WAL segment ili SSTable | `FAILED` | `MANIFEST_INVALID` |
| Nedostaje obavezni direktorijum (`lsm/`, `lsm/wal/`, `lsm/sst/`, `lsm/tmp/`), a `node.json` ili manifest već postoji | `FAILED` | `DIRECTORY_LAYOUT` |
| `node.json` je neispravan (JSON, polja, tipovi, nepodržana verzija) ili nije običan fajl | `FAILED` | `NODE_IDENTITY_MISMATCH` |
| `nodeId` se razlikuje od konfigurisanog `NODE_ID` | `FAILED` | `NODE_IDENTITY_MISMATCH` |

Zaostali `lsm/tmp/node.json.tmp` nikad ne nadjačava konačni `node.json` i briše se tek posle ove klasifikacije (CP1 §14.2).

## 5. `MANIFEST.json`

Manifest je zvanična lista objavljenih SSTable tabela i granica do koje su mutacije već prenete u SSTable (CP1 §18). Fajl postaje deo baze **samo** ako ga trenutni manifest navodi; finalizovana tabela koju manifest ne navodi je orphan (I4). Manifest je uvek **kompletan snimak** stanja, a ne dnevnik izmena. Nalazi se na `lsm/MANIFEST.json`. Implementacija: paket `rs.raf.dlsm.manifest`.

### 5.1 Sadržaj

UTF-8 JSON objekat, bez BOM-a. Mehanizam piše kompaktan oblik (bez razmaka), sa poljima ovim redosledom:

```json
{"formatVersion":1,"generation":7,"nextTableId":12,"flushedThroughSequence":812,"sstables":[{"tableId":11,"file":"sst-00000000000000000011.sst","minKey":"customer.0001","maxKey":"customer.0999","minSequence":401,"maxSequence":812,"entryCount":377,"sizeBytes":28194}]}
```

Početni manifest nove baze (CP1 §14.2, korak 3 inicijalizacije) je tačno:

```json
{"formatVersion":1,"generation":0,"nextTableId":1,"flushedThroughSequence":0,"sstables":[]}
```

| Polje | JSON tip | Pravilo |
|---|---|---|
| `formatVersion` | ceo broj | Tačno `1`. Svaka druga vrednost je nepodržana verzija. |
| `generation` | ceo broj (`i64`) | `≥ 0`. Početni manifest ima `0`; svaka objava povećava je za tačno `1`. |
| `nextTableId` | ceo broj (`i64`) | `≥ 1`. Veći od svakog `tableId` rezervisanog pre upisa ovog manifesta; nikad se ne smanjuje. |
| `flushedThroughSequence` | ceo broj (`i64`) | `≥ 0`. Svaka važeća mutacija sa `sequence ≤` ove vrednosti već je ugrađena u objavljene tabele (CP1 §18). Pomera ga samo flush; compaction ga ne menja. Nikad se ne smanjuje. |
| `sstables` | niz objekata | Ceo merodavni skup tabela, ne razlika u odnosu na prethodni manifest. Može biti prazan. |

Polja jedne tabele u `sstables` (isti sadržaj kao `sstable.SSTableMeta`):

| Polje | JSON tip | Pravilo |
|---|---|---|
| `tableId` | ceo broj (`i64`) | `≥ 1` i `< nextTableId`. |
| `file` | string | Tačno `sst-<tableId sa 20 cifara>.sst` (§2), samo ime fajla u `lsm/sst/`, bez putanje. |
| `minKey`, `maxKey` | string | Ispravni ključevi (§1) i `minKey ≤ maxKey`. |
| `minSequence`, `maxSequence` | ceo broj (`i64`) | `1 ≤ minSequence ≤ maxSequence ≤ flushedThroughSequence`. |
| `entryCount` | ceo broj (`i64`) | `≥ 1`. |
| `sizeBytes` | ceo broj (`i64`) | `≥ 1`. |

CP1 §18 dozvoljava timu da doda polja; mi ih ne dodajemo, pa je čitanje strogo.

### 5.2 Validacija pri čitanju

Čitalac (`ManifestManager.loadAndValidate`) odbija manifest sa `STORAGE_IO` i kategorijom `MANIFEST_INVALID` ako:

- `MANIFEST.json` ne postoji ili nije običan fajl;
- sadržaj nije ispravan JSON objekat, ima duplirani ključ ili višak sadržaja posle objekta;
- nedostaje neko polje, postoji nepoznato polje ili polje ima pogrešan tip (npr. string umesto broja, decimalni broj, broj van `i64`);
- `formatVersion` nije `1`;
- neka vrednost krši granicu iz tabela u §5.1;
- dve tabele imaju isti `tableId` ili isti `file`;
- `file` ne odgovara `tableId`;
- neka tabela ima `maxSequence > flushedThroughSequence` (nekonzistentan high-water mark, CP1 §18.2).

Ovo je samo strukturna provera. Postojanje i sadržaj navedenih SSTable fajlova proverava oporavak (CP1 §17.3, §18.2). Manifest se nikad ne rekonstruiše nabrajanjem `lsm/sst/`.

### 5.3 Objavljivanje

Ista disciplina kao `node.json` (§4.1), ali se `MANIFEST.json` **zamenjuje** (CP1 §18.1):

1. Polazi se od trenutnog manifesta u memoriji. Izmena (nova tabela, zamena ulaza compaction-a) računa se nad njim, a generacija se povećava za `1` (rebase). Kandidat se proverava pravilima iz §5.2, a uz to `nextTableId` i `flushedThroughSequence` ne smeju biti manji nego u trenutnom manifestu. Neispravan kandidat se odbija pre bilo kakvog upisa.
2. Zaostali `lsm/tmp/MANIFEST-<generation sa 20 cifara>.tmp` iste generacije se briše, pa se u taj fajl upiše kompletan novi manifest.
3. Force nad privremenim fajlom.
4. Failpoint `manifest.after-temp-sync.before-rename`, pa atomska zamena (`ATOMIC_MOVE` + `REPLACE_EXISTING`) preko `lsm/MANIFEST.json`.
5. Failpoint `manifest.after-rename.before-directory-sync`, pa force nad direktorijumom `lsm/`.
6. Tek tada se menja manifest u memoriji. Brisanje WAL segmenata ili ulaza compaction-a sme da usledi tek posle ovog koraka.

Objavljivanja su serijalizovana; dva se nikad ne izvršavaju istovremeno. Početni manifest (generacija `0`) objavljuje se istim koracima 2–6, sa privremenim fajlom `MANIFEST-00000000000000000000.tmp`.

**Greške tokom objave** (CP1 §26.1):

| Gde nastaje greška | Stanje na disku | Ishod |
|---|---|---|
| Koraci 2–4 (upis, force, preimenovanje; uključuje `IO_ERROR` na `manifest.after-temp-sync.before-rename`) | Stari `MANIFEST.json` netaknut; privremeni fajl se briše. | Posao održavanja ne uspeva, mehanizam ostaje `SERVING`, ponovni pokušaj je dozvoljen (`renamed = false`). |
| Korak 5 (force direktorijuma; uključuje `IO_ERROR` na `manifest.after-rename.before-directory-sync`) | `MANIFEST.json` je možda nov, ali preimenovanje nije trajno potvrđeno. | Ništa se ne briše, manifest u memoriji ostaje stari, mehanizam prelazi u `FAILED` (`renamed = true`). Restart bira kompletan stari ili novi manifest; oba su bezbedna. |

Pad (`CRASH`) na bilo kojoj od ovih tačaka ostavlja ili kompletan stari ili kompletan novi manifest, nikad mešavinu. Zaostali `MANIFEST-*.tmp` nije dokaz i oporavak ga briše.

### 5.4 Dodela ID-ja tabela

ID-jevi SSTable tabela su prostor imena odvojen od WAL-a (§2). Pri pokretanju, **pre** brisanja orphan i privremenih tabela (CP1 §18.3):

```text
runtimeNextTableId = max(manifest.nextTableId, 1 + najveći ispravan ID u lsm/sst/ ili lsm/tmp/)
```

- U obzir se uzimaju imena `sst-<20 cifara>.sst` i privremena imena `sst-<20 cifara>.sst.tmp`, u oba direktorijuma. Druga imena se preskaču.
- Alokator (`manifest.TableIdAllocator`) vraća trenutnu vrednost, pa je uvećava; jedan ID nikad ne dobiju dva izlaza. Praznine u ID-jevima su dozvoljene, ponovna upotreba nije.
- Manifest koji objavljuje novu tabelu upisuje `nextTableId = max(prethodni nextTableId, sledeći ID alokatora)`.
- Prekoračenje (`2^63-1`) je izričit otkaz, nikad ponovna upotreba.

Primer: manifest ima `nextTableId = 5`, a u `lsm/sst/` je ostala orphan tabela `sst-00000000000000000012.sst` iz flush-a prekinutog pre objave. Sledeća dodela je `13`.

### 5.5 Pregled: `inspect manifest`

`./dbctl inspect manifest` prikazuje manifest zaustavljenog volumena (CP1 §25). Implementacija je `rs.raf.dlsm.inspect.InspectManifest.run(dataDir, out)`; manifest čita produkcionim `Manifest.parse`, pa ga odbija po istim pravilima kao oporavak (§5.2).

**Samo čita.** Ne briše orphan tabele ni zaostale privremene manifeste, ne popravlja ništa i ne pokreće čišćenje iz oporavka. Sadržaj SSTable fajlova ne validira; za to služi `inspect sstable`.

**Izlaz** je jedan JSON objekat (UTF-8, polja uvek istim redom): `manifestFile` (uvek `lsm/MANIFEST.json`), `status` (`VALID`, `CORRUPT`, `MISSING` ili `IO_ERROR`), `sizeBytes`, `formatVersion`, `generation`, `nextTableId`, `flushedThroughSequence`, `sstableCount`, `error` (`category`, `message`) ili `null`, `sstables` po rastućem `tableId`, `orphanTables` (`sst-<id>.sst` u `lsm/sst/` koje manifest ne navodi, po imenu) i `temporaryManifests` (`MANIFEST-*.tmp` u `lsm/tmp/`, po imenu). Kada manifest ne može da se pročita ili ne prolazi validaciju, izlaz ima samo `manifestFile`, `status` i `error`.

Svaka tabela u `sstables` ima polja iz §5.1 i još `fileStatus` (`PRESENT`, `MISSING` ili `SIZE_MISMATCH`) i `sizeOnDisk` (veličina fajla u bajtovima ili `null`).

**Izlazni kodovi** (isti kao `inspect wal`, §3.7):

| Kod | Kada |
|---:|---|
| `0` | `VALID`: manifest je ispravan i svaka navedena tabela postoji sa veličinom iz manifesta |
| `1` | `CORRUPT`: `MANIFEST_INVALID` (uključujući nepodržanu verziju), `REFERENCED_FILE_MISSING` ili `MANIFEST_TABLE_MISMATCH` |
| `2` | `MANIFEST.json` ne postoji (`MISSING`) |
| `3` | čitanje nije uspelo iz drugog razloga (`IO_ERROR`) |


## 6. SSTable — format Bloom filtera (verzija 1)

### Parametri

- Heš algoritam: SHA-256
- Verzija heša: 1
- Broj heš probi: 7
- Broj bitova: `max(64, 10 * entryCount)`, zaokruženo naviše na umnožak broja 8
- Svi višebajtni celi brojevi: big-endian
- Numerisanje bitova: unutar svakog bajta kreće se od najmanje značajnog bita

### Heširanje

Ključevi se kodiraju kao ASCII bajtovi.

Prvih 8 bajtova SHA-256 sažetka čini `h1`, sledećih 8 bajtova čini `h2`.

`h2` se pravi neparnim: `h2 = h2 | 1`.

Za svaku probu `i`, gde je `0 <= i < 7`:

`position = Long.remainderUnsigned(h1 + i * h2, m)`

Java `long` aritmetika koristi 64-bitno prelivanje.

### Binarni format

| Polje | Veličina |
|---|---:|
| Magic (`BLM1`) | 4 bajta |
| Verzija heša | 4 bajta |
| Broj bitova | 4 bajta |
| Broj heš funkcija | 4 bajta |
| Dužina niza bitova | 4 bajta |
| Niz bitova | promenljivo |
| CRC32C | 4 bajta |

Ukupna veličina serijalizovanog filtera: `24 + bitArrayLength` bajtova.

CRC32C pokriva svaki bajt osim poslednja 4 bajta kontrolne sume.

### Validacija

Deserijalizacija odbija:

- neispravan magic
- nepodržanu verziju heša
- pogrešan broj heš funkcija
- neispravan broj bitova
- pogrešnu dužinu niza bitova
- skraćene podatke
- nepodudaranje CRC32C

### Semantika

Bloom filter može dati lažno pozitivan rezultat, ali nikad lažno negativan za ubačene ključeve.

Obrisani ključevi (tombstone) takođe moraju biti u filteru.

Negativan rezultat dozvoljava da se preskoči čitanje bloka podataka. Pozitivan rezultat zahteva proveru odgovarajućeg bloka.

## 7. SSTable — format bloka podataka (v1)

### Konstante

- Magic: `DBL1`
- Verzija formata: 1
- Ciljna veličina sadržaja bloka: 4096 bajtova
- Najveća veličina vrednosti: 262144 bajta
- Redosled bajtova: big-endian
- Kontrolna suma: CRC32C

### Raspored bloka

| Polje | Veličina |
|---|---:|
| Magic (`DBL1`) | 4 bajta |
| Verzija | 4 bajta |
| Broj zapisa | 4 bajta |
| Dužina sadržaja | 4 bajta |
| Zapisi | promenljivo |
| CRC32C | 4 bajta |

CRC32C pokriva sve bajtove osim poslednje kontrolne sume.

### Raspored zapisa

| Polje | Veličina |
|---|---:|
| Dužina ključa | 4 bajta |
| Redni broj (`sequence`) | 8 bajtova |
| Vrsta zapisa | 1 bajt |
| Dužina vrednosti | 4 bajta |
| Bajtovi ključa | promenljivo |
| Bajtovi vrednosti | promenljivo |

Vrste zapisa:

- `1` = PUT
- `2` = DELETE (tombstone)

Za DELETE dužina vrednosti je `-1` i nijedan bajt vrednosti se ne čuva. Za prazan PUT dužina vrednosti je `0`.

Zapisi moraju biti poređani po ključu strogo rastuće. Dupli ključevi unutar bloka su zabranjeni. Zapis koji prelazi ciljnu veličinu čuva se u sopstvenom bloku.

### Validacija

Dekoder odbija neispravan magic, nepodržanu verziju, skraćene zapise, neispravne dužine, duple ili neuređene ključeve, neispravne redne brojeve i nepodudaranje CRC32C.

## 8. SSTable — format retkog indeksa (v1)

### Konstante

- Magic: `IDX1`
- Verzija formata: 1
- Redosled bajtova: big-endian
- Kontrolna suma: CRC32C

### Raspored indeksa

| Polje | Veličina |
|---|---:|
| Magic (`IDX1`) | 4 bajta |
| Verzija | 4 bajta |
| Broj stavki | 4 bajta |
| Stavke indeksa | promenljivo |
| CRC32C | 4 bajta |

### Raspored stavke indeksa

| Polje | Veličina |
|---|---:|
| Dužina prvog ključa | 4 bajta |
| Bajtovi prvog ključa | promenljivo |
| Pomak bloka | 8 bajtova |
| Dužina bloka | 4 bajta |

Svaka stavka određuje prvi ključ i fizički položaj jednog bloka podataka.

Ključevi u indeksu moraju biti strogo rastući, a opsezi blokova se ne smeju preklapati.

Tačkasto čitanje koristi binarnu pretragu da nađe stavku sa najvećim prvim ključem koji je manji ili jednak traženom. Ako traženi ključ prethodi prvom ključu u indeksu, ne postoji blok kandidat. Indeks ne čuva svaki korisnički ključ.

### Validacija

Dekoder proverava kontrolnu sumu, verziju formata, broj stavki, ispravnost ključeva, strogo rastući redosled, pozitivne dužine blokova, nenegativne pomake i da se opsezi blokova ne preklapaju.

Ovi formati su kodiranja verzije 1 koja je odredio tim. `SSTableWriter` i `SSTableReader` moraju dosledno koristiti upravo ova kodiranja.

## 9. SSTable — format celog fajla (v1)

### Opšta pravila

- Ime fajla: `sst-<tableId sa 20 cifara>.sst`
- Redosled bajtova: big-endian
- Verzija formata: 1
- Kodiranje ključeva: ASCII
- Kontrolne sume: CRC32C
- Tabele su nepromenljive posle finalizacije.
- Ključevi se čuvaju strogo rastuće.
- Svaki logički ključ se javlja najviše jednom po tabeli.
- DELETE tombstone se čuva kao zapis i ulazi u Bloom filter.

### Raspored fajla

Ceo SSTable ima pet susednih oblasti:

| Oblast | Veličina | Opis |
|---|---:|---|
| Zaglavlje | 16 bajtova | Magic, verzija, ID tabele |
| Blokovi podataka | promenljivo | Sortirani zapisi zaštićeni CRC32C |
| Retki indeks | promenljivo | Prvi ključ, pomak i dužina po bloku |
| Bloom filter | promenljivo | Filter članstva za sačuvane ključeve |
| Footer | 384 bajta | Raspored oblasti i metapodaci tabele |

Između oblasti nisu dozvoljene praznine. Footer se uvek nalazi na `fileSize - 384`.

### Zaglavlje

| Pomak | Veličina | Polje |
|---|---:|---|
| 0 | 4 | Magic `SST1` (`53 53 54 31`) |
| 4 | 4 | Verzija = 1 |
| 8 | 8 | ID tabele (pozitivan) |

Zaglavlje nema sopstvenu kontrolnu sumu.

### Oblast podataka

Podaci počinju na apsolutnom pomaku 16. Svaki blok koristi format `DBL1` iz §7.

Zapisi su uređeni po ključu kroz celu oblast podataka, a ne samo unutar jednog bloka. Ciljna veličina sadržaja je 4096 bajtova, a zapis koji je prevelik zauzima sopstveni blok.

Blok sadrži zaglavlje od 16 bajtova, kodirani sadržaj zapisa i CRC32C od 4 bajta. CRC32C pokriva ceo blok osim poslednja 4 bajta.

### Oblast retkog indeksa

Retki indeks koristi format `IDX1` iz §8. Tačno jedna stavka opisuje svaki blok podataka i sadrži prvi ključ bloka, apsolutni pomak bloka u fajlu i kodiranu dužinu bloka. Stavke su uređene po prvom ključu.

Tačkasto čitanje bira poslednju stavku čiji je prvi ključ manji ili jednak traženom.

### Oblast Bloom filtera

Koristi format `BLM1` iz §6: verzija heša 1, dvostruko heširanje SHA-256, sedam proba, broj bitova `max(64, 10 * expectedEntries)` zaokruženo na umnožak broja 8, numerisanje bitova od najmanje značajnog.

Filter sadrži i PUT ključeve i DELETE tombstone. Negativan rezultat dozvoljava čitaocu da preskoči oblast podataka; pozitivan rezultat nije dokaz da ključ postoji.

### Footer

Footer je tačno 384 bajta.

| Pomak u footeru | Veličina | Polje |
|---|---:|---|
| 0 | 4 | Magic `FTR1` |
| 4 | 4 | Verzija = 1 |
| 8 | 8 | ID tabele |
| 16 | 8 | Pomak podataka |
| 24 | 8 | Dužina podataka |
| 32 | 8 | Pomak indeksa |
| 40 | 8 | Dužina indeksa |
| 48 | 8 | Pomak Bloom filtera |
| 56 | 8 | Dužina Bloom filtera |
| 64 | 8 | Najmanji `sequence` |
| 72 | 8 | Najveći `sequence` |
| 80 | 8 | Broj zapisa |
| 88 | 132 | Najmanji ključ |
| 220 | 132 | Najveći ključ |
| 352 | 28 | Rezervisano, sve nule |
| 380 | 4 | CRC32C footera |

Svako polje ključa fiksne širine sadrži dužinu ključa (4 bajta), do 128 ASCII bajtova ključa i dopunu nulama do 128 bajtova ključa. CRC32C footera pokriva bajtove footera od 0 do 379.

Ceo fajl mora da zadovoljava:

- `dataOffset = 16`
- `indexOffset = dataOffset + dataLength`
- `bloomOffset = indexOffset + indexLength`
- `footerOffset = bloomOffset + bloomLength`
- `fileSize = footerOffset + 384`

Svi pomaci oblasti su apsolutni pomaci u fajlu.

### Potpuna validacija

Validator prima putanju i očekivani `SSTableMeta`. Proverava:

1. postojanje fajla i najmanju veličinu
2. magic, verziju i ID tabele u zaglavlju
3. magic, verziju i CRC32C footera
4. tačne, susedne granice oblasti
5. format, redosled i kontrolnu sumu retkog indeksa
6. format, parametre i kontrolnu sumu Bloom filtera
7. svaki blok podataka i njegovu kontrolnu sumu
8. strogo rastući redosled ključeva i preko granica blokova
9. da stavke indeksa odgovaraju svojim blokovima
10. da nema lažno negativnih rezultata Bloom filtera za sačuvane ključeve
11. broj zapisa, najmanji i najveći ključ i `sequence`
12. slaganje sa očekivanim metapodacima iz manifesta

Blokovi se čitaju postepeno, bez učitavanja celog SSTable u memoriju. Oštećeni podaci se nikad ne tumače kao odsutan ključ.

### Dekodiran primer — SSTable sa jednim zapisom

Ulaz:

- `tableId = 7`
- ključ = `alpha`
- `sequence = 1`
- vrsta = PUT
- vrednost = ASCII `A`
- `expectedEntries = 1`

Dobijena tabela ima 512 bajtova.

| Oblast | Apsolutni pomak | Dužina |
|---|---:|---:|
| Zaglavlje | 0 | 16 |
| Blok podataka | 16 | 43 |
| Retki indeks | 59 | 37 |
| Bloom | 96 | 32 |
| Footer | 128 | 384 |

Zaglavlje: magic `SST1`, verzija 1, `tableId = 7`.

Blok podataka: magic `DBL1`, verzija 1, `recordCount = 1`, `payloadLength = 23`, ključ `alpha`, `sequence = 1`, vrsta PUT, `valueLength = 1`, vrednost `A`, ispravan CRC32C.

Retki indeks: magic `IDX1`, `entryCount = 1`, `firstKey = alpha`, `blockOffset = 16`, `blockLength = 43`, ispravan CRC32C.

Bloom: magic `BLM1`, `hashVersion = 1`, `bitCount = 64`, `hashFunctions = 7`, `bitArrayLength = 8`, `mightContain("alpha") = true`, ispravan CRC32C.

Footer: magic `FTR1`, `tableId = 7`, `dataOffset = 16`, `dataLength = 43`, `indexOffset = 59`, `indexLength = 37`, `bloomOffset = 96`, `bloomLength = 32`, `minKey = maxKey = alpha`, `minSequence = maxSequence = 1`, `entryCount = 1`, ispravan CRC32C.

Primer proverava i `SSTableFormatContractTest`.
