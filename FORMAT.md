
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
