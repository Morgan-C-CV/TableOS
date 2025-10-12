package com.tableos.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import android.graphics.Color
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
        val res = holder.card.resources
        val focusStroke = res.getDimensionPixelSize(R.dimen.app_tile_focus_stroke)
        val paddingDefault = res.getDimensionPixelSize(R.dimen.app_tile_icon_padding_default)
        val paddingFocus = res.getDimensionPixelSize(R.dimen.app_tile_icon_padding_focus)

        // 初始化非焦点态：透明描边与默认 padding
        holder.card.strokeColor = Color.TRANSPARENT
        holder.card.strokeWidth = 0
        holder.icon.setPadding(paddingDefault, paddingDefault, paddingDefault, paddingDefault)

        holder.card.setOnFocusChangeListener { v, hasFocus ->
            val card = v as MaterialCardView
            if (hasFocus) {
                card.strokeColor = Color.parseColor("#4DA3F7")
                card.strokeWidth = focusStroke
                holder.icon.setPadding(paddingFocus, paddingFocus, paddingFocus, paddingFocus)
            } else {
                card.strokeColor = Color.TRANSPARENT
                card.strokeWidth = 0
                holder.icon.setPadding(paddingDefault, paddingDefault, paddingDefault, paddingDefault)
            }
        }
        holder.card.setOnClickListener { onLaunch(item) }
    }

    override fun getItemCount(): Int = items.size

    class AppVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val card: MaterialCardView = itemView.findViewById(R.id.app_card)
        val icon: ImageView = itemView.findViewById(R.id.app_icon)
        val label: TextView = itemView.findViewById(R.id.app_label)
    }
}