package rs.raf.dlsm.failpoint;

import rs.raf.dlsm.failpoint.FailpointRegistry.Action;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class FailpointCatalog {

    private static final Set<String> IO_ERROR_HOOKS = Set.of(
            FailpointNames.WAL_BEFORE_APPEND,
            FailpointNames.WAL_AFTER_RECORD_PREFIX,
            FailpointNames.WAL_AFTER_APPEND_BEFORE_SYNC,
            FailpointNames.SSTABLE_AFTER_WRITE_BEFORE_SYNC,
            FailpointNames.SSTABLE_AFTER_SYNC_BEFORE_RENAME,
            FailpointNames.SSTABLE_AFTER_RENAME_BEFORE_DIRECTORY_SYNC,
            FailpointNames.MANIFEST_AFTER_TEMP_SYNC_BEFORE_RENAME,
            FailpointNames.MANIFEST_AFTER_RENAME_BEFORE_DIRECTORY_SYNC);

    private static final List<String> NAMES = List.of(
            FailpointNames.WAL_BEFORE_APPEND,
            FailpointNames.WAL_AFTER_RECORD_PREFIX,
            FailpointNames.WAL_AFTER_APPEND_BEFORE_SYNC,
            FailpointNames.WAL_AFTER_SYNC_BEFORE_MEMTABLE,
            FailpointNames.MEMTABLE_AFTER_APPLY_BEFORE_RESPONSE,
            FailpointNames.ROTATION_AFTER_NEW_WAL_SYNC_BEFORE_SWITCH,
            FailpointNames.ROTATION_AFTER_SWITCH_BEFORE_FLUSH,
            FailpointNames.SCAN_AFTER_SNAPSHOT_BEFORE_MERGE,
            FailpointNames.SSTABLE_AFTER_WRITE_BEFORE_SYNC,
            FailpointNames.SSTABLE_AFTER_SYNC_BEFORE_RENAME,
            FailpointNames.SSTABLE_AFTER_RENAME_BEFORE_DIRECTORY_SYNC,
            FailpointNames.MANIFEST_AFTER_TEMP_SYNC_BEFORE_RENAME,
            FailpointNames.MANIFEST_AFTER_RENAME_BEFORE_DIRECTORY_SYNC,
            FailpointNames.FLUSH_AFTER_SSTABLE_FINALIZE_BEFORE_MANIFEST,
            FailpointNames.FLUSH_AFTER_MANIFEST_PUBLISH_BEFORE_WAL_DELETE,
            FailpointNames.COMPACTION_AFTER_SELECTION_BEFORE_OUTPUT,
            FailpointNames.COMPACTION_AFTER_OUTPUT_FINALIZE_BEFORE_MANIFEST,
            FailpointNames.COMPACTION_AFTER_MANIFEST_PUBLISH_BEFORE_INPUT_DELETE,
            FailpointNames.RECOVERY_AFTER_MANIFEST_LOAD,
            FailpointNames.RECOVERY_AFTER_WAL_TAIL_TRUNCATE,
            FailpointNames.RECOVERY_AFTER_WAL_REPLAY_BEFORE_READY);

    private FailpointCatalog() {
    }
    public static List<String> names() {
        return NAMES;
    }
    public static boolean contains(String name) {
        return NAMES.contains(name);
    }
    public static List<Action> actions(String name) {
        if (!contains(name)) {
            return List.of();
        }
        return IO_ERROR_HOOKS.contains(name)
                ? List.of(Action.PAUSE, Action.CRASH, Action.IO_ERROR)
                : List.of(Action.PAUSE, Action.CRASH, Action.RETURN_ERROR);
    }
    public static boolean supports(String name, Action action) {
        return actions(name).contains(action);
    }
    public static Map<String, List<Action>> asMap() {
        Map<String, List<Action>> catalog = new LinkedHashMap<>();
        for (String name : NAMES) {
            catalog.put(name, actions(name));
        }
        return catalog;
    }
}