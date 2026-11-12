package com.meuiptv.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

/** Lista de canais/filmes/séries com a logo ou capa ao lado do nome. */
class ItemAdapter(
    private val label: (Item) -> String,
    private val onLongClick: (Item, Int) -> Unit,
    private val onClick: (Item, Int) -> Unit
) : RecyclerView.Adapter<ItemAdapter.VH>() {

    private var items: List<Item> = emptyList()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val iv: ImageView = v.findViewById(R.id.ivThumb)
        val tv: TextView = v.findViewById(R.id.tvTitle)
    }

    fun submit(list: List<Item>) {
        items = list
        notifyDataSetChanged()
    }

    fun refresh() = notifyDataSetChanged()

    fun itemAt(pos: Int): Item? = items.getOrNull(pos)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_media, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.tv.text = label(item)

        // Canais: logo retangular. Filmes/séries: capa em pé (poster).
        val d = holder.itemView.resources.displayMetrics.density
        val poster = item.kind != Kind.LIVE
        val lp = holder.iv.layoutParams
        lp.width = ((if (poster) 48 else 72) * d).toInt()
        lp.height = ((if (poster) 72 else 44) * d).toInt()
        holder.iv.layoutParams = lp
        holder.iv.scaleType = if (poster) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER

        if (item.image.isNotBlank()) {
            Glide.with(holder.iv)
                .load(item.image)
                .placeholder(R.drawable.ph_media)
                .error(R.drawable.ph_media)
                .into(holder.iv)
        } else {
            Glide.with(holder.iv).clear(holder.iv)
            holder.iv.setImageResource(R.drawable.ph_media)
        }

        holder.itemView.setOnClickListener {
            val p = holder.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(items[p], p)
        }
        holder.itemView.setOnLongClickListener {
            val p = holder.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onLongClick(items[p], p)
            true
        }
    }
}
