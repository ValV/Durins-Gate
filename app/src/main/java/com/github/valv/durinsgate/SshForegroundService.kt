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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

class SshForegroundService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var wakeLock: PowerManager.WakeLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var currentNetwork: Network? = null

    // Clean tracking wrapper for active tunnel components
    private class TunnelSession(
        val client: SSHClient,
        val serverSocket: ServerSocket?,
        val job: Job
    ) {
        fun close() {
            try { serverSocket?.close() } catch (e: Exception) {}
            try { client.disconnect() } catch (e: Exception) {}
            job.cancel()
        }
    }

    companion object {
        const val TAG = "SshService"
        const val CHANNEL_ID = "ssh_tunnel_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP_ALL = "STOP_ALL"
        const val ACTION_STOP_CONFIG = "STOP_CONFIG"
        const val ACTION_RETRY_CONFIG = "RETRY_CONFIG"
        const val EXTRA_CONFIG_ID = "config_id"

        // Global, thread-safe references to manage tunnel critical sections
        private val tunnelLocks = ConcurrentHashMap<String, Mutex>()
        private val activeTunnels = ConcurrentHashMap<String, TunnelSession>()

        fun isConfigActive(id: String): Boolean = activeTunnels.containsKey(id)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "DurinsGate::SSH_WakeLock"
        )

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
                if (currentNetwork == network) return

                Log.d(TAG, "Network changed/restored. Refreshing tunnels...")
                currentNetwork = network

                // If network actually changed, we must drop existing dangling connections
                // because their TCP sockets are bound to the old network interface
                serviceScope.launch {
                    val storage = ConfigStorage(this@SshForegroundService)
                    storage.loadConfigs().filter { it.isEnabled }.forEach { config ->
                        stopTunnel(config.id)
                        startTunnel(config)
                    }
                }
            }

            override fun onLost(network: Network) {
                if (currentNetwork == network) {
                    currentNetwork = null
                    Log.d(TAG, "Primary network lost.")
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback!!)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
                if (configId != null) {
                    serviceScope.launch {
                        val storage = ConfigStorage(this@SshForegroundService)
                        storage.loadConfigs().find { it.id == configId }?.let { config ->
                            config.isEnabled = false
                            storage.saveConfigs(storage.loadConfigs().map { if (it.id == config.id) config else it })
                        }
                        stopTunnel(configId)
                    }
                }

                return START_STICKY
            }

            ACTION_RETRY_CONFIG -> {
                val configId = intent.getStringExtra(EXTRA_CONFIG_ID)
                if (configId != null) {
                    serviceScope.launch {
                        val storage = ConfigStorage(this@SshForegroundService)
                        storage.loadConfigs().find { it.id == configId }?.let {
                            stopTunnel(configId)
                            startTunnel(it)
                        }
                    }
                }
                return START_STICKY
            }
        }

        val configId = intent.getStringExtra(EXTRA_CONFIG_ID)
        if (configId != null) {
            serviceScope.launch {
                val storage = ConfigStorage(this@SshForegroundService)
                storage.loadConfigs().find { it.id == configId }?.let { startTunnel(it) }
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
            if (enabledConfigs.isEmpty() && activeTunnels.isEmpty()) {
                stopServiceInternal()
            } else {
                enabledConfigs.forEach { startTunnel(it) }
                updateSummaryNotification()
            }
        }
    }

    private fun startTunnel(config: SshConfig) {
        val mutex = tunnelLocks.getOrPut(config.id) { Mutex() }

        serviceScope.launch {
            mutex.withLock {
                if (activeTunnels.containsKey(config.id)) return@withLock

                if (wakeLock?.isHeld == false) {
                    try { wakeLock?.acquire(config.timeoutWakeLock) } catch (e: Exception) {}
                }

                var retryCount = 0
                while (isActive && currentNetwork != null) {
                    // Refetch config status to verify the user did not disable it while waiting
                    val currentConfigs = ConfigStorage(this@SshForegroundService).loadConfigs()
                    val freshConfig = currentConfigs.find { it.id == config.id }
                    if (freshConfig == null || !freshConfig.isEnabled) break

                    val client = SSHClient()
                    var serverSocket: ServerSocket? = null
                    val tunnelJob = SupervisorJob()

                    try {
                        val verifierManager = HostKeyVerifierManager(this@SshForegroundService)
                        client.addHostKeyVerifier(verifierManager.getVerifier())
                        client.connectTimeout = freshConfig.timeoutConnect
                        client.timeout = freshConfig.timeoutClient

                        LogRepository.log("Gate [${freshConfig.name}]: Connecting...")
                        try {
                            client.connect(freshConfig.host, freshConfig.port)
                        } catch (e: Exception) {
                            var cause: Throwable? = e
                            while (cause != null) {
                                if (cause is HostKeyVerificationException) {
                                    val lookupKey = HostKeyVerifierManager.getLookupKey(cause.hostname, cause.port)
                                    HostKeyVerifierManager.pendingVerifications[lookupKey] = Pair(cause, System.currentTimeMillis())
                                    showVerificationNotification(freshConfig)
                                    LogRepository.log("Gate [${freshConfig.name}]: Host verification required.")
                                    throw cause
                                }
                                cause = cause.cause
                            }
                            throw e
                        }

                        freshConfig.keyAlias?.let { alias ->
                            LogRepository.log("Gate [${freshConfig.name}]: Authenticating...")
                            val keyManager = KeyManager(this@SshForegroundService)
                            client.authPublickey(freshConfig.username, client.loadKeys(keyManager.getPrivateKeyPath(alias)))
                        }

                        client.connection.keepAlive.keepAliveInterval = freshConfig.keepAliveInterval

                        if (!client.isAuthenticated) {
                            throw Exception("Authentication failed")
                        }

                        LogRepository.log("Gate Open: ${freshConfig.name}")
                        retryCount = 0 // Reset exponential backoff on successful connect
                        updateSummaryNotification()

                        if (freshConfig.isSocks5) {
                            serverSocket = ServerSocket().apply {
                                reuseAddress = true
                                bind(InetSocketAddress("127.0.0.1", freshConfig.localPort))
                            }
                        }

                        val session = TunnelSession(client, serverSocket, tunnelJob)
                        activeTunnels[freshConfig.id] = session

                        coroutineScope {
                            if (serverSocket != null) {
                                launch(tunnelJob + Dispatchers.IO) {
                                    runSocks5Server(freshConfig, client, serverSocket)
                                }
                            }
                            // Relying on SSHJ's native background keepAlive system;
                            // we only monitor connection status here
                            while (isActive && client.isConnected) {
                                delay(freshConfig.connectionCheckInterval)
                            }
                        }
                    } catch (e: HostKeyVerificationException) {
                        break // Prevent retry loops when manual verification is needed
                    } catch (e: Exception) {
                        if (!isActive) break
                        LogRepository.log("Gate [${freshConfig.name}] Error: ${e.message ?: "Connection Lost"}")
                    } finally {
                        // Cleanup single tunnel instances immediately on disconnection
                        activeTunnels.remove(freshConfig.id)?.close()
                        try { serverSocket?.close() } catch (ex: Exception) {}
                        try { client.disconnect() } catch (ex: Exception) {}
                        tunnelJob.cancel()
                        updateSummaryNotification()
                    }

                    if (currentNetwork == null) break
                    val checkConfigs = ConfigStorage(this@SshForegroundService).loadConfigs()
                    if (checkConfigs.find { it.id == config.id }?.isEnabled != true) break

                    retryCount++
                    val delayMs = (freshConfig.timeoutVerificationRetry * freshConfig.backoffMultiplier.pow((retryCount - 1).toDouble()))
                        .toLong().coerceAtMost(freshConfig.maxRetryDelay)

                    LogRepository.log("Gate [${freshConfig.name}]: Retrying in ${delayMs / 1000}s (Attempt $retryCount)")
                    delay(delayMs)
                }
            }

            // Auto shutdown service if no active or enabled tunnels exist
            val storage = ConfigStorage(this@SshForegroundService)
            if (activeTunnels.isEmpty() && storage.loadConfigs().none { it.isEnabled }) {
                stopServiceInternal()
            }
        }
    }

    private fun stopTunnel(configId: String) {
        activeTunnels.remove(configId)?.close()
    }

    private fun stopAll() {
        val storage = ConfigStorage(this)
        val configs = storage.loadConfigs()
        configs.forEach { it.isEnabled = false }
        storage.saveConfigs(configs)

        activeTunnels.keys.toList().forEach { stopTunnel(it) }
        stopServiceInternal()
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

    private suspend fun runSocks5Server(config: SshConfig, ssh: SSHClient, serverSocket: ServerSocket) =
        withContext(Dispatchers.IO) {
            try {
                while (isActive && !serverSocket.isClosed) {
                    val clientSocket = try {
                        serverSocket.accept()
                    } catch (e: Exception) {
                        null
                    }
                    if (clientSocket != null) {
                        launch { handleSocks5Request(ssh, clientSocket) }
                    }
                }
            } finally {
                try { serverSocket.close() } catch (e: Exception) {}
            }
        }

    private suspend fun handleSocks5Request(ssh: SSHClient, socket: Socket) =
        withContext(Dispatchers.IO) {
            socket.use { s ->
                try {
                    val input = s.getInputStream()
                    val output = s.getOutputStream()
                    val version = input.read()
                    if (version != 5) return@withContext
                    val nMethods = input.read()
                    if (nMethods <= 0) return@withContext
                    val methods = ByteArray(nMethods)
                    input.readFully(methods)

                    output.write(byteArrayOf(5, 0))
                    output.flush()

                    val ver = input.read()
                    if (ver != 5) return@withContext
                    val cmd = input.read()
                    input.read() // Skip rsv byte
                    val aType = input.read()
                    val targetHost = when (aType) {
                        1 -> {
                            val address = ByteArray(4)
                            input.readFully(address)
                            java.net.InetAddress.getByAddress(address).hostAddress
                        }
                        3 -> {
                            val len = input.read()
                            if (len <= 0 || len > 255) return@withContext
                            val address = ByteArray(len)
                            input.readFully(address)
                            String(address)
                        }
                        4 -> {
                            val address = ByteArray(16)
                            input.readFully(address)
                            java.net.InetAddress.getByAddress(address).hostAddress?.split("%")[0]
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

                        // Simple, native stream copying without thread-blocking leaks
                        coroutineScope {
                            val jobIn = launch {
                                try {
                                    pipe(input, channel.outputStream)
                                } catch (e: Exception) {
                                } finally {
                                    try { s.close() } catch (_: Exception) {}
                                    try { channel.close() } catch (_: Exception) {}
                                }
                            }
                            val jobOut = launch {
                                try {
                                    pipe(channel.inputStream, output)
                                } catch (e: Exception) {
                                } finally {
                                    try { s.close() } catch (_: Exception) {}
                                    try { channel.close() } catch (_: Exception) {}
                                }
                            }
                            joinAll(jobIn, jobOut)
                        }
                    }
                } catch (e: Exception) {
                    // Suppress connection-closed trace details
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
        var read: Int
        while (true) {
            read = from.read(buffer)
            if (read == -1) break
            if (read > 0) {
                to.write(buffer, 0, read)
                to.flush()
            }
        }
    }


    private fun updateSummaryNotification() {
        val activeCount = activeTunnels.size
        val statusText = if (activeCount > 0) "Active Gates: $activeCount" else "Durin's Gate Service Active"
        val notification = createNotification(statusText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun showVerificationNotification(config: SshConfig) {
        val intent = Intent(this, HostKeyApprovalActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CONFIG_ID, config.id)
            putExtra("key", HostKeyVerifierManager.getLookupKey(
                config.host, config.port
            ))
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
        manager.notify(config.host.hashCode(), notification)
    }

    private fun createNotification(content: String): Notification {
        val stopIntent = Intent(
            this, SshForegroundService::class.java
        ).apply { action = ACTION_STOP_ALL }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )
        val mainIntent = Intent(this, MainActivity::class.java)
        val pendingMainIntent = PendingIntent.getActivity(
            this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE
        )

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
                NotificationChannel(
                    CHANNEL_ID, "SSH", NotificationManager.IMPORTANCE_HIGH
                )
            getSystemService(
                NotificationManager::class.java
            ).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        networkCallback?.let {
            val manager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            manager.unregisterNetworkCallback(it)
        }
        activeTunnels.values.forEach { it.close() }
        activeTunnels.clear()
        serviceJob.cancel()
        super.onDestroy()
    }
}
