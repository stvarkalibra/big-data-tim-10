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
