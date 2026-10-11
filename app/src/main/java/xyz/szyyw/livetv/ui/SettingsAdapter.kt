package xyz.szyyw.livetv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import xyz.szyyw.livetv.R

/** 设置列表的行：分组标题（不可聚焦）或设置项（名称 + 当前值）。cyclic = 左右键/确定可直接循环切换。 */
sealed interface SRow
data class SHeader(val text: String) : SRow
data class SItem(val id: String, val title: String, val value: String, val cyclic: Boolean) : SRow

class SettingsAdapter(private val onClick: (SItem) -> Unit) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var data: List<SRow> = emptyList()

    /** 行数不变时只刷新内容（保住焦点）。 */
    var rows: List<SRow>
        get() = data
        set(v) {
            val same = v.size == data.size
            data = v
            if (same) notifyItemRangeChanged(0, v.size) else notifyDataSetChanged()
        }

    class ItemVH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.setting_name)
        val value: TextView = v.findViewById(R.id.setting_value)
    }
    class HeaderVH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    override fun getItemViewType(position: Int) = if (data[position] is SHeader) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        if (viewType == 0) return HeaderVH(inf.inflate(R.layout.item_header, parent, false) as TextView)
        val vh = ItemVH(inf.inflate(R.layout.item_setting, parent, false))
        vh.itemView.setOnClickListener {
            vh.bindingAdapterPosition.takeIf { it >= 0 }?.let { p -> (data[p] as? SItem)?.let(onClick) }
        }
        return vh
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val r = data[position]) {
            is SHeader -> (holder as HeaderVH).tv.text = r.text
            is SItem -> (holder as ItemVH).let {
                it.name.text = r.title
                it.value.text = if (r.cyclic) "◀ ${r.value} ▶" else r.value
            }
        }
    }

    override fun getItemCount() = data.size
}
