package xyz.szyyw.livetv.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import xyz.szyyw.livetv.R

data class Row(val text: String, val highlighted: Boolean = false)

/** 浮层用的简单文字行列表；焦点高亮由 row_bg 选择器负责，highlighted 对应 activated 状态。 */
class RowAdapter(
    private val onClick: (Int) -> Unit,
    private val onFocus: (Int) -> Unit,
) : RecyclerView.Adapter<RowAdapter.VH>() {
    private var rowsInternal: List<Row> = emptyList()
    var rows: List<Row>
        get() = rowsInternal
        set(v) { rowsInternal = v; notifyDataSetChanged() }

    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false) as TextView
        val vh = VH(tv)
        tv.setOnClickListener { vh.bindingAdapterPosition.takeIf { it >= 0 }?.let(onClick) }
        tv.setOnFocusChangeListener { _, has -> if (has) vh.bindingAdapterPosition.takeIf { it >= 0 }?.let(onFocus) }
        return vh
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = rows[position]
        holder.tv.text = r.text
        holder.tv.isActivated = r.highlighted
    }

    override fun getItemCount() = rows.size

    /** 只改高亮，不整体刷新（避免丢失焦点）。 */
    fun setHighlighted(pos: Int) {
        val old = rows.indexOfFirst { it.highlighted }
        rowsInternal = rows.mapIndexed { i, r -> r.copy(highlighted = i == pos) }
        if (old >= 0) notifyItemChanged(old)
        if (pos in rows.indices) notifyItemChanged(pos)
    }
}

/** 滚动到 pos 并把焦点放上去（行可能尚未布局，延迟重试）。 */
fun RecyclerView.focusRow(pos: Int, tries: Int = 6) {
    val lm = layoutManager as LinearLayoutManager
    lm.scrollToPositionWithOffset(pos, (resources.displayMetrics.density * 160).toInt())
    post {
        val v = findViewHolderForAdapterPosition(pos)?.itemView
        if (v != null) v.requestFocus() else if (tries > 0) postDelayed({ focusRow(pos, tries - 1) }, 50)
    }
}
