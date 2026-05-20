package com.github.valv.durinsgate

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.valv.durinsgate.databinding.ItemKeyBinding

class KeyAdapter(
    private var keys: MutableList<String>,
    private val onCopyPublic: (String) -> Unit,
    private val onDelete: (String) -> Unit
) : RecyclerView.Adapter<KeyAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemKeyBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemKeyBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val keyName = keys[position]
        holder.binding.tvKeyName.text = keyName
        holder.binding.btnCopyPublic.setOnClickListener { onCopyPublic(keyName) }
        holder.binding.btnDeleteKey.setOnClickListener { onDelete(keyName) }
    }

    override fun getItemCount() = keys.size

    fun updateKeys(newKeys: List<String>) {
        keys.clear()
        keys.addAll(newKeys)
        notifyDataSetChanged()
    }
}
