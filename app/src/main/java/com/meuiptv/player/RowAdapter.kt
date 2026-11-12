package com.meuiptv.player

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class RowAdapter<T>(
    private val label: (T) -> String,
    private val onClick: (T, Int) -> Unit
) : RecyclerView.Adapter<RowAdapter.VH>() {

    private var items: List<T> = emptyList()
    private var selected = -1

    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    fun submit(list: List<T>) {
        items = list
        selected = -1
        notifyDataSetChanged()
    }

    fun select(pos: Int) {
        val old = selected
        selected = pos
        if (old >= 0) notifyItemChanged(old)
        if (pos >= 0) notifyItemChanged(pos)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false) as TextView
        return VH(tv)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.tv.text = label(items[position])
        holder.tv.isSelected = position == selected
        holder.tv.setOnClickListener {
            val p = holder.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(items[p], p)
        }
    }
}
