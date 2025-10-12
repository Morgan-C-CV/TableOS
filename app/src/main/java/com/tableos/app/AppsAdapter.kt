package com.tableos.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView

class AppsAdapter(
    private var items: List<AppInfo>,
    private val onLaunch: (AppInfo) -> Unit
) : RecyclerView.Adapter<AppsAdapter.AppVH>() {

    fun submitList(newItems: List<AppInfo>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_tile, parent, false)
        return AppVH(view)
    }

    override fun onBindViewHolder(holder: AppVH, position: Int) {
        val item = items[position]
        holder.icon.setImageDrawable(item.icon)
        holder.label.text = item.label

        holder.card.isFocusable = true
        holder.card.isClickable = true
        holder.card.setOnClickListener { onLaunch(item) }
    }

    override fun getItemCount(): Int = items.size

    class AppVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val card: CardView = itemView.findViewById(R.id.app_card)
        val icon: ImageView = itemView.findViewById(R.id.app_icon)
        val label: TextView = itemView.findViewById(R.id.app_label)
    }
}