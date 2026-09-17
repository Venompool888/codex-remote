package app.codexremote.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONObject
import java.security.MessageDigest

object TaskNotifications {
    private fun notificationDigest(server: String, request: String): String =
        MessageDigest.getInstance("SHA-256").digest("$server\u0000$request".toByteArray()).joinToString("") { "%02x".format(it) }

    const val STATUS_CHANNEL = "task-status"
    const val CONNECTION_CHANNEL = "remote-connection"
    fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(STATUS_CHANNEL, "Task results and requests", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(CONNECTION_CHANNEL, "Remote connection", NotificationManager.IMPORTANCE_LOW))
    }
    @Synchronized fun event(context: Context, server: String, message: JSONObject) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val prefs = context.getSharedPreferences("notified_events", Context.MODE_PRIVATE)
        val hostKey = notificationDigest(server, "host")
        if (message.optString("type") == "host_session_changed") {
            // The host's previous pending request IDs are no longer actionable.
            prefs.all.filter { (key, value) -> key.startsWith("activeHost:") && value == hostKey }.forEach { (key, _) ->
                val target = key.removePrefix("activeHost:")
                val request = prefs.getString("active:$target", null)
                manager.cancel(target, 0)
                prefs.edit().apply {
                    remove(key)
                    remove("active:$target")
                    request?.let {
                        remove("request:" + notificationDigest(server, it))
                        putLong("resolved:" + notificationDigest(server, it), System.currentTimeMillis())
                    }
                }.apply()
            }
            return
        }
        if (message.optString("type") == "server_response_ack") {
            if (message.optString("status") !in setOf("answered", "expired")) return
            val request = message.optString("requestId").takeIf(String::isNotBlank) ?: return
            val requestKey = "request:" + notificationDigest(server, request)
            // Two host connections can deliver the acknowledgement before a replayed request.
            prefs.edit().putLong("resolved:" + notificationDigest(server, request), System.currentTimeMillis()).apply()
            val target = prefs.getString(requestKey, null) ?: return
            if (prefs.getString("active:$target", null) == request) {
                manager.cancel(target, 0)
                prefs.edit().remove("active:$target").remove("activeHost:$target").apply()
            }
            prefs.edit().remove(requestKey).apply()
            return
        }
        if (message.optString("type") == "codex_request" &&
            prefs.contains("resolved:" + notificationDigest(server, message.optString("requestId")))) return
        val params = message.optJSONObject("params") ?: return
        val turn = params.optJSONObject("turn")
        val thread = params.optString("threadId").ifBlank { params.optJSONObject("thread")?.optString("id").orEmpty() }
        if (thread.isBlank()) return
        val label = TaskStatus.label(message) ?: return
        if (!manager.areNotificationsEnabled()) return
        val eventId = message.optString("requestId").ifBlank { turn?.optString("id") ?: params.optString("turnId") }
        val key = MessageDigest.getInstance("SHA-256").digest("$server\u0000$thread\u0000$eventId\u0000$label".toByteArray()).joinToString("") { "%02x".format(it) }
        if (prefs.contains(key)) return
        val target = MessageDigest.getInstance("SHA-256").digest("$server\u0000$thread".toByteArray()).joinToString("") { "%02x".format(it) }
        val intent = Intent(context, MainActivity::class.java).setData(android.net.Uri.parse("remote://task/$target"))
            .putExtra("remote_server", server).putExtra("remote_thread", thread)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, (server + thread).hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, STATUS_CHANNEL)
            .setSmallIcon(R.drawable.ic_computer).setContentTitle(label).setContentText("Open the task in Remote")
            .setContentIntent(pending).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build()
        // One notification per host/task. A completed task replaces its waiting state.
        manager.notify(target, 0, notification)
        val previousRequest = prefs.getString("active:$target", null)
        val request = message.optString("requestId").takeIf { message.optString("type") == "codex_request" && it.isNotBlank() }
        prefs.edit().apply {
            putLong(key, System.currentTimeMillis())
            previousRequest?.let { remove("request:" + notificationDigest(server, it)) }
            if (request == null) {
                remove("active:$target")
                remove("activeHost:$target")
            } else {
                putString("activeHost:$target", hostKey)
                putString("active:$target", request)
                putString("request:" + notificationDigest(server, request), target)
            }
        }.apply()
        if (prefs.all.size > 1000) {
            val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60_000L
            prefs.edit().apply { prefs.all.filterValues { it is Long && it < cutoff }.keys.forEach(::remove) }.apply()
        }
    }
}

/** User-visible remote messaging connection keeps task handoff working while the app is backgrounded. */
class RemoteMonitorService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val connections = MonitorConnections<RemoteClient>()
    private val configurationChanged = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        main.post { if (!closed) reconcileConnections() }
    }
    private val attempts = mutableMapOf<String, Int>()
    private var closed = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        TaskNotifications.channels(this)
        getSharedPreferences("remote_projects", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(configurationChanged)
        getSharedPreferences("remote_credentials", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(configurationChanged)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, RemoteMonitorService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        startForeground(4812, Notification.Builder(this, TaskNotifications.CONNECTION_CHANNEL).setSmallIcon(R.drawable.ic_computer)
            .setContentTitle("Remote task notifications are on").setContentText("Maintaining your host connections")
            .setContentIntent(open).addAction(Notification.Action.Builder(null, "Stop", stop).build()).setOngoing(true).build())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            getSharedPreferences("remote_settings", MODE_PRIVATE).edit().putBoolean("background_monitor", false).apply()
            stopSelf(); return START_NOT_STICKY
        }
        reconcileConnections()
        return START_STICKY
    }

    private fun reconcileConnections() {
        if (closed) return
        val tokens = SecureTokenStore(this)
        val desired = RemoteProjectStore(this).connections().map { it.serverUrl }.distinct()
            .mapNotNull { server -> tokens.load(server)?.let { server to it } }.toMap()
        attempts.keys.retainAll(desired.keys)
        val added = connections.reconcile(desired, create = { server ->
            lateinit var client: RemoteClient
            client = RemoteClient(object : RemoteClient.Listener {
                override fun onConnected() { main.post {
                    if (!closed && connections.isCurrent(server, client)) attempts.remove(server)
                } }
                override fun onDisconnected(reason: String) {
                    main.post {
                        if (closed || !connections.isCurrent(server, client)) return@post
                        val attempt = ((attempts[server] ?: 0) + 1).coerceAtMost(6)
                        attempts[server] = attempt
                        main.postDelayed({
                            if (!closed && connections.isCurrent(server, client)) {
                                tokens.load(server)?.let { client.connect(server, it) }
                            }
                        }, minOf(30_000L, 1000L shl attempt))
                    }
                }
                override fun onMessage(message: JSONObject) { main.post {
                    if (!closed && connections.isCurrent(server, client))
                        TaskNotifications.event(this@RemoteMonitorService, server, message)
                } }
            })
            client
        }, close = RemoteClient::close)
        added.forEach { (server, client) ->
            attempts.remove(server)
            client.connect(server, desired.getValue(server))
        }
    }
    override fun onDestroy() {
        closed = true
        main.removeCallbacksAndMessages(null)
        getSharedPreferences("remote_projects", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(configurationChanged)
        getSharedPreferences("remote_credentials", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(configurationChanged)
        connections.closeAll(RemoteClient::close)
        super.onDestroy()
    }
}
