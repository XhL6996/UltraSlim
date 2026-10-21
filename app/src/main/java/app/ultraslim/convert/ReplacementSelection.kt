package app.ultraslim.convert

import app.ultraslim.data.ReplacementEntity

/** A page is bounded by original URIs, including all journal rows referring to those originals. */
internal fun selectReplacementPage(rows: List<ReplacementEntity>): List<ReplacementEntity> {
    val sources = rows.asSequence().map { it.sourceUri }.distinct()
        .take(MediaStoreTrashHelper.MAX_REQUEST_ITEMS).toHashSet()
    return rows.filter { it.sourceUri in sources }
}
