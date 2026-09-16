package com.sih.itantra

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Locale

class EmergencyService : Service(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private val CHANNEL_ID = "iTantraEmergencyChannel"
    private val SERVICE_ID = "com.sih.itantra"

    companion object {
        val connectedEndpoints = mutableSetOf<String>()
        val pendingRequests = mutableSetOf<String>()
        var onConnectionCountChanged: ((Int) -> Unit)? = null
        var onEmergencyDataReceived: ((String, String, String, String, Double, Double, String) -> Unit)? = null
        var onAckReceived: ((String, String, Double, Double) -> Unit)? = null

        // BULLETIN CALLBACK FOR LIVE NDRF NEWS
        var onBulletinDataReceived: ((String, String, String, String) -> Unit)? = null

        fun broadcastEmergencyPayload(context: Context, jsonPayload: String): Boolean {
            if (connectedEndpoints.isEmpty()) {
                return false
            }
            val payload = Payload.fromBytes(jsonPayload.toByteArray(StandardCharsets.UTF_8))
            Nearby.getConnectionsClient(context).sendPayload(connectedEndpoints.toList(), payload)
            return true
        }
    }

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        createNotificationChannel()
        startForeground(101, createForegroundNotification("iTantra Active Guard"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "ACTION_FORCE_RESTART_MESH" || action == "ACTION_START_FAST_SCAN") {
            showToast("⚡ Resetting Mesh Discovery Loop...")
        }
        startNearbyMeshNode()
        return START_STICKY
    }

    private fun startNearbyMeshNode() {
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()

        // Stop current discovery/advertising before restarting for fast re-pairing
        Nearby.getConnectionsClient(this).stopAdvertising()
        Nearby.getConnectionsClient(this).stopDiscovery()

        Nearby.getConnectionsClient(this).startAdvertising(
            Build.MODEL,
            SERVICE_ID,
            connectionLifecycleCallback,
            advertisingOptions
        ).addOnSuccessListener {
            showToast("Mesh Advertising Active")
        }.addOnFailureListener { e ->
            showToast("Advertising Error: ${e.localizedMessage}")
        }

        Nearby.getConnectionsClient(this).startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            discoveryOptions
        ).addOnSuccessListener {
            showToast("Mesh Discovery Searching...")
        }.addOnFailureListener { e ->
            showToast("Discovery Error: ${e.localizedMessage}")
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (!connectedEndpoints.contains(endpointId) && !pendingRequests.contains(endpointId)) {
                pendingRequests.add(endpointId)
                Nearby.getConnectionsClient(applicationContext).requestConnection(
                    Build.MODEL,
                    endpointId,
                    connectionLifecycleCallback
                ).addOnFailureListener {
                    pendingRequests.remove(endpointId)
                }
            }
        }

        override fun onEndpointLost(endpointId: String) {
            pendingRequests.remove(endpointId)
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            Nearby.getConnectionsClient(applicationContext).acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            pendingRequests.remove(endpointId)
            if (result.status.isSuccess) {
                connectedEndpoints.add(endpointId)
                notifyUI()
                showToast("Connected to nearby device!")
            } else {
                connectedEndpoints.remove(endpointId)
                notifyUI()
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
            pendingRequests.remove(endpointId)
            notifyUI()
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val jsonString = String(payload.asBytes()!!, StandardCharsets.UTF_8)
                try {
                    val jsonObj = JSONObject(jsonString)
                    val type = jsonObj.optString("type", "ALERT")

                    if (type == "ACK") {
                        val receiverName = jsonObj.optString("name", "Nearby Receiver")
                        val receiverPhone = jsonObj.optString("phone", "N/A")
                        val lat = jsonObj.optDouble("lat", 0.0)
                        val lng = jsonObj.optDouble("lng", 0.0)

                        Handler(Looper.getMainLooper()).post {
                            onAckReceived?.invoke(receiverName, receiverPhone, lat, lng)
                            showToast("✅ Delivery Confirmed by $receiverName!")
                        }
                    } else if (type == "BULLETIN") {
                        // NDRF BULLETIN PARSER
                        val title = jsonObj.optString("title", "Official Disaster Update")
                        val desc = jsonObj.optString("desc", "")
                        val source = jsonObj.optString("source", "NDRF Master Mesh Node")
                        val priority = jsonObj.optString("priority", "HIGH")

                        Handler(Looper.getMainLooper()).post {
                            onBulletinDataReceived?.invoke(title, desc, source, priority)
                            showToast("📢 Official NDRF Bulletin Received!")
                        }
                    } else {
                        // CITIZEN EMERGENCY ALERT PARSER
                        val originalMsg = jsonObj.optString("msg", "Emergency Alert!")
                        val senderName = jsonObj.optString("name", "Unknown Person")
                        val senderPhone = jsonObj.optString("phone", "N/A")
                        val lat = jsonObj.optDouble("lat", 0.0)
                        val lng = jsonObj.optDouble("lng", 0.0)
                        val sourceLang = jsonObj.optString("lang", "en-IN")

                        val prefs = getSharedPreferences("iTantraPrefs", Context.MODE_PRIVATE)
                        val receiverPreferredLang = prefs.getString("user_lang", "hi-IN") ?: "hi-IN"

                        translateHybridOffline(originalMsg, sourceLang, receiverPreferredLang) { translatedMsg ->

                            Handler(Looper.getMainLooper()).post {
                                onEmergencyDataReceived?.invoke(
                                    originalMsg,
                                    translatedMsg,
                                    senderName,
                                    senderPhone,
                                    lat,
                                    lng,
                                    receiverPreferredLang
                                )
                            }

                            triggerEmergencyAlertNotification(translatedMsg, senderName, senderPhone, lat, lng)
                            speakOutLoudInLanguage("Emergency alert from $senderName. $translatedMsg", receiverPreferredLang)
                            sendAcknowledgmentBack(endpointId)
                        }
                    }

                } catch (e: Exception) {
                    speakOutLoudInLanguage(jsonString, "hi-IN")
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private fun translateHybridOffline(
        text: String,
        sourceLangCode: String,
        targetLangCode: String,
        onComplete: (String) -> Unit
    ) {
        var srcTag = sourceLangCode.take(2).lowercase()
        val tgtTag = targetLangCode.take(2).lowercase()

        if (containsDevanagari(text)) {
            srcTag = "hi"
        } else if (isLatinText(text)) {
            srcTag = "en"
        }

        if (srcTag == tgtTag) {
            onComplete(text)
            return
        }

        val srcMlKit = mapToMlKitLanguage(srcTag)
        val tgtMlKit = mapToMlKitLanguage(tgtTag)

        if (srcMlKit != null && tgtMlKit != null) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(srcMlKit)
                .setTargetLanguage(tgtMlKit)
                .build()

            val translator = Translation.getClient(options)

            translator.translate(text)
                .addOnSuccessListener { translatedText ->
                    translator.close()
                    onComplete(translatedText)
                }
                .addOnFailureListener {
                    translator.close()
                    onComplete(smartFallbackTranslation(text, srcTag, tgtTag))
                }
        } else {
            onComplete(smartFallbackTranslation(text, srcTag, tgtTag))
        }
    }

    private fun containsDevanagari(text: String): Boolean {
        return text.any { it in '\u0900'..'\u097F' }
    }

    private fun isLatinText(text: String): Boolean {
        return text.any { it in 'a'..'z' || it in 'A'..'Z' } && !containsDevanagari(text)
    }

    private fun smartFallbackTranslation(text: String, src: String, tgt: String): String {
        val lower = text.trim().lowercase()

        if (src == "hi" && tgt == "en") {
            return when {
                lower.contains("मदद") || lower.contains("हेल्प") || lower.contains("help") -> "I need your help"
                lower.contains("पानी") || lower.contains("जल") -> "Need water urgently"
                lower.contains("डॉक्टर") || lower.contains("अस्पताल") -> "Send medical help / doctor"
                lower.contains("फंसा") || lower.contains("अटका") -> "I am trapped here"
                lower.contains("कहां") || lower.contains("कहाँ") -> "Where are you?"
                lower.contains("खाना") || lower.contains("भोजन") -> "Need food supplies"
                lower.contains("बचाओ") -> "Save us!"
                else -> text
            }
        }

        if (src == "mr" && tgt == "en") {
            return when {
                lower.contains("मदत") || lower.contains("हेल्प") || lower.contains("help") -> "I need your help"
                lower.contains("पाणी") -> "Need water urgently"
                lower.contains("डॉक्टर") || lower.contains("दवाखाना") -> "Send medical help / doctor"
                lower.contains("अडकलो") || lower.contains("अडकले") -> "I am trapped here"
                lower.contains("कुठे") -> "Where are you?"
                lower.contains("अन्न") || lower.contains("जेवण") -> "Need food supplies"
                lower.contains("वाचवा") -> "Save us!"
                else -> text
            }
        }

        if (src == "en" && tgt == "hi") {
            return when {
                lower.contains("where") -> "आप कहां हैं?"
                lower.contains("help") -> "मुझे आपकी मदद चाहिए"
                lower.contains("water") -> "पानी की सख्त जरूरत है"
                lower.contains("doctor") || lower.contains("medical") -> "डॉक्टर की जरूरत है"
                lower.contains("trapped") || lower.contains("stuck") -> "मैं यहां फंसा हुआ हूं"
                lower.contains("food") -> "खाना चाहिए"
                lower.contains("save") -> "हमें बचाओ"
                else -> text
            }
        }

        if (src == "en" && tgt == "mr") {
            return when {
                lower.contains("where") -> "तुम्ही कुठे आहात?"
                lower.contains("help") -> "मला तुमची मदत हवी आहे"
                lower.contains("water") -> "पाणी हवे आहे"
                lower.contains("doctor") || lower.contains("medical") -> "डॉक्टर पाठवा"
                lower.contains("trapped") || lower.contains("stuck") -> "मी इथे अडकलो आहे"
                lower.contains("food") -> "अन्न हवे आहे"
                lower.contains("save") -> "वाचवा"
                else -> text
            }
        }

        if (src == "mr" && tgt == "hi") {
            return when {
                lower.contains("मदत") || lower.contains("हेल्प") -> "मुझे आपकी मदद चाहिए"
                lower.contains("पाणी") -> "पानी चाहिए"
                lower.contains("कुठे") -> "आप कहां हैं?"
                lower.contains("अडकलो") -> "मैं यहां फंसा हुआ हूं"
                lower.contains("वाचवा") -> "बचाओ"
                else -> text
            }
        }

        if (src == "hi" && tgt == "mr") {
            return when {
                lower.contains("मदद") || lower.contains("हेल्प") -> "मला तुमची मदत हवी आहे"
                lower.contains("पानी") -> "पाणी हवे आहे"
                lower.contains("कहां") || lower.contains("कहाँ") -> "तुम्ही कुठे आहात?"
                lower.contains("फंसा") -> "मी इथे अडकलो आहे"
                lower.contains("बचाओ") -> "वाचवा"
                else -> text
            }
        }

        return text
    }

    private fun mapToMlKitLanguage(langTagTwoLetter: String): String? {
        return when (langTagTwoLetter) {
            "en" -> TranslateLanguage.ENGLISH
            "hi" -> TranslateLanguage.HINDI
            "mr" -> TranslateLanguage.MARATHI
            "gu" -> TranslateLanguage.GUJARATI
            "bn" -> TranslateLanguage.BENGALI
            "ta" -> TranslateLanguage.TAMIL
            "te" -> TranslateLanguage.TELUGU
            "kn" -> TranslateLanguage.KANNADA
            else -> null
        }
    }

    private fun sendAcknowledgmentBack(senderEndpointId: String) {
        val prefs = getSharedPreferences("iTantraPrefs", Context.MODE_PRIVATE)
        val myName = prefs.getString("user_name", "Nearby User") ?: "Nearby User"
        val myPhone = prefs.getString("user_phone", "N/A") ?: "N/A"

        val location = getCurrentGPSLocation()
        val lat = location?.latitude ?: 0.0
        val lng = location?.longitude ?: 0.0

        val ackPayload = JSONObject().apply {
            put("type", "ACK")
            put("name", myName)
            put("phone", myPhone)
            put("lat", lat)
            put("lng", lng)
        }.toString()

        val payload = Payload.fromBytes(ackPayload.toByteArray(StandardCharsets.UTF_8))
        Nearby.getConnectionsClient(applicationContext).sendPayload(senderEndpointId, payload)
    }

    @SuppressLint("MissingPermission")
    private fun getCurrentGPSLocation(): Location? {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        if (location == null) {
            location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }
        return location
    }

    private fun notifyUI() {
        Handler(Looper.getMainLooper()).post {
            onConnectionCountChanged?.invoke(connectedEndpoints.size)
        }
    }

    private fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun triggerEmergencyAlertNotification(
        message: String,
        senderName: String,
        senderPhone: String,
        lat: Double,
        lng: Double
    ) {
        // Wakes screen for 8 seconds
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "iTantra:EmergencyAlertWakeLock"
        )
        wakeLock.acquire(8000)

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("SENDER_NAME", senderName)
            putExtra("SENDER_PHONE", senderPhone)
            putExtra("SENDER_LAT", lat)
            putExtra("SENDER_LNG", lng)
            putExtra("EMERGENCY_MSG", message)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("🚨 EMERGENCY SOS: $senderName")
            .setContentText("\"$message\" ($senderPhone)")
            .setStyle(NotificationCompat.BigTextStyle().bigText("👤 Sender: $senderName ($senderPhone)\n💬 Msg: \"$message\"\n📍 Location: $lat, $lng"))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(pendingIntent, true) // Immediately opens full-screen emergency alert with details
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }

    private fun speakOutLoudInLanguage(text: String, langCode: String) {
        val locale = Locale.forLanguageTag(langCode)
        tts?.language = locale
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onInit(status: Int) {}

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "iTantra Emergency Alerts",
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createForegroundNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("iTantra Active Guard")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}