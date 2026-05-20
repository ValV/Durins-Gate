package com.github.valv.durinsgate

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.valv.durinsgate.databinding.ItemSshConfigBinding

class SshConfigAdapter(
    private var configs: MutableList<SshConfig>,
    private val onEdit: (SshConfig) -> Unit,
    private val onConnect: (SshConfig) -> Unit,
    private val onDisconnect: (SshConfig) -> Unit,
    private val onCopyKey: (String?) -> Unit
) : RecyclerView.Adapter<SshConfigAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemSshConfigBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding =
            ItemSshConfigBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val config = configs[position]
        val isActive = SshForegroundService.isConfigActive(config.id)

        holder.binding.tvName.text = config.name.ifBlank { "Unnamed Config" }
        holder.binding.tvDetails.text =
            "${config.username}@${config.host}:${config.port} -> :${config.localPort}"

        holder.binding.btnKey.setOnClickListener { onCopyKey(config.keyAlias) }
        holder.binding.btnEdit.setOnClickListener { onEdit(config) }

        holder.binding.btnConnect.text = if (isActive) "Disconnect" else "Connect"
        holder.binding.btnConnect.setOnClickListener {
            if (isActive) onDisconnect(config) else onConnect(config)
        }
    }

    override fun getItemCount() = configs.size

    fun updateData(newConfigs: List<SshConfig>) {
        configs.clear()
        configs.addAll(newConfigs)
        notifyDataSetChanged()
    }

    fun getConfigs(): List<SshConfig> = configs
}
