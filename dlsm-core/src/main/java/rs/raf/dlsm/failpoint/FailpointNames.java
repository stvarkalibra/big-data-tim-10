package rs.raf.dlsm.failpoint;

public class FailpointNames {

    private FailpointNames() {}

    // WAL failpoint-i
    public static final String WAL_BEFORE_APPEND = "wal.before-append";
    public static final String WAL_AFTER_RECORD_PREFIX = "wal.after-record-prefix";
    public static final String WAL_AFTER_APPEND_BEFORE_SYNC = "wal.after-append.before-sync";
    public static final String WAL_AFTER_SYNC_BEFORE_MEMTABLE = "wal.after-sync.before-memtable";

    // Engine / Memtable / Scan failpoint-i
    public static final String MEMTABLE_AFTER_APPLY_BEFORE_RESPONSE = "memtable.after-apply.before-response";
    public static final String ROTATION_AFTER_NEW_WAL_SYNC_BEFORE_SWITCH = "rotation.after-new-wal-sync.before-switch";
    public static final String ROTATION_AFTER_SWITCH_BEFORE_FLUSH = "rotation.after-switch.before-flush";
    public static final String SCAN_AFTER_SNAPSHOT_BEFORE_MERGE = "scan.after-snapshot.before-merge";

    // SSTable failpoint-i
    public static final String SSTABLE_AFTER_WRITE_BEFORE_SYNC = "sstable.after-write.before-sync";
    public static final String SSTABLE_AFTER_SYNC_BEFORE_RENAME = "sstable.after-sync.before-rename";
    public static final String SSTABLE_AFTER_RENAME_BEFORE_DIRECTORY_SYNC = "sstable.after-rename.before-directory-sync";

    // Manifest failpoint-i
    public static final String MANIFEST_AFTER_TEMP_SYNC_BEFORE_RENAME = "manifest.after-temp-sync.before-rename";
    public static final String MANIFEST_AFTER_RENAME_BEFORE_DIRECTORY_SYNC = "manifest.after-rename.before-directory-sync";

    // Flush failpoint-i
    public static final String FLUSH_AFTER_SSTABLE_FINALIZE_BEFORE_MANIFEST = "flush.after-sstable-finalize.before-manifest";
    public static final String FLUSH_AFTER_MANIFEST_PUBLISH_BEFORE_WAL_DELETE = "flush.after-manifest-publish.before-wal-delete";

    // Compaction failpoint-i
    public static final String COMPACTION_AFTER_SELECTION_BEFORE_OUTPUT = "compaction.after-selection.before-output";
    public static final String COMPACTION_AFTER_OUTPUT_FINALIZE_BEFORE_MANIFEST = "compaction.after-output-finalize.before-manifest";
    public static final String COMPACTION_AFTER_MANIFEST_PUBLISH_BEFORE_INPUT_DELETE = "compaction.after-manifest-publish.before-input-delete";

    // Recovery failpoint-i 
    public static final String RECOVERY_AFTER_MANIFEST_LOAD = "recovery.after-manifest-load";
    public static final String RECOVERY_AFTER_WAL_TAIL_TRUNCATE = "recovery.after-wal-tail-truncate";
    public static final String RECOVERY_AFTER_WAL_REPLAY_BEFORE_READY = "recovery.after-wal-replay.before-ready";
}
