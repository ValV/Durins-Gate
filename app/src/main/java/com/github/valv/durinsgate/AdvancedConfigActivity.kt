package com.github.valv.durinsgate

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class AdvancedConfigActivity : AppCompatActivity() {
    private lateinit var rvHostKeys: RecyclerView
    private lateinit var manager: HostKeyVerifierManager
    private lateinit var adapter: HostKeyAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_advanced_config)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        // We avoid setSupportActionBar(toolbar) to prevent the "Activity already has an action bar" crash.
        // Instead, we handle navigation and title directly on the Toolbar view.
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        toolbar.setNavigationOnClickListener { finish() }

        manager = HostKeyVerifierManager(this)
        rvHostKeys = findViewById(R.id.rv_host_keys)
        rvHostKeys.layoutManager = LinearLayoutManager(this)

        refreshList()
    }

    private fun refreshList() {
        adapter = HostKeyAdapter(manager.getKnownHosts()) { entry ->
            manager.removeHostKey(entry.raw)
            refreshList()
        }
        rvHostKeys.adapter = adapter
    }

    class HostKeyAdapter(
        private val items: List<KnownHostEntry>,
        private val onDelete: (KnownHostEntry) -> Unit
    ) : RecyclerView.Adapter<HostKeyAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvHost: TextView = view.findViewById(R.id.tv_host)
            val tvType: TextView = view.findViewById(R.id.tv_type)
            val btnDelete: ImageButton = view.findViewById(R.id.btn_delete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_host_key, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvHost.text = item.host
            holder.tvType.text = item.type
            holder.btnDelete.setOnClickListener { onDelete(item) }
        }

        override fun getItemCount() = items.size
    }
}
