package com.github.valv.durinsgate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

class SshForegroundService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val activeClients = ConcurrentHashMap<String, SSHClient>()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeProxies = ConcurrentHashMap<String, ServerSocket>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    companion object {
        const val TAG = "SshService"
        const val CHANNEL_ID = "ssh_tunnel_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP_ALL = "STOP_ALL"
        const val ACTION_STOP_CONFIG = "STOP_CONFIG"
        const val ACTION_RETRY_CONFIG = "RETRY_CONFIG"
        const val EXTRA_CONFIG_ID = "config_id"

        private val _activeConfigIds = mutableSetOf<String>()
        val activeConfigIds: Set<String> get() = synchronized(_activeConfigIds) { _activeConfigIds.toSet() }

        fun isConfigActive(id: String) =
            synchronized(_activeConfigIds) { _activeConfigIds.contains(id) }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock =
            powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DurinsGate::SSH_WakeLock")

        serviceScope.launch {
            KeyManager(this@SshForegroundService).cleanupOrphanedKeys()
        }

        registerNetworkCallback()
    }

    private fun registerNetworkCallback() {
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network restored. Attempting to reconnect gates...")
                restoreTunnelsFromStorage()
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback!!)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateSummaryNotification()

        if (intent == null) {
            restoreTunnelsFromStorage()
            return START_STICKY
        }

        when (intent.action) {
            ACTION_STOP_ALL -> {
                stopAll()
                return START_NOT_STICKY
            }

            ACTION_STOP_CONFIG -> {
                val configId = intent.getStringExtra(EXTRA_CONFIG_ID)
                if (configId != null) stopConfig(configId)
                return START_STICKY
            }

            ACTION_RETRY_CONFIG -> {
                val configId = intent.getStringExtra(EXTRA_CONFIG_ID)
                if (configId != null) {
                    serviceScope.launch {
                        val storage = ConfigStorage(this@SshForegroundService)
                        storage.loadConfigs().find { it.id == configId }?.let { startConfig(it) }
                    }
                }
                return START_STICKY
            }
        }

        // Fix 7: Use EXTRA_CONFIG_ID instead of serialized object
        val configId = intent.getStringExtra(EXTRA_CONFIG_ID)
        if (configId != null) {
            serviceScope.launch {
                val storage = ConfigStorage(this@SshForegroundService)
                storage.loadConfigs().find { it.id == configId }?.let { startConfig(it) }
            }
        } else {
            restoreTunnelsFromStorage()
        }

        return START_STICKY
    }

    private fun restoreTunnelsFromStorage() {
        serviceScope.launch {
            val storage = ConfigStorage(this@SshForegroundService)
            val enabledConfigs = storage.loadConfigs().filter { it.isEnabled }
            withContext(Dispatchers.Main) {
                if (enabledConfigs.isEmpty() && activeConfigIds.isEmpty()) {
                    stopServiceInternal()
                } else {
                    enabledConfigs.forEach { startConfig(it) }
                    updateSummaryNotification()
                }
            }
        }
    }

    private fun startConfig(config: SshConfig) {
        if (isConfigActive(config.id)) return

        // Fix 1: Config ID is added only after successful authentication in establishTunnel

        if (wakeLock?.isHeld == false) {
            try {
                wakeLock?.acquire(config.timeoutWakeLock)
            } catch (e: Exception) {
            }
        }

        updateSummaryNotification()

        val job = serviceScope.launch {
            try {
                establishTunnel(config)
            } catch (e: CancellationException) {
                // Shutdown
            } catch (e: Exception) {
                // Logged in establishTunnel
            } finally {
                onConfigDisconnected(config.id)
            }
        }
        activeJobs[config.id] = job
    }

    private fun updateSummaryNotification() {
        serviceScope.launch {
            val storage = ConfigStorage(this@SshForegroundService)
            val enabledConfigs = storage.loadConfigs().filter { it.isEnabled }
            val activeCount = activeConfigIds.size
            val enabledCount = enabledConfigs.size

            withContext(Dispatchers.Main) {
                val statusText = when {
                    activeCount > 0 && activeCount < enabledCount -> "Connecting: $activeCount/$enabledCount"
                    activeCount > 0 -> "Active Gates: $activeCount"
                    enabledCount > 0 -> "Waiting for network..."
                    else -> "Durin's Gate Service Active"
                }

                val notification = createNotification(statusText)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }
        }
    }

    private fun showVerificationNotification(config: SshConfig) {
        val intent = Intent(this, HostKeyApprovalActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CONFIG_ID, config.id)
            putExtra("hostname", config.host)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            config.id.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Security Verification Required")
            .setContentText("Gate [${config.name}] encountered an unknown host key.")
            .setSmallIcon(R.drawable.ic_notification_gate)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        // Unique ID per host to allow multiple simultaneous verifications
        manager.notify(config.host.hashCode(), notification)
    }

    // Fix 5: Proxy error notification
    private fun showProxyErrorNotification(config: SshConfig, port: Int) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Proxy Error")
            .setContentText("Gate [${config.name}]: SOCKS5 proxy failed to bind to port $port.")
            .setSmallIcon(R.drawable.ic_notification_gate)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(config.id.hashCode() + 1000, notification)
    }

    private fun stopConfig(configId: String) {
        activeJobs[configId]?.cancel()
        activeClients[configId]?.disconnect()
        activeProxies[configId]?.close()
        onConfigDisconnected(configId)
    }

    private fun stopAll() {
        val storage = ConfigStorage(this)
        val configs = storage.loadConfigs()
        configs.forEach { it.isEnabled = false }
        storage.saveConfigs(configs)

        activeJobs.values.forEach { it.cancel() }
        activeClients.values.forEach { it.disconnect() }
        activeProxies.values.forEach { it.close() }
        synchronized(_activeConfigIds) { _activeConfigIds.clear() }
        stopServiceInternal()
    }

    private fun onConfigDisconnected(configId: String) {
        activeJobs.remove(configId)
        activeClients.remove(configId)
        activeProxies.remove(configId)
        synchronized(_activeConfigIds) { _activeConfigIds.remove(configId) }

        serviceScope.launch {
            val storage = ConfigStorage(this@SshForegroundService)
            val configs = storage.loadConfigs()
            val anyEnabled = configs.any { it.isEnabled }
            val config = configs.find { it.id == configId }

            withContext(Dispatchers.Main) {
                if (activeConfigIds.isEmpty() && !anyEnabled) {
                    stopServiceInternal()
                } else {
                    updateSummaryNotification()
                    if (config?.isEnabled == true) {
                        // Fix 3 & 8: Verification timeout and stale check
                        val pending = HostKeyVerifierManager.pendingVerifications[config.host]
                        val isPending = pending != null
                        val isStale = isPending && (System.currentTimeMillis() - pending.second > config.timeoutVerification)

                        if (!isPending || isStale) {
                            if (isStale) {
                                HostKeyVerifierManager.pendingVerifications.remove(config.host)
                            }
                            delay(config.timeoutVerificationRetry)
                            startConfig(config)
                        }
                    }
                }
            }
        }
    }

    private suspend fun establishTunnel(config: SshConfig) = withContext(Dispatchers.IO) {
        val client = SSHClient()
        activeClients[config.id] = client

        try {
            val verifierManager = HostKeyVerifierManager(this@SshForegroundService)
            client.addHostKeyVerifier(verifierManager.getVerifier())

            client.connectTimeout = config.timeoutConnecct
            client.timeout = config.timeoutClient

            LogRepository.log("Gate [${config.name}]: Connecting...")

            try {
                client.connect(config.host, config.port)
            } catch (e: Exception) {
                var cause: Throwable? = e
                while (cause != null) {
                    if (cause is HostKeyVerificationException) {
                        // Fix 3: Track timestamp
                        HostKeyVerifierManager.pendingVerifications[config.host] = Pair(cause, System.currentTimeMillis())
                        showVerificationNotification(config)
                        LogRepository.log("Gate [${config.name}]: Host verification required.")
                        throw cause
                    }
                    cause = cause.cause
                }
                throw e
            }

            config.keyAlias?.let { alias ->
                LogRepository.log("Gate [${config.name}]: Authenticating...")
                val keyManager = KeyManager(this@SshForegroundService)
                client.authPublickey(
                    config.username,
                    client.loadKeys(keyManager.getPrivateKeyPath(alias))
                )
            }

            client.connection.keepAlive.keepAliveInterval = config.keepAliveInterval

            if (client.isAuthenticated) {
                LogRepository.log("Gate Open: ${config.name}")
                // Fix 1: Add to active set only after successful authentication
                synchronized(_activeConfigIds) { _activeConfigIds.add(config.id) }
                updateSummaryNotification()

                coroutineScope {
                    var proxyJob: Job? = null
                    if (config.isSocks5) {
                        proxyJob = launch { runSocks5Server(config, client, config.localPort) }
                    }

                    try {
                        while (isActive && client.isConnected) {
                            delay(config.timeoutJob)
                        }
                    } finally {
                        proxyJob?.cancelAndJoin()
                        activeProxies[config.id]?.close()
                    }
                }
                LogRepository.log("Gate Closed: ${config.name}")
            } else {
                LogRepository.log("Gate [${config.name}] Auth Failed")
                throw Exception("Auth failed")
            }
        } catch (e: Exception) {
            if (isActive) {
                if (e !is HostKeyVerificationException) {
                    LogRepository.log("Gate [${config.name}] error: ${e.message ?: "Unknown error"}")
                }
                throw e
            }
        } finally {
            try {
                client.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Error disconnecting client", e)
            }
            activeClients.remove(config.id)
        }
    }

    private suspend fun runSocks5Server(config: SshConfig, ssh: SSHClient, port: Int) =
        withContext(Dispatchers.IO) {
            var serverSocket: ServerSocket? = null
            try {
                var retries = config.keepAliveRetries
                while (retries > 0 && isActive) {
                    try {
                        serverSocket = ServerSocket()
                        serverSocket.reuseAddress = true
                        serverSocket.bind(InetSocketAddress("127.0.0.1", port))
                        activeProxies[config.id] = serverSocket
                        Log.d(TAG, "SOCKS5 Proxy ready on port $port")
                        break
                    } catch (e: java.net.BindException) {
                        serverSocket?.close()
                        serverSocket = null
                        retries--
                        if (retries > 0) {
                            delay(config.timeoutRetry)
                        } else {
                            // Fix 5: Explicit log and notification
                            LogRepository.log(
                                "Gate [${config.name}]: SOCKS5 proxy failed to bind to port $port after ${config.keepAliveRetries} retries"
                            )
                            showProxyErrorNotification(config, port)
                            return@withContext
                        }
                    }
                }

                while (isActive && serverSocket?.isClosed == false) {
                    val clientSocket = try {
                        serverSocket?.accept()
                    } catch (e: Exception) {
                        null
                    }
                    if (clientSocket != null) {
                        launch { handleSocks5Request(ssh, clientSocket) }
                    }
                }
            } finally {
                activeProxies.remove(config.id)
                try {
                    serverSocket?.close()
                } catch (e: Exception) {
                }
            }
        }

    private suspend fun handleSocks5Request(ssh: SSHClient, socket: Socket) =
        withContext(Dispatchers.IO) {
            socket.use { s ->
                try {
                    val input = s.getInputStream()
                    val output = s.getOutputStream()

                    // Bounds check for initial greeting
                    val version = input.read()
                    if (version != 5) return@withContext

                    val nMethods = input.read()
                    if (nMethods <= 0) return@withContext

                    val methods = ByteArray(nMethods)
                    var methodsRead = 0
                    while (methodsRead < nMethods) {
                        val read = input.read(methods, methodsRead, nMethods - methodsRead)
                        if (read == -1) return@withContext
                        methodsRead += read
                    }

                    output.write(byteArrayOf(5, 0))
                    output.flush()

                    // Request
                    val ver = input.read()
                    if (ver != 5) return@withContext
                    val cmd = input.read()
                    input.read() // RSV
                    val atyp = input.read()

                    val targetHost = when (atyp) {
                        1 -> {
                            val addr = ByteArray(4)
                            input.readFully(addr)
                            java.net.InetAddress.getByAddress(addr).hostAddress
                        }

                        3 -> {
                            // Fix 4: Size limits in SOCKS5
                            val len = input.read()
                            if (len <= 0 || len > 255) return@withContext
                            val addr = ByteArray(len)
                            input.readFully(addr)
                            String(addr)
                        }

                        4 -> { // IPv6
                            val addr = ByteArray(16)
                            input.readFully(addr)
                            java.net.InetAddress.getByAddress(addr).hostAddress
                        }

                        else -> return@withContext
                    }

                    val p1 = input.read()
                    val p2 = input.read()
                    if (p1 == -1 || p2 == -1) return@withContext
                    val targetPort = ((p1 and 0xFF) shl 8) or (p2 and 0xFF)

                    if (cmd != 1) return@withContext

                    ssh.newDirectConnection(targetHost, targetPort).use { channel ->
                        output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                        output.flush()

                        coroutineScope {
                            launch { pipe(input, channel.outputStream) }
                            launch { pipe(channel.inputStream, output) }
                        }
                    }
                } catch (e: Exception) {
                    // Fix 6: Pipe error handling
                    Log.w(TAG, "SOCKS5 request error: ${e.message}")
                }
            }
        }

    private fun InputStream.readFully(buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read == -1) throw java.io.IOException("EOF")
            offset += read
        }
    }

    private fun pipe(from: InputStream, to: OutputStream) {
        val buffer = ByteArray(16384)
        try {
            var read: Int
            while (from.read(buffer).also { read = it } != -1) {
                if (read > 0) {
                    to.write(buffer, 0, read)
                    to.flush()
                }
            }
        } catch (e: Exception) {
            // Fix 6: Pipe error handling
            Log.w(TAG, "Pipe error during SOCKS5 forwarding: ${e.message}")
        }
    }

    private fun stopServiceInternal() {
        if (wakeLock?.isHeld == true) try {
            wakeLock?.release()
        } catch (e: Exception) {
        }
        serviceJob.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotification(content: String): Notification {
        val stopIntent =
            Intent(this, SshForegroundService::class.java).apply { action = ACTION_STOP_ALL }
        val stopPendingIntent =
            PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        val mainIntent = Intent(this, MainActivity::class.java)
        val pendingMainIntent =
            PendingIntent.getActivity(this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Durin's Gate")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification_gate)
            .setOngoing(true)
            .setContentIntent(pendingMainIntent)
            .addAction(R.drawable.ic_notification_gate, "Stop All", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(CHANNEL_ID, "SSH", NotificationManager.IMPORTANCE_HIGH)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        networkCallback?.let {
            val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            manager.unregisterNetworkCallback(it)
        }
        serviceJob.cancel()
        super.onDestroy()
    }
}
