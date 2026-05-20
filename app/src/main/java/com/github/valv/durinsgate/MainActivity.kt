package com.github.valv.durinsgate

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.valv.durinsgate.databinding.ActivityMainBinding
import com.github.valv.durinsgate.databinding.DialogEditConfigBinding
import com.github.valv.durinsgate.databinding.DialogManageKeysBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var storage: ConfigStorage
    private lateinit var adapter: SshConfigAdapter
    private lateinit var keyManager: KeyManager

    private var iconTapCount = 0
    private var lastTapTime = 0L

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Toast.makeText(this, "Notification permission is required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        KeyManager.setupBouncyCastle()
        storage = ConfigStorage(this)
        keyManager = KeyManager(this)

        setupToolbar()
        setupRecyclerView()
        setupLogs()
        checkPermissions()
        checkBatteryOptimizations()
        startStatePolling()

        restoreTunnels()

        binding.fabAdd.setOnClickListener { showEditDialog(null) }
    }

    private fun setupToolbar() {
        val icon = findViewById<ImageView>(R.id.iv_header_icon)
        icon.setOnClickListener {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastTapTime > 2000) {
                iconTapCount = 0
            }
            lastTapTime = currentTime
            iconTapCount++

            if (iconTapCount == 7) {
                iconTapCount = 0
                startActivity(Intent(this, AdvancedConfigActivity::class.java))
            } else if (iconTapCount > 3) {
                Toast.makeText(this, "You are ${7 - iconTapCount} steps away from advanced settings", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkBatteryOptimizations() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                AlertDialog.Builder(this)
                    .setTitle("Keep Tunnels Alive")
                    .setMessage("To prevent the SSH tunnel from being killed in the background, please disable battery optimization for Durin Gate.")
                    .setPositiveButton("Settings") { _, _ ->
                        val intent =
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:$packageName")
                            }
                        startActivity(intent)
                    }
                    .setNegativeButton("Later", null)
                    .show()
            }
        }
    }

    private fun restoreTunnels() {
        val configs = storage.loadConfigs()
        configs.filter { it.isEnabled }.forEach { startSshService(it) }
    }

    private fun setupRecyclerView() {
        adapter = SshConfigAdapter(
            storage.loadConfigs(),
            onEdit = { showEditDialog(it) },
            onConnect = { config ->
                updateConfigEnabledState(config.id, true)
                startSshService(config)
            },
            onDisconnect = { config ->
                updateConfigEnabledState(config.id, false)
                stopSshService(config.id)
            },
            onCopyKey = { alias ->
                alias?.let {
                    val pubKey = keyManager.getPublicKey(it)
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("SSH Public Key", pubKey))
                    Toast.makeText(this, "Public key copied", Toast.LENGTH_SHORT).show()
                }
            }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    private fun updateConfigEnabledState(id: String, enabled: Boolean) {
        val configs = storage.loadConfigs()
        configs.find { it.id == id }?.let {
            it.isEnabled = enabled
            storage.saveConfigs(configs)
            adapter.updateData(configs)
        }
    }

    private fun startSshService(config: SshConfig) {
        val intent =
            Intent(this, SshForegroundService::class.java).apply { putExtra("config", config) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(
            intent
        )
    }

    private fun stopSshService(configId: String) {
        val intent = Intent(this, SshForegroundService::class.java).apply {
            action = SshForegroundService.ACTION_STOP_CONFIG
            putExtra(SshForegroundService.EXTRA_CONFIG_ID, configId)
        }
        startService(intent)
    }

    private fun startStatePolling() {
        lifecycleScope.launch {
            while (true) {
                adapter.notifyDataSetChanged()
                delay(2000)
            }
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun setupLogs() {
        lifecycleScope.launch {
            LogRepository.logs.collectLatest { message ->
                binding.tvLogs.append("\n$message")
                binding.logScrollView.post { binding.logScrollView.fullScroll(android.view.View.FOCUS_DOWN) }
            }
        }
    }

    private fun showEditDialog(config: SshConfig?) {
        val dialogBinding = DialogEditConfigBinding.inflate(LayoutInflater.from(this))
        val isEdit = config != null
        val targetConfig = config ?: SshConfig()

        if (isEdit) {
            dialogBinding.etName.setText(targetConfig.name)
            dialogBinding.etHost.setText(targetConfig.host)
            dialogBinding.etPort.setText(targetConfig.port.toString())
            dialogBinding.etUsername.setText(targetConfig.username)
            dialogBinding.etLocalPort.setText(targetConfig.localPort.toString())
            dialogBinding.cbSocks5.isChecked = targetConfig.isSocks5
        }

        val keys = mutableListOf("None")
        keys.addAll(keyManager.listKeys())
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, keys)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        dialogBinding.spinnerKeys.adapter = spinnerAdapter

        targetConfig.keyAlias?.let {
            val index = keys.indexOf(it)
            if (index >= 0) dialogBinding.spinnerKeys.setSelection(index)
        }

        dialogBinding.btnManageKeys.setOnClickListener {
            showManageKeysDialog {
                keys.clear()
                keys.add("None")
                keys.addAll(keyManager.listKeys())
                spinnerAdapter.notifyDataSetChanged()
            }
        }

        AlertDialog.Builder(this)
            .setTitle(if (isEdit) "Edit Gate" else "New Gate")
            .setView(dialogBinding.root)
            .setPositiveButton("Save") { _, _ ->
                targetConfig.apply {
                    name = dialogBinding.etName.text.toString()
                    host = dialogBinding.etHost.text.toString()
                    port = dialogBinding.etPort.text.toString().toIntOrNull() ?: 22
                    username = dialogBinding.etUsername.text.toString()
                    localPort = dialogBinding.etLocalPort.text.toString().toIntOrNull() ?: 1080
                    isSocks5 = dialogBinding.cbSocks5.isChecked
                    val selectedKey = dialogBinding.spinnerKeys.selectedItem.toString()
                    keyAlias = if (selectedKey == "None") null else selectedKey
                }

                val currentConfigs = storage.loadConfigs()
                if (isEdit) {
                    val index = currentConfigs.indexOfFirst { it.id == targetConfig.id }
                    if (index >= 0) currentConfigs[index] = targetConfig
                } else {
                    currentConfigs.add(targetConfig)
                }

                storage.saveConfigs(currentConfigs)
                adapter.updateData(currentConfigs)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showManageKeysDialog(onDismiss: () -> Unit) {
        val keyDialogBinding = DialogManageKeysBinding.inflate(LayoutInflater.from(this))
        val keysList = keyManager.listKeys().toMutableList()

        var keyAdapter: KeyAdapter? = null
        keyAdapter = KeyAdapter(
            keysList,
            onCopyPublic = { alias ->
                val pubKey = keyManager.getPublicKey(alias)
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("SSH Public Key", pubKey))
                Toast.makeText(this, "Public key copied", Toast.LENGTH_SHORT).show()
            },
            onDelete = { alias ->
                keyManager.deleteKey(alias)
                keysList.remove(alias)
                keyAdapter?.updateKeys(keyManager.listKeys())
            }
        )

        keyDialogBinding.rvKeys.layoutManager = LinearLayoutManager(this)
        keyDialogBinding.rvKeys.adapter = keyAdapter

        keyDialogBinding.btnGenEd.setOnClickListener {
            val name = keyDialogBinding.etNewKeyName.text.toString()
                .ifBlank { "ed_${System.currentTimeMillis()}" }
            keyManager.generateEd25519Key(name)
            keyAdapter.updateKeys(keyManager.listKeys())
            keyDialogBinding.etNewKeyName.text?.clear()
        }

        keyDialogBinding.btnGenRsa.setOnClickListener {
            val name = keyDialogBinding.etNewKeyName.text.toString()
                .ifBlank { "rsa_${System.currentTimeMillis()}" }
            keyManager.generateRSAKey(name, 4096)
            keyAdapter.updateKeys(keyManager.listKeys())
            keyDialogBinding.etNewKeyName.text?.clear()
        }

        AlertDialog.Builder(this)
            .setTitle("Manage SSH Keys")
            .setView(keyDialogBinding.root)
            .setOnDismissListener { onDismiss() }
            .setPositiveButton("Close", null)
            .show()
    }
}
