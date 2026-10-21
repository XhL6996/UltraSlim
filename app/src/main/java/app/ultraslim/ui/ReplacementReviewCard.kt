package app.ultraslim.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.ultraslim.convert.MediaStoreTrashHelper

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReplacementReviewCard(review: ReplacementReview, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth().testTag("replacement-review"), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${review.count} 张原图待替换", style = MaterialTheme.typography.titleMedium)
            Text(if (review.checking) "正在复核文件，请稍候…"
                else "成品已保存。复核通过后，由系统确认将原图移入回收站。" +
                    if (review.count > MediaStoreTrashHelper.MAX_REQUEST_ITEMS)
                        "本次最多 ${MediaStoreTrashHelper.MAX_REQUEST_ITEMS} 张，剩余照片需再次点击处理。" else "",
                style = MaterialTheme.typography.bodyMedium)
            if (review.checking) LinearProgressIndicator(Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = review.onConfirm, enabled = !review.checking,
                    modifier = Modifier.testTag("replacement-confirm")) {
                    Text("替换 ${minOf(review.count, MediaStoreTrashHelper.MAX_REQUEST_ITEMS)} 张原图")
                }
                TextButton(onClick = review.onKeepOriginals, enabled = review.canKeep,
                    modifier = Modifier.testTag("replacement-keep")) { Text("保留原图") }
            }
        }
    }
}
