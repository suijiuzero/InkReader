package com.inkreader.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inkreader.R
import com.inkreader.data.model.ImportOrder
import com.inkreader.ui.theme.InkType
import com.inkreader.ui.theme.LocalInkPalette

/** 导入与追加共用同一份文件顺序草稿，取消对话框时不会写入书籍。 */
@Composable
fun ImportOrderControls(
    order: ImportOrder,
    names: List<String>,
    onOrderChange: (ImportOrder) -> Unit,
    onMove: (Int, Int) -> Unit
) {
    val p = LocalInkPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel(stringResource(R.string.import_order_label))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InkChip(stringResource(R.string.import_order_auto), selected = order == ImportOrder.AUTO,
                onClick = { onOrderChange(ImportOrder.AUTO) })
            InkChip(stringResource(R.string.import_order_selection), selected = order == ImportOrder.SELECTION,
                onClick = { onOrderChange(ImportOrder.SELECTION) })
        }
        Text(stringResource(if (order == ImportOrder.AUTO) R.string.import_order_auto_hint else R.string.import_order_selection_hint),
            style = InkType.Caption, color = p.textSecondary)
        if (order == ImportOrder.SELECTION) {
            Text(stringResource(R.string.import_order_adjust_hint), style = InkType.Caption, color = p.textFaint)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp).clip(RoundedCornerShape(12.dp)).background(p.background)) {
                itemsIndexed(names) { index, name ->
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}", Modifier.width(28.dp), style = InkType.Caption, color = p.accent)
                        Text(name, Modifier.weight(1f), style = InkType.Caption, color = p.textPrimary,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        InkIconButton(R.drawable.ic_up, stringResource(R.string.import_order_move_up, index + 1),
                            { onMove(index, index - 1) }, enabled = index > 0, size = 20)
                        InkIconButton(R.drawable.ic_down, stringResource(R.string.import_order_move_down, index + 1),
                            { onMove(index, index + 1) }, enabled = index < names.lastIndex, size = 20)
                    }
                }
            }
        }
    }
}
