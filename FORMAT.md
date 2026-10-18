
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
