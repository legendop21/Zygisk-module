package com.hivirtus.zygiskmode

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.CheckBox
import androidx.recyclerview.widget.RecyclerView
import com.hivirtus.zygiskmode.databinding.ItemUpiAppRowBinding

class UpiAppListAdapter(
    private val apps: List<UpiAppRegistry.UpiApp>,
    initialSelection: Map<String, Boolean>
) : RecyclerView.Adapter<UpiAppListAdapter.Holder>() {

    private val selected = apps.associate { it.packageName to (initialSelection[it.packageName] == true) }
        .toMutableMap()

    fun selectedMap(): Map<String, Boolean> = selected.toMap()

    fun selectedCount(): Int = selected.count { it.value }

    fun setAll(checked: Boolean) {
        apps.forEach { selected[it.packageName] = checked }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemUpiAppRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding.cbUpiApp)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val app = apps[position]
        holder.checkBox.setOnCheckedChangeListener(null)
        holder.checkBox.text = app.displayName
        holder.checkBox.isChecked = selected[app.packageName] == true
        holder.checkBox.setOnCheckedChangeListener { _, checked ->
            selected[app.packageName] = checked
        }
    }

    override fun getItemCount(): Int = apps.size

    class Holder(val checkBox: CheckBox) : RecyclerView.ViewHolder(checkBox)
}
