package rs.raf.dlsm.scenarios;

import rs.raf.dlsm.failpoint.FailpointNames;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The oracle matrices of CP1-MDL-002 (CP1 §29.6) as data, one row per hook.
 *
 * <p>A {@code CRASH} row says which target reaches the hook and what the
 * harness derives <em>before</em> the restart: the candidates for the target
 * mutation, the {@code MANIFEST.json} on disk, the files recovery must clean
 * up and the length of the active WAL segment. An {@code IO_ERROR} row says
 * the immediate result of the eight mappings of CP1 §26.1. Each failpoint
 * owner checks the rows of their own hooks (plan §8).
 */
final class Mdl002Matrix {

    /** What the harness sends so that this hook is the crash boundary. */
    enum Target {
        /** One {@code PUT} or {@code DELETE}, filtered by its request ID. */
        MUTATION,
        /** A mutation that must rotate first: the active generation is full. */
        ROTATION,
        /** One {@code SCAN}, filtered by its request ID. */
        SCAN,
        /** A manual flush of a non-empty active generation, filtered by its request ID. */
        FLUSH,
        /** A manual compaction of two or three tables, filtered by its request ID. */
        COMPACTION,
        /** A start with a bootstrap rule (CP1 §26.3), crashed twice in a row. */
        RECOVERY
    }

    /** The target mutation after recovery (CP1 §28.3). */
    enum Mutation {
        /** Nothing is in flight: exactly the confirmed model. */
        NONE,
        /** Neither a byte nor a sequence number reached the log. */
        ABSENT,
        /** A complete frame before its force: absent or fully applied. */
        EITHER,
        /** The force finished: it must be there although nobody answered. */
        APPLIED
    }

    /** {@code MANIFEST.json} right after the crash, against the one before the target. */
    enum Manifest { OLD, NEW, OLD_OR_NEW }

    /** Files the crash leaves for recovery to clean up, checked before the restart. */
    enum Leftover {
        /** {@code lsm/tmp/} is empty and every table is referenced. */
        NONE,
        /** A temporary table {@code lsm/tmp/sst-<id>.sst.tmp}. */
        TEMP_TABLE,
        /** A table in {@code lsm/sst/} that the manifest does not reference. */
        ORPHAN_TABLE,
        /** A temporary manifest in {@code lsm/tmp/} and its unreferenced table. */
        TEMP_MANIFEST,
        /** The compaction inputs, no longer referenced by the new manifest. */
        RETIRED_INPUTS,
        /** Either way: the directory was not forced, so the name may or may not exist. */
        ANY
    }

    /** The segment that was active before the target, right after the crash. */
    enum Wal {
        UNCHANGED,
        /** Exactly {@code prefixBytes} longer (CP1 §26.2). */
        PREFIX,
        /** Exactly one complete frame longer: {@code 30 + k + v} bytes (FORMAT.md §3). */
        FULL_FRAME,
        /** Unchanged, plus a newer segment with only its 24-byte header. */
        NEW_EMPTY_SEGMENT,
        /** Recovery hooks: checked by the recovery cycle itself. */
        NOT_CHECKED
    }

    record CrashRow(String hook, Target target, Mutation mutation, Manifest manifest,
                    Leftover leftover, Wal wal) {
    }

    /** CP1 §29.6, the {@code CRASH} matrix, in catalog order (CP1 §26.1). */
    static final List<CrashRow> CRASH = List.of(
            new CrashRow(FailpointNames.WAL_BEFORE_APPEND, Target.MUTATION,
                    Mutation.ABSENT, Manifest.OLD, Leftover.NONE, Wal.UNCHANGED),
            new CrashRow(FailpointNames.WAL_AFTER_RECORD_PREFIX, Target.MUTATION,
                    Mutation.ABSENT, Manifest.OLD, Leftover.NONE, Wal.PREFIX),
            new CrashRow(FailpointNames.WAL_AFTER_APPEND_BEFORE_SYNC, Target.MUTATION,
                    Mutation.EITHER, Manifest.OLD, Leftover.NONE, Wal.FULL_FRAME),
            new CrashRow(FailpointNames.WAL_AFTER_SYNC_BEFORE_MEMTABLE, Target.MUTATION,
                    Mutation.APPLIED, Manifest.OLD, Leftover.NONE, Wal.FULL_FRAME),
            new CrashRow(FailpointNames.MEMTABLE_AFTER_APPLY_BEFORE_RESPONSE, Target.MUTATION,
                    Mutation.APPLIED, Manifest.OLD, Leftover.NONE, Wal.FULL_FRAME),
            // Rotation runs inside the mutation before its sequence number, so the
            // mutation that triggered it has neither a sequence nor a frame.
            new CrashRow(FailpointNames.ROTATION_AFTER_NEW_WAL_SYNC_BEFORE_SWITCH, Target.ROTATION,
                    Mutation.ABSENT, Manifest.OLD, Leftover.NONE, Wal.NEW_EMPTY_SEGMENT),
            new CrashRow(FailpointNames.ROTATION_AFTER_SWITCH_BEFORE_FLUSH, Target.ROTATION,
                    Mutation.ABSENT, Manifest.OLD, Leftover.NONE, Wal.NEW_EMPTY_SEGMENT),
            new CrashRow(FailpointNames.SCAN_AFTER_SNAPSHOT_BEFORE_MERGE, Target.SCAN,
                    Mutation.NONE, Manifest.OLD, Leftover.NONE, Wal.UNCHANGED),
            new CrashRow(FailpointNames.SSTABLE_AFTER_WRITE_BEFORE_SYNC, Target.FLUSH,
                    Mutation.NONE, Manifest.OLD, Leftover.TEMP_TABLE, Wal.UNCHANGED),
            new CrashRow(FailpointNames.SSTABLE_AFTER_SYNC_BEFORE_RENAME, Target.FLUSH,
                    Mutation.NONE, Manifest.OLD, Leftover.TEMP_TABLE, Wal.UNCHANGED),
            new CrashRow(FailpointNames.SSTABLE_AFTER_RENAME_BEFORE_DIRECTORY_SYNC, Target.FLUSH,
                    Mutation.NONE, Manifest.OLD, Leftover.ANY, Wal.UNCHANGED),
            new CrashRow(FailpointNames.MANIFEST_AFTER_TEMP_SYNC_BEFORE_RENAME, Target.FLUSH,
                    Mutation.NONE, Manifest.OLD, Leftover.TEMP_MANIFEST, Wal.UNCHANGED),
            new CrashRow(FailpointNames.MANIFEST_AFTER_RENAME_BEFORE_DIRECTORY_SYNC, Target.FLUSH,
                    Mutation.NONE, Manifest.OLD_OR_NEW, Leftover.ANY, Wal.UNCHANGED),
            new CrashRow(FailpointNames.FLUSH_AFTER_SSTABLE_FINALIZE_BEFORE_MANIFEST, Target.FLUSH,
                    Mutation.NONE, Manifest.OLD, Leftover.ORPHAN_TABLE, Wal.UNCHANGED),
            new CrashRow(FailpointNames.FLUSH_AFTER_MANIFEST_PUBLISH_BEFORE_WAL_DELETE, Target.FLUSH,
                    Mutation.NONE, Manifest.NEW, Leftover.NONE, Wal.UNCHANGED),
            new CrashRow(FailpointNames.COMPACTION_AFTER_SELECTION_BEFORE_OUTPUT, Target.COMPACTION,
                    Mutation.NONE, Manifest.OLD, Leftover.NONE, Wal.UNCHANGED),
            new CrashRow(FailpointNames.COMPACTION_AFTER_OUTPUT_FINALIZE_BEFORE_MANIFEST, Target.COMPACTION,
                    Mutation.NONE, Manifest.OLD, Leftover.ORPHAN_TABLE, Wal.UNCHANGED),
            new CrashRow(FailpointNames.COMPACTION_AFTER_MANIFEST_PUBLISH_BEFORE_INPUT_DELETE, Target.COMPACTION,
                    Mutation.NONE, Manifest.NEW, Leftover.RETIRED_INPUTS, Wal.UNCHANGED),
            new CrashRow(FailpointNames.RECOVERY_AFTER_MANIFEST_LOAD, Target.RECOVERY,
                    Mutation.NONE, Manifest.OLD, Leftover.NONE, Wal.NOT_CHECKED),
            // Reached over a torn tail: the cycle first crashes on wal.after-record-prefix.
            new CrashRow(FailpointNames.RECOVERY_AFTER_WAL_TAIL_TRUNCATE, Target.RECOVERY,
                    Mutation.NONE, Manifest.OLD, Leftover.NONE, Wal.NOT_CHECKED),
            new CrashRow(FailpointNames.RECOVERY_AFTER_WAL_REPLAY_BEFORE_READY, Target.RECOVERY,
                    Mutation.NONE, Manifest.OLD, Leftover.NONE, Wal.NOT_CHECKED));

    /** How the failed operation must show right away (CP1 §29.6, {@code IO_ERROR} matrix). */
    enum IoEffect {
        /** {@code 500 STORAGE_IO}, the engine is {@code FAILED} until a restart. */
        WRITE_PATH_FAILED,
        /** {@code 500 STORAGE_IO}, nothing published, the node stays {@code READY}; a retry publishes. */
        MAINTENANCE_FAILED,
        /** {@code 500 STORAGE_IO}, then {@code FAILED}; the restart sees the old or the new manifest. */
        MAINTENANCE_FAILED_ENGINE_FAILED
    }

    record IoRow(String hook, IoEffect effect, Mutation mutation, Wal wal) {
    }

    /** CP1 §29.6, the {@code IO_ERROR} matrix: the eight mappings of CP1 §26.1. */
    static final List<IoRow> IO_ERROR = List.of(
            new IoRow(FailpointNames.WAL_BEFORE_APPEND,
                    IoEffect.WRITE_PATH_FAILED, Mutation.ABSENT, Wal.UNCHANGED),
            new IoRow(FailpointNames.WAL_AFTER_RECORD_PREFIX,
                    IoEffect.WRITE_PATH_FAILED, Mutation.ABSENT, Wal.PREFIX),
            new IoRow(FailpointNames.WAL_AFTER_APPEND_BEFORE_SYNC,
                    IoEffect.WRITE_PATH_FAILED, Mutation.EITHER, Wal.FULL_FRAME),
            new IoRow(FailpointNames.SSTABLE_AFTER_WRITE_BEFORE_SYNC,
                    IoEffect.MAINTENANCE_FAILED, Mutation.NONE, Wal.UNCHANGED),
            new IoRow(FailpointNames.SSTABLE_AFTER_SYNC_BEFORE_RENAME,
                    IoEffect.MAINTENANCE_FAILED, Mutation.NONE, Wal.UNCHANGED),
            new IoRow(FailpointNames.SSTABLE_AFTER_RENAME_BEFORE_DIRECTORY_SYNC,
                    IoEffect.MAINTENANCE_FAILED, Mutation.NONE, Wal.UNCHANGED),
            new IoRow(FailpointNames.MANIFEST_AFTER_TEMP_SYNC_BEFORE_RENAME,
                    IoEffect.MAINTENANCE_FAILED, Mutation.NONE, Wal.UNCHANGED),
            new IoRow(FailpointNames.MANIFEST_AFTER_RENAME_BEFORE_DIRECTORY_SYNC,
                    IoEffect.MAINTENANCE_FAILED_ENGINE_FAILED, Mutation.NONE, Wal.UNCHANGED));

    /** Damage to a clone of a stopped, healthy volume; the node must start {@code FAILED}. */
    enum Corruption {
        /** The CRC of the first frame of the active segment: {@code CHECKSUM}. */
        WAL_RECORD("CHECKSUM"),
        /** The magic of the active segment: {@code WAL_HEADER}. */
        WAL_SEGMENT_HEADER("WAL_HEADER"),
        /** {@code MANIFEST.json} cut in half: {@code MANIFEST_INVALID}. */
        MANIFEST("MANIFEST_INVALID"),
        /** A table the manifest references is gone; any explicit category. */
        MISSING_TABLE(null),
        /** One byte inside the first data block of a referenced table; any explicit category. */
        TABLE_REGION(null);

        /** The {@code lastFatalError.code}, or {@code null} when only {@code FAILED} is required. */
        final String code;

        Corruption(String code) {
            this.code = code;
        }
    }

    /** Startup protection (CP1 §14, §29.6); each must refuse with this category. */
    enum StartupGuard {
        /** A second process over the owned directory; the first stays ready. */
        SECOND_PROCESS("DIRECTORY_LOCKED"),
        /** A non-empty volume without {@code node.json}. */
        MISSING_NODE_JSON("NODE_IDENTITY_MISMATCH"),
        /** A non-empty volume whose {@code node.json} names another node. */
        OTHER_NODE_ID("NODE_IDENTITY_MISMATCH"),
        /** A non-empty volume without {@code MANIFEST.json}: never initialized as empty. */
        MISSING_MANIFEST("MANIFEST_INVALID");

        final String code;

        StartupGuard(String code) {
            this.code = code;
        }
    }

    private Mdl002Matrix() {
    }

    static CrashRow crashRow(String hook) {
        return CRASH.stream().filter(row -> row.hook().equals(hook)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No CRASH row for " + hook));
    }

    /**
     * Crash/restart cycles a row costs: a recovery hook crashes twice in a
     * row (CP1 §29.6), and the tail-truncate hook is reached over a crash on
     * {@code wal.after-record-prefix}.
     */
    static int crashes(CrashRow row) {
        if (row.target() != Target.RECOVERY) {
            return 1;
        }
        return row.hook().equals(FailpointNames.RECOVERY_AFTER_WAL_TAIL_TRUNCATE) ? 3 : 2;
    }

    /**
     * The seeded schedule: every row once, then rows of reachable
     * non-recovery hooks drawn by the seed until the cycles add up to
     * {@code minCrashes}, all in a seeded order (CP1 §29.6). The same seed
     * always gives the same schedule.
     */
    static List<CrashRow> schedule(long seed, int minCrashes) {
        Random random = new Random(seed ^ 0x4D444C30_30324CL);
        List<CrashRow> extra = CRASH.stream().filter(row -> row.target() != Target.RECOVERY).toList();
        List<CrashRow> schedule = new ArrayList<>(CRASH);
        int crashes = schedule.stream().mapToInt(Mdl002Matrix::crashes).sum();
        while (crashes < minCrashes) {
            CrashRow row = extra.get(random.nextInt(extra.size()));
            schedule.add(row);
            crashes += crashes(row);
        }
        Collections.shuffle(schedule, random);
        return List.copyOf(schedule);
    }

    /** {@link #IO_ERROR} in a seeded order. */
    static List<IoRow> ioSchedule(long seed) {
        List<IoRow> rows = new ArrayList<>(IO_ERROR);
        Collections.shuffle(rows, new Random(seed ^ 0x494F5F45_52524FL));
        return List.copyOf(rows);
    }
}