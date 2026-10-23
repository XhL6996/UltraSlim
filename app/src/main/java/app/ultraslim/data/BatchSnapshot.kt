package app.ultraslim.data

import org.json.JSONArray

internal const val BATCH_SNAPSHOT_ERROR = "任务快照无法解析，请重新选择图片转换"

internal data class BatchSnapshotResult(
    val itemIds: List<String>,
    val options: ConversionDefaults?,
    val isValid: Boolean,
)

/** Keep usable IDs for reporting corruption, but never execute a partially decoded batch. */
internal fun decodeBatchSnapshot(batch: BatchEntity): BatchSnapshotResult {
    val array = runCatching { JSONArray(batch.itemIds) }.getOrNull()
    val ids = linkedSetOf<String>()
    var validIds = array != null && array.length() > 0
    if (array != null) for (index in 0 until array.length()) {
        val value = array.opt(index)
        if (value !is String || value.isBlank() || !ids.add(value)) validIds = false
    }
    val options = runCatching { decodeDefaults(batch.settings) }.getOrNull()
    return BatchSnapshotResult(ids.toList(), options, validIds && options != null)
}
