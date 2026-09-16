package com.sih.itantra

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// --- MOONLIGHT LIGHT THEME PALETTE ---
val ColorMoonlight    = Color(0xFFF0ECDD) // Main Soft Off-White Background
val ColorCardLight    = Color(0xFFFAF8F2) // Light Card Surface
val ColorFrostBlue    = Color(0xFF8BA3C5) // Soft Sky Blue
val ColorSteel        = Color(0xFF495B7D) // Slate Blue
val ColorStorm        = Color(0xFF23354D) // Dark Slate
val ColorOxfordBlue   = Color(0xFF02122F) // Deep Dark Navy
val ColorAlertRed     = Color(0xFFDC2626) // Alert Red
val ColorSuccessGreen = Color(0xFF059669) // Emerald Green

// Theme Mapping
val ThemeScreenBg       = ColorMoonlight
val ThemeHeaderBg       = ColorOxfordBlue
val ThemeCardBg         = ColorCardLight
val ThemeSubContainerBg = Color(0xFFEBE6D6)
val ThemeTextPrimary    = ColorOxfordBlue
val ThemeTextSecondary  = ColorSteel
val ThemeTextMuted      = Color(0xFF64748B)

data class AppLanguage(val name: String, val code: String)

val supportedLanguages = listOf(
    AppLanguage("Hindi (हिंदी)", "hi-IN"),
    AppLanguage("English", "en-IN"),
    AppLanguage("Marathi (मराठी)", "mr-IN"),
    AppLanguage("Gujarati (ગુજરાતી)", "gu-IN"),
    AppLanguage("Bengali (বাংলা)", "bn-IN"),
    AppLanguage("Tamil (தமிழ்)", "ta-IN"),
    AppLanguage("Telugu (తెలుగు)", "te-IN"),
    AppLanguage("Kannada (કನ್ನಡ)", "kn-IN"),
    AppLanguage("Malayalam (മലയാളം)", "ml-IN"),
    AppLanguage("Punjabi (ਪੰਜਾਬੀ)", "pa-IN")
)

data class ReceiverAck(
    val name: String,
    val phone: String,
    val lat: Double,
    val lng: Double,
    val time: String
)

data class IncomingAlert(
    val id: String,
    val originalMsg: String,
    val translatedMsg: String,
    val senderName: String,
    val senderPhone: String,
    val lat: Double,
    val lng: Double,
    val time: String
)

data class DisasterBulletin(
    val id: String,
    val source: String,
    val title: String,
    val description: String,
    val timestamp: String,
    val priority: String, // "HIGH" or "INFO"
    val isMeshMode: Boolean
)

class MainActivity : ComponentActivity() {

    private var speechRecognizer: SpeechRecognizer? = null
    private var prefs: SharedPreferences? = null

    // STATE VARIABLES
    private var userName by mutableStateOf("")
    private var userPhone by mutableStateOf("")
    private var userAge by mutableStateOf("")
    private var userBloodGroup by mutableStateOf("O+")
    private var userMedicalNotes by mutableStateOf("")
    private var iceContactName by mutableStateOf("")
    private var iceContactPhone by mutableStateOf("")
    private var profileImageUriString by mutableStateOf("")
    private var isProfileSaved by mutableStateOf(false)

    private var selectedLanguage by mutableStateOf(supportedLanguages[0])
    private var isLangDropdownExpanded by mutableStateOf(false)

    private var recognizedText by mutableStateOf("")
    private var isListening by mutableStateOf(false)
    private var connectedCount by mutableStateOf(0)

    private var showControlRoomReceipt by mutableStateOf(false)
    private var incidentId by mutableStateOf("")
    private var dispatchTime by mutableStateOf("")

    // List of Saved Received Citizen Emergency Alerts
    private val receivedAlerts = mutableStateListOf<IncomingAlert>()

    // Delivery Receipts for sent messages
    private val acknowledgedReceivers = mutableStateListOf<ReceiverAck>()
    private var selectedTab by mutableStateOf(0) // 0: Home, 1: NDRF Hub, 2: Alerts, 3: Profile

    // Live Dynamic NDRF Bulletin List
    private val officialBulletins = mutableStateListOf(
        DisasterBulletin(
            "1",
            "NDRF Master Mesh Node",
            "Floodwater Rising in Sector 4 - Immediate Evacuation",
            "Relocate immediately to Relief Camp at St. Xavier Ground. Emergency boat teams dispatched via mesh routing.",
            "12 mins ago",
            "HIGH",
            true
        ),
        DisasterBulletin(
            "2",
            "District Relief Control",
            "Drinking Water & Food Distribution Point Active",
            "Clean drinking water tankers and ration kits ready at Sector 2 Community Center.",
            "40 mins ago",
            "INFO",
            false
        ),
        DisasterBulletin(
            "3",
            "IMD Weather Station",
            "Red Warning: High Thunderstorms & Gale Winds",
            "Sustained wind speeds exceeding 60 km/h. Avoid trees, high pillars, and weak roofs.",
            "1 hour ago",
            "HIGH",
            false
        )
    )

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            startEmergencyService("ACTION_START_FAST_SCAN")
        } else {
            Toast.makeText(this, "All permissions required for iTantra operation", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("iTantraPrefs", Context.MODE_PRIVATE)

        userName = prefs?.getString("user_name", "") ?: ""
        userPhone = prefs?.getString("user_phone", "") ?: ""
        userAge = prefs?.getString("user_age", "") ?: ""
        userBloodGroup = prefs?.getString("user_blood_group", "O+") ?: "O+"
        userMedicalNotes = prefs?.getString("user_medical_notes", "") ?: ""
        iceContactName = prefs?.getString("ice_contact_name", "") ?: ""
        iceContactPhone = prefs?.getString("ice_contact_phone", "") ?: ""
        profileImageUriString = prefs?.getString("user_photo_uri", "") ?: ""
        isProfileSaved = userName.isNotEmpty() && userPhone.isNotEmpty()

        val savedLangCode = prefs?.getString("user_lang", "hi-IN") ?: "hi-IN"
        selectedLanguage = supportedLanguages.find { it.code == savedLangCode } ?: supportedLanguages[0]

        // ALLOW SCREEN TO TURN ON AND SHOW OVER LOCK SCREEN FOR EMERGENCY POP-UP
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            setupSpeechListener()
        }

        EmergencyService.onConnectionCountChanged = { count -> connectedCount = count }

        // Multi-Alert Queue Receiver
        EmergencyService.onEmergencyDataReceived = { originalMsg, translatedMsg, name, phone, lat, lng, _ ->
            val currentTime = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
            val alert = IncomingAlert(
                id = System.currentTimeMillis().toString(),
                originalMsg = originalMsg,
                translatedMsg = translatedMsg,
                senderName = name.ifEmpty { "Unknown Citizen" },
                senderPhone = phone.ifEmpty { "N/A" },
                lat = lat,
                lng = lng,
                time = currentTime
            )
            receivedAlerts.add(0, alert)
        }

        // Live NDRF Bulletin Receiver
        EmergencyService.onBulletinDataReceived = { title, desc, source, priority ->
            val currentTime = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
            officialBulletins.add(
                0,
                DisasterBulletin(
                    id = System.currentTimeMillis().toString(),
                    source = source.ifEmpty { "NDRF Master Mesh Node" },
                    title = title,
                    description = desc,
                    timestamp = currentTime,
                    priority = priority,
                    isMeshMode = true
                )
            )
        }

        EmergencyService.onAckReceived = { name, phone, lat, lng ->
            val currentTime = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
            acknowledgedReceivers.add(0, ReceiverAck(name, phone, lat, lng, currentTime))
        }

        handleIncomingIntent(intent)
        checkAndRequestPermissions()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = ThemeScreenBg
                ) {
                    Scaffold(
                        topBar = { TopHeaderBar() },
                        bottomBar = { BottomNavigationBar() },
                        containerColor = ThemeScreenBg
                    ) { innerPadding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        ) {
                            when (selectedTab) {
                                0 -> HomeScreen()
                                1 -> NdrfHubScreen()
                                2 -> AlertsScreen()
                                3 -> ProfileScreen()
                            }
                        }
                    }
                }
            }
        }
    }

    // --- TOP HEADER BAR ---
    @Composable
    private fun TopHeaderBar() {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ThemeHeaderBg)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text("iTantra Mesh", color = ColorMoonlight, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("CONNECT · SAVE LIVES", color = ColorFrostBlue, fontSize = 8.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0F392B))
                        .border(1.dp, ColorSuccessGreen, RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color(0xFF34D399)))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "$connectedCount Connected",
                            color = Color(0xFFD1FAE5),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                Box {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(ColorStorm)
                            .border(1.dp, ColorSteel, RoundedCornerShape(12.dp))
                            .clickable { isLangDropdownExpanded = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Language, contentDescription = null, tint = ColorFrostBlue, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = selectedLanguage.name.split(" ")[0],
                                color = ColorMoonlight,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = ColorMoonlight, modifier = Modifier.size(14.dp))
                        }
                    }

                    DropdownMenu(
                        expanded = isLangDropdownExpanded,
                        onDismissRequest = { isLangDropdownExpanded = false },
                        modifier = Modifier.background(ThemeCardBg)
                    ) {
                        supportedLanguages.forEach { lang ->
                            DropdownMenuItem(
                                text = { Text(lang.name, color = ThemeTextPrimary, fontSize = 12.sp) },
                                onClick = {
                                    selectedLanguage = lang
                                    isLangDropdownExpanded = false
                                    prefs?.edit()?.putString("user_lang", lang.code)?.commit()
                                    Toast.makeText(this@MainActivity, "Language: ${lang.name}", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // --- HOME DASHBOARD ---
    @Composable
    private fun HomeScreen() {
        val latestAlert = receivedAlerts.firstOrNull()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // 🚨 CRITICAL EMERGENCY ALERT BANNER
            if (latestAlert != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.5.dp, ColorAlertRed),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .clickable { selectedTab = 2 }
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = ColorAlertRed, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("🚨 LATEST INCOMING SOS ALERT", color = ColorAlertRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Text("See All (${receivedAlerts.size}) >", color = ColorAlertRed, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text("👤 ${latestAlert.senderName} (${latestAlert.senderPhone}) | ${latestAlert.time}", color = ThemeTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("📍 GPS: ${latestAlert.lat}, ${latestAlert.lng}", color = Color(0xFFB45309), fontSize = 10.sp)

                        Spacer(modifier = Modifier.height(4.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White)
                                .border(1.dp, Color(0xFFFCA5A5), RoundedCornerShape(8.dp))
                                .padding(6.dp)
                        ) {
                            Column {
                                Text("💬 Msg: \"${latestAlert.translatedMsg}\"", color = ColorOxfordBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // 1. QUICK SOS CATEGORY PRESETS
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.FlashOn, contentDescription = null, tint = ColorOxfordBlue, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text("QUICK SOS CATEGORY PRESETS", color = ThemeTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("Tap a category to add it to your message", color = ThemeTextSecondary, fontSize = 9.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PresetCardItem("Medical", "Injury/Health", Icons.Default.MedicalServices, Color(0xFFE8E2F4), ColorOxfordBlue, Modifier.weight(1f)) {
                    appendPresetText("Need urgent medical aid and doctor.")
                }
                PresetCardItem("Water", "Drinking", Icons.Default.WaterDrop, Color(0xFFD8C2E7), ColorOxfordBlue, Modifier.weight(1f)) {
                    appendPresetText("Need clean drinking water supplies.")
                }
                PresetCardItem("Trapped", "Debris", Icons.Default.HomeWork, Color(0xFFB59CCA), ColorOxfordBlue, Modifier.weight(1f)) {
                    appendPresetText("People trapped under debris here!")
                }
                PresetCardItem("Food", "Supplies", Icons.Default.Restaurant, ColorOxfordBlue, ColorMoonlight, Modifier.weight(1f)) {
                    appendPresetText("Need emergency food rations.")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 2. BROADCAST EMERGENCY MESSAGE CARD
            Card(
                colors = CardDefaults.cardColors(containerColor = ThemeCardBg),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFFE2DCCB)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Message, contentDescription = null, tint = ColorOxfordBlue, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("BROADCAST EMERGENCY MESSAGE", color = ThemeTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Text("${recognizedText.length}/500", color = ThemeTextSecondary, fontSize = 10.sp)
                    }
                    Text("Send help requests to nearby devices via mesh network", color = ThemeTextSecondary, fontSize = 9.sp)

                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = recognizedText,
                        onValueChange = { recognizedText = it },
                        placeholder = { Text("Need urgent medical aid and water near sector 4...", color = ThemeTextMuted, fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth().height(75.dp),
                        textStyle = TextStyle(color = ThemeTextPrimary, fontSize = 12.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ColorOxfordBlue,
                            unfocusedBorderColor = Color(0xFFD6CFC0),
                            focusedContainerColor = ThemeSubContainerBg,
                            unfocusedContainerColor = ThemeSubContainerBg
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = { startListening() },
                            colors = ButtonDefaults.buttonColors(containerColor = if (isListening) ColorAlertRed else ColorStorm),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Column {
                                    Text(if (isListening) "LISTENING..." else "HOLD TO SPEAK", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    Text("Voice Broadcast", fontSize = 8.sp, color = ColorFrostBlue)
                                }
                            }
                        }

                        // HIGH-SPEED MESH RESTART BUTTON
                        Button(
                            onClick = {
                                startEmergencyService("ACTION_FORCE_RESTART_MESH")
                                Toast.makeText(this@MainActivity, "⚡ High-Speed Mesh Discovery Started!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC3D2E5)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Refresh, contentDescription = null, tint = ColorOxfordBlue, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Column {
                                    Text("RESTART MESH", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = ColorOxfordBlue)
                                    Text("Fast Discovery", fontSize = 8.sp, color = ColorSteel)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Button(
                        onClick = { broadcastEmergencyWithDetails() },
                        colors = ButtonDefaults.buttonColors(containerColor = ColorOxfordBlue),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Campaign, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text("BLAST SOS TO ALL NEARBY PHONES", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Text("Send emergency alert to all devices in range", color = ColorFrostBlue, fontSize = 8.sp)
                                }
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 3. LIVE DELIVERY RECEIPTS CARD
            Card(
                colors = CardDefaults.cardColors(containerColor = ThemeCardBg),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFFE2DCCB)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = ColorOxfordBlue, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("LIVE DELIVERY RECEIPTS", color = ThemeTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Box(
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFFC3D2E5)).padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("${acknowledgedReceivers.size} Confirmed", color = ColorOxfordBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text("Real-time confirmation from nearby devices", color = ThemeTextSecondary, fontSize = 9.sp)

                    Spacer(modifier = Modifier.height(6.dp))

                    if (acknowledgedReceivers.isEmpty()) {
                        Text("No delivery receipts confirmed yet.", color = ThemeTextSecondary, fontSize = 11.sp, modifier = Modifier.padding(vertical = 2.dp))
                    } else {
                        acknowledgedReceivers.forEach { ack ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(ThemeSubContainerBg)
                                    .padding(8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.AccountCircle, contentDescription = null, tint = ColorOxfordBlue, modifier = Modifier.size(28.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Column {
                                        Text("${ack.name} (${ack.phone})", color = ThemeTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Text("Confirmed at ${ack.time} | GPS: ${ack.lat}, ${ack.lng}", color = ColorSuccessGreen, fontSize = 9.sp)
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Button(
                                        onClick = {
                                            val uri = Uri.parse("tel:${ack.phone}")
                                            startActivity(Intent(Intent.ACTION_DIAL, uri))
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = ColorOxfordBlue),
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier.weight(1f).height(32.dp),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                                    ) {
                                        Icon(Icons.Default.Call, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("CALL", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }

                                    Button(
                                        onClick = {
                                            val uri = Uri.parse("geo:${ack.lat},${ack.lng}?q=${ack.lat},${ack.lng}(${ack.name})")
                                            startActivity(Intent(Intent.ACTION_VIEW, uri))
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = ColorSuccessGreen),
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier.weight(1f).height(32.dp),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                                    ) {
                                        Icon(Icons.Default.Map, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("MAP LOCATION", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }
            }
        }
    }

    // TIGHT & GAP-FREE PRESET CARD COMPOSABLE
    @Composable
    private fun PresetCardItem(
        title: String,
        subtitle: String,
        icon: ImageVector,
        bgColor: Color,
        textColor: Color,
        modifier: Modifier = Modifier,
        onClick: () -> Unit
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = bgColor),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, Color(0xFFD6CFC0)),
            modifier = modifier.clickable { onClick() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp, horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(icon, contentDescription = null, tint = textColor, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = title,
                    color = textColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    lineHeight = 10.sp
                )
                Text(
                    text = subtitle,
                    color = if (textColor == ColorMoonlight) ColorFrostBlue else ColorSteel,
                    fontSize = 7.5.sp,
                    maxLines = 1,
                    lineHeight = 8.sp
                )
            }
        }
    }

    private fun appendPresetText(preset: String) {
        recognizedText = if (recognizedText.isEmpty()) preset else "$recognizedText $preset"
    }

    // --- TAB 1: NDRF HUB ---
    @Composable
    private fun NdrfHubScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("🏛️ NDRF Emergency Response Hub", color = ThemeTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("Official disaster response network, news feeds & mesh relay node", color = ThemeTextSecondary, fontSize = 10.sp)

            Spacer(modifier = Modifier.height(10.dp))

            // 1. NDRF MESH RELAY NODE STATUS
            Card(
                colors = CardDefaults.cardColors(containerColor = ThemeCardBg),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFFE2DCCB)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(ThemeSubContainerBg)
                            .border(1.dp, ColorSteel, RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("NDRF RELAY NODE ACTIVE", color = ThemeTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("P2P Mesh Dispatch: ${if (incidentId.isEmpty()) "#NDRF-8831" else incidentId}", color = ColorOxfordBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text("Status: Routed to NDRF Master Control Node", color = ThemeTextSecondary, fontSize = 9.sp)
                        }

                        Box(
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFFD1FAE5)).padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(ColorSuccessGreen))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("ONLINE", color = Color(0xFF065F46), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 2. DYNAMIC OFFICIAL DISASTER BULLETINS & NEWS SECTION
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Campaign, contentDescription = null, tint = ColorOxfordBlue, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("OFFICIAL DISASTER BULLETINS & NEWS", color = ThemeTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Box(
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFFD1FAE5)).padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("VERIFIED FEED", color = Color(0xFF065F46), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            officialBulletins.forEach { bulletin ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = ThemeCardBg),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFFE2DCCB)),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (bulletin.priority == "HIGH") Icons.Default.Warning else Icons.Default.Info,
                                    contentDescription = null,
                                    tint = if (bulletin.priority == "HIGH") ColorAlertRed else ColorOxfordBlue,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(bulletin.source, color = ThemeTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (bulletin.isMeshMode) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color(0xFFE8E2F4))
                                            .padding(horizontal = 4.dp, vertical = 1.dp)
                                    ) {
                                        Text("OFFLINE MESH", color = ColorOxfordBlue, fontSize = 7.5.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                Text(bulletin.timestamp, color = ThemeTextMuted, fontSize = 9.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(bulletin.title, color = ThemeTextPrimary, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(bulletin.description, color = ThemeTextSecondary, fontSize = 10.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 3. HELPLINES CARD
            Card(
                colors = CardDefaults.cardColors(containerColor = ThemeCardBg),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFFE2DCCB)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Call, contentDescription = null, tint = ColorOxfordBlue, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("NATIONAL EMERGENCY HELPLINES", color = ThemeTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("One-tap emergency call service (Cellular Fallback)", color = ThemeTextSecondary, fontSize = 9.sp)

                    Spacer(modifier = Modifier.height(8.dp))

                    HelplineRow("NDRF Disaster Helpline", "1078")
                    HelplineRow("National Emergency Number", "112")
                    HelplineRow("Medical Ambulance Service", "102")
                    HelplineRow("Fire & Rescue Services", "101")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 4. SURVIVAL PROTOCOL
            Card(
                colors = CardDefaults.cardColors(containerColor = ThemeCardBg),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFFE2DCCB)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = ColorOxfordBlue, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("OFF-GRID MESH SURVIVAL PROTOCOL", color = ThemeTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text("• Keep Bluetooth and GPS turned ON for mesh relaying.", color = ThemeTextSecondary, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("• Broadcast SOS messages only when trapped or in immediate danger.", color = ThemeTextSecondary, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("• Translation automatically detects English, Hindi, and regional scripts offline.", color = ThemeTextSecondary, fontSize = 11.sp)
                }
            }
        }
    }

    @Composable
    private fun HelplineRow(title: String, number: String) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(ThemeSubContainerBg)
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(title, color = ThemeTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("Dial: $number", color = ColorOxfordBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = {
                    val uri = Uri.parse("tel:$number")
                    startActivity(Intent(Intent.ACTION_DIAL, uri))
                },
                colors = ButtonDefaults.buttonColors(containerColor = ColorOxfordBlue),
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Default.Call, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("CALL", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    // --- TAB 2: ALERTS TAB ---
    @Composable
    private fun AlertsScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = ColorOxfordBlue, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("💬 Saved Emergency Feed", color = ThemeTextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (receivedAlerts.isNotEmpty()) ColorAlertRed else ColorSteel)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text("${receivedAlerts.size} Alerts Saved", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }

            Text("Historical log of incoming SOS calls received from nearby devices", color = ThemeTextSecondary, fontSize = 10.sp)

            Spacer(modifier = Modifier.height(10.dp))

            if (receivedAlerts.isEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = ThemeCardBg),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFFE2DCCB)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(modifier = Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        Text("No incoming emergency alerts received yet.", color = ThemeTextSecondary, fontSize = 11.sp)
                    }
                }
            } else {
                receivedAlerts.forEachIndexed { index, alert ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, ColorAlertRed),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Warning, contentDescription = null, tint = ColorAlertRed, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("🚨 EMERGENCY SOS #${receivedAlerts.size - index}", color = ColorAlertRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                Text(alert.time, color = ThemeTextMuted, fontSize = 9.sp)
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Text("👤 Sender: ${alert.senderName} (${alert.senderPhone})", color = ThemeTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("📍 GPS Location: ${alert.lat}, ${alert.lng}", color = Color(0xFFB45309), fontSize = 10.sp, fontWeight = FontWeight.Medium)

                            Spacer(modifier = Modifier.height(4.dp))

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White)
                                    .border(1.dp, Color(0xFFFCA5A5), RoundedCornerShape(8.dp))
                                    .padding(8.dp)
                            ) {
                                Column {
                                    Text("💬 Original: \"${alert.originalMsg}\"", color = ThemeTextSecondary, fontSize = 10.5.sp)
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text("🌐 Translated: \"${alert.translatedMsg}\"", color = ColorOxfordBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        val uri = Uri.parse("tel:${alert.senderPhone}")
                                        startActivity(Intent(Intent.ACTION_DIAL, uri))
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = ColorOxfordBlue),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.weight(1f).height(34.dp)
                                ) {
                                    Icon(Icons.Default.Call, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("CALL SENDER", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = {
                                        val uri = Uri.parse("geo:${alert.lat},${alert.lng}?q=${alert.lat},${alert.lng}(Emergency Location)")
                                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = ColorSuccessGreen),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.weight(1f).height(34.dp)
                                ) {
                                    Icon(Icons.Default.Map, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("MAP LOCATION", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // --- PROFILE TAB WITH PERMANENT AVATAR & TRIAGE CARD ---
    @Composable
    private fun ProfileScreen() {
        val context = LocalContext.current
        var isBloodDropdownExpanded by remember { mutableStateOf(false) }
        val bloodGroups = listOf("A+", "A-", "B+", "B-", "O+", "O-", "AB+", "AB-")

        val photoPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            uri?.let {
                val localPath = saveProfileImageToInternalStorage(context, it)
                if (localPath != null) {
                    profileImageUriString = localPath
                    prefs?.edit()?.putString("user_photo_uri", localPath)?.apply()
                    Toast.makeText(context, "Profile Photo Permanently Saved!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("👤 Citizen Profile & Triage Card", color = ThemeTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("Stored locally on device for offline emergency broadcasts", color = ThemeTextSecondary, fontSize = 9.5.sp)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isProfileSaved) Color(0xFFD1FAE5) else Color(0xFFFEF2F2))
                        .border(1.dp, if (isProfileSaved) ColorSuccessGreen else ColorAlertRed, RoundedCornerShape(8.dp))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (isProfileSaved) "READY FOR SOS" else "INCOMPLETE",
                        color = if (isProfileSaved) Color(0xFF065F46) else ColorAlertRed,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // PROFILE AVATAR PICKER
            Box(
                contentAlignment = Alignment.BottomEnd,
                modifier = Modifier
                    .size(85.dp)
                    .clickable { photoPickerLauncher.launch("image/*") }
            ) {
                val bitmap = remember(profileImageUriString) {
                    loadProfileBitmap(context, profileImageUriString)
                }

                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Profile Photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .border(2.dp, ColorOxfordBlue, CircleShape)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(ThemeSubContainerBg)
                            .border(2.dp, ColorSteel, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = ColorSteel,
                            modifier = Modifier.size(45.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(ColorOxfordBlue)
                        .border(1.5.dp, ColorMoonlight, CircleShape)
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AddAPhoto,
                        contentDescription = "Change Photo",
                        tint = Color.White,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }

            Text(
                text = "Tap circle to add photo",
                color = ThemeTextSecondary,
                fontSize = 9.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
            )

            // 1. PERSONAL DETAILS CARD
            Card(
                colors = CardDefaults.cardColors(containerColor = ThemeCardBg),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFFE2DCCB)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Person, contentDescription = null, tint = ColorOxfordBlue, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("PERSONAL CONTACT DETAILS", color = ColorOxfordBlue, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = userName,
                            onValueChange = { userName = it },
                            label = { Text("Full Name", color = ThemeTextSecondary, fontSize = 10.sp) },
                            modifier = Modifier.weight(1.5f),
                            textStyle = TextStyle(color = ThemeTextPrimary, fontSize = 11.5.sp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ColorOxfordBlue,
                                unfocusedBorderColor = Color(0xFFD6CFC0),
                                focusedContainerColor = ThemeSubContainerBg,
                                unfocusedContainerColor = ThemeSubContainerBg
                            ),
                            shape = RoundedCornerShape(8.dp)
                        )

                        OutlinedTextField(
                            value = userAge,
                            onValueChange = { userAge = it },
                            label = { Text("Age", color = ThemeTextSecondary, fontSize = 10.sp) },
                            modifier = Modifier.weight(0.7f),
                            textStyle = TextStyle(color = ThemeTextPrimary, fontSize = 11.5.sp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ColorOxfordBlue,
                                unfocusedBorderColor = Color(0xFFD6CFC0),
                                focusedContainerColor = ThemeSubContainerBg,
                                unfocusedContainerColor = ThemeSubContainerBg
                            ),
                            shape = RoundedCornerShape(8.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = userPhone,
                        onValueChange = { userPhone = it },
                        label = { Text("Phone Number", color = ThemeTextSecondary, fontSize = 10.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(color = ThemeTextPrimary, fontSize = 11.5.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ColorOxfordBlue,
                            unfocusedBorderColor = Color(0xFFD6CFC0),
                            focusedContainerColor = ThemeSubContainerBg,
                            unfocusedContainerColor = ThemeSubContainerBg
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 2. IN CASE OF EMERGENCY (ICE) FAMILY CONTACT
            Card(
                colors = CardDefaults.cardColors(containerColor = ThemeCardBg),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFFE2DCCB)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ContactPhone, contentDescription = null, tint = ColorOxfordBlue, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("IN CASE OF EMERGENCY (ICE) FAMILY CONTACT", color = ColorOxfordBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("Relative/Kin phone number attached to emergency packets", color = ThemeTextSecondary, fontSize = 8.5.sp)

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = iceContactName,
                        onValueChange = { iceContactName = it },
                        label = { Text("Guardian / Kin Name", color = ThemeTextSecondary, fontSize = 10.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(color = ThemeTextPrimary, fontSize = 11.5.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ColorOxfordBlue,
                            unfocusedBorderColor = Color(0xFFD6CFC0),
                            focusedContainerColor = ThemeSubContainerBg,
                            unfocusedContainerColor = ThemeSubContainerBg
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = iceContactPhone,
                        onValueChange = { iceContactPhone = it },
                        label = { Text("Emergency Kin Phone Number", color = ThemeTextSecondary, fontSize = 10.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(color = ThemeTextPrimary, fontSize = 11.5.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ColorOxfordBlue,
                            unfocusedBorderColor = Color(0xFFD6CFC0),
                            focusedContainerColor = ThemeSubContainerBg,
                            unfocusedContainerColor = ThemeSubContainerBg
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 3. MEDICAL TRIAGE PROFILE
            Card(
                colors = CardDefaults.cardColors(containerColor = ThemeCardBg),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFFE2DCCB)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.MedicalServices, contentDescription = null, tint = ColorAlertRed, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("MEDICAL TRIAGE & SURVIVAL VITAL TAG", color = ColorAlertRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("Auto-broadcasted to NDRF first responders during SOS", color = ThemeTextSecondary, fontSize = 8.5.sp)

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedTextField(
                                value = userBloodGroup,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Blood Group", color = ThemeTextSecondary, fontSize = 10.sp) },
                                trailingIcon = {
                                    Icon(
                                        Icons.Default.ArrowDropDown,
                                        contentDescription = null,
                                        modifier = Modifier.clickable { isBloodDropdownExpanded = true }
                                    )
                                },
                                modifier = Modifier.fillMaxWidth().clickable { isBloodDropdownExpanded = true },
                                textStyle = TextStyle(color = ColorAlertRed, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = ColorAlertRed,
                                    unfocusedBorderColor = Color(0xFFD6CFC0),
                                    focusedContainerColor = ThemeSubContainerBg,
                                    unfocusedContainerColor = ThemeSubContainerBg
                                ),
                                shape = RoundedCornerShape(8.dp)
                            )

                            DropdownMenu(
                                expanded = isBloodDropdownExpanded,
                                onDismissRequest = { isBloodDropdownExpanded = false },
                                modifier = Modifier.background(ThemeCardBg)
                            ) {
                                bloodGroups.forEach { bg ->
                                    DropdownMenuItem(
                                        text = { Text("Blood Group: $bg", color = ThemeTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                        onClick = {
                                            userBloodGroup = bg
                                            isBloodDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = userMedicalNotes,
                        onValueChange = { userMedicalNotes = it },
                        placeholder = { Text("e.g. Diabetic, Asthmatic, High Blood Pressure, Wheelchair dependent...", color = ThemeTextMuted, fontSize = 10.5.sp) },
                        label = { Text("Medical Conditions / Allergies / Needs", color = ThemeTextSecondary, fontSize = 10.sp) },
                        modifier = Modifier.fillMaxWidth().height(65.dp),
                        textStyle = TextStyle(color = ThemeTextPrimary, fontSize = 11.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ColorOxfordBlue,
                            unfocusedBorderColor = Color(0xFFD6CFC0),
                            focusedContainerColor = ThemeSubContainerBg,
                            unfocusedContainerColor = ThemeSubContainerBg
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // SAVE PROFILE BUTTON
            Button(
                onClick = {
                    prefs?.edit()?.apply {
                        putString("user_name", userName)
                        putString("user_phone", userPhone)
                        putString("user_age", userAge)
                        putString("ice_contact_name", iceContactName)
                        putString("ice_contact_phone", iceContactPhone)
                        putString("user_blood_group", userBloodGroup)
                        putString("user_medical_notes", userMedicalNotes)
                        putString("user_photo_uri", profileImageUriString)
                        apply()
                    }
                    isProfileSaved = userName.isNotEmpty() && userPhone.isNotEmpty()
                    Toast.makeText(this@MainActivity, "✅ Emergency Profile Saved to Offline Storage!", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(containerColor = ColorOxfordBlue),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().height(42.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Save, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("SAVE EMERGENCY TRIAGE PROFILE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 4. MESH NODE & AI DIAGNOSTICS CARD
            Card(
                colors = CardDefaults.cardColors(containerColor = ThemeCardBg),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFFE2DCCB)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Router, contentDescription = null, tint = ColorOxfordBlue, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("SYSTEM & OFFLINE MESH DIAGNOSTICS", color = ThemeTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(ThemeSubContainerBg)
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("P2P Mesh Device Identifier", color = ThemeTextSecondary, fontSize = 9.sp)
                            Text("Node ID: #iTANTRA-${(Build.MODEL.hashCode() % 8999 + 1000)}", color = ColorOxfordBlue, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                        }
                        Box(
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFFD1FAE5)).padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("ACTIVE RELAY", color = Color(0xFF065F46), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = { downloadOfflineTranslationModels() },
                        colors = ButtonDefaults.buttonColors(containerColor = ColorSuccessGreen),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().height(38.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Download, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("DOWNLOAD OFFLINE AI MODELS (ONE-TIME)", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // --- BOTTOM NAVIGATION BAR ---
    @Composable
    private fun BottomNavigationBar() {
        NavigationBar(
            containerColor = Color(0xFFEBE6D6),
            contentColor = ThemeTextPrimary
        ) {
            NavigationBarItem(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                label = { Text("Home", fontSize = 9.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = ColorOxfordBlue,
                    selectedTextColor = ColorOxfordBlue,
                    indicatorColor = Color(0xFFC3D2E5),
                    unselectedIconColor = ColorSteel,
                    unselectedTextColor = ColorSteel
                )
            )
            NavigationBarItem(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                icon = { Icon(Icons.Default.AccountBalance, contentDescription = "NDRF Hub") },
                label = { Text("NDRF Hub", fontSize = 9.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = ColorOxfordBlue,
                    selectedTextColor = ColorOxfordBlue,
                    indicatorColor = Color(0xFFC3D2E5),
                    unselectedIconColor = ColorSteel,
                    unselectedTextColor = ColorSteel
                )
            )
            NavigationBarItem(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                icon = {
                    BadgedBox(badge = { if (receivedAlerts.isNotEmpty()) Badge { Text("${receivedAlerts.size}") } }) {
                        Icon(Icons.Default.Notifications, contentDescription = "Alerts")
                    }
                },
                label = { Text("Alerts", fontSize = 9.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = ColorOxfordBlue,
                    selectedTextColor = ColorOxfordBlue,
                    indicatorColor = Color(0xFFC3D2E5),
                    unselectedIconColor = ColorSteel,
                    unselectedTextColor = ColorSteel
                )
            )
            NavigationBarItem(
                selected = selectedTab == 3,
                onClick = { selectedTab = 3 },
                icon = { Icon(Icons.Default.Person, contentDescription = "Profile") },
                label = { Text("Profile", fontSize = 9.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = ColorOxfordBlue,
                    selectedTextColor = ColorOxfordBlue,
                    indicatorColor = Color(0xFFC3D2E5),
                    unselectedIconColor = ColorSteel,
                    unselectedTextColor = ColorSteel
                )
            )
        }
    }

    // --- LOGIC HELPER METHODS ---
    private fun saveProfileImageToInternalStorage(context: Context, uri: Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val file = File(context.filesDir, "profile_photo.jpg")
            val outputStream = FileOutputStream(file)
            inputStream.copyTo(outputStream)
            inputStream.close()
            outputStream.close()
            file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun loadProfileBitmap(context: Context, pathOrUri: String): ImageBitmap? {
        if (pathOrUri.isEmpty()) return null
        return try {
            if (pathOrUri.startsWith("/")) {
                val file = File(pathOrUri)
                if (file.exists()) {
                    BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                } else null
            } else {
                val uri = Uri.parse(pathOrUri)
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(source)
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                }
                bitmap.asImageBitmap()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun downloadOfflineTranslationModels() {
        Toast.makeText(this, "⏳ Downloading English ↔ Hindi AI Models for Offline Use...", Toast.LENGTH_SHORT).show()
        val conditions = DownloadConditions.Builder().build()

        val enToHiOptions = TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.ENGLISH).setTargetLanguage(TranslateLanguage.HINDI).build()
        val enToHiTranslator = Translation.getClient(enToHiOptions)

        val hiToEnOptions = TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.HINDI).setTargetLanguage(TranslateLanguage.ENGLISH).build()
        val hiToEnTranslator = Translation.getClient(hiToEnOptions)

        enToHiTranslator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                hiToEnTranslator.downloadModelIfNeeded(conditions)
                    .addOnSuccessListener {
                        Toast.makeText(this@MainActivity, "✅ Bidirectional English ↔ Hindi AI Ready Offline!", Toast.LENGTH_LONG).show()
                        enToHiTranslator.close()
                        hiToEnTranslator.close()
                    }
            }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val msg = intent?.getStringExtra("EMERGENCY_MSG")
        val name = intent?.getStringExtra("SENDER_NAME")
        val phone = intent?.getStringExtra("SENDER_PHONE")
        val lat = intent?.getDoubleExtra("SENDER_LAT", 0.0) ?: 0.0
        val lng = intent?.getDoubleExtra("SENDER_LNG", 0.0) ?: 0.0

        if (!msg.isNullOrEmpty()) {
            val currentTime = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
            val alert = IncomingAlert(
                id = System.currentTimeMillis().toString(),
                originalMsg = msg,
                translatedMsg = msg,
                senderName = name ?: "Unknown Citizen",
                senderPhone = phone ?: "N/A",
                lat = lat,
                lng = lng,
                time = currentTime
            )
            receivedAlerts.add(0, alert)
            selectedTab = 0 // Automatically display the incoming emergency alert on Home tab
        }
    }

    private fun startEmergencyService(action: String = "ACTION_FORCE_RESTART_MESH") {
        val serviceIntent = Intent(this, EmergencyService::class.java).apply {
            this.action = action
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun broadcastEmergencyWithDetails() {
        if (!isProfileSaved) {
            Toast.makeText(this, "Please save your profile in the Profile tab first!", Toast.LENGTH_SHORT).show()
            return
        }

        if (recognizedText.trim().isEmpty()) {
            Toast.makeText(this, "Please speak or type a message first!", Toast.LENGTH_SHORT).show()
            return
        }

        acknowledgedReceivers.clear()

        val location = getCurrentGPSLocation()
        val lat = location?.latitude ?: 0.0
        val lng = location?.longitude ?: 0.0

        val jsonPayload = JSONObject().apply {
            put("type", "ALERT")
            put("msg", recognizedText.trim())
            put("name", userName)
            put("phone", userPhone)
            put("lat", lat)
            put("lng", lng)
            put("lang", selectedLanguage.code)
        }.toString()

        val success = EmergencyService.broadcastEmergencyPayload(this, jsonPayload)
        if (success) {
            incidentId = "#NDRF-" + (1000..9999).random()
            dispatchTime = SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date())
            showControlRoomReceipt = true
            Toast.makeText(this, "🚨 Blasted Offline Mesh Packet!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "⚠️ 0 devices connected. Check Bluetooth/Location permissions.", Toast.LENGTH_LONG).show()
        }
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

    private fun checkAndRequestPermissions() {
        val permissionsList = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsList.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            permissionsList.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsList.add(Manifest.permission.BLUETOOTH_SCAN)
            permissionsList.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissionsList.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }

        requestPermissionsLauncher.launch(permissionsList.toTypedArray())
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, selectedLanguage.code)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        isListening = true
        speechRecognizer?.startListening(intent)
    }

    private fun setupSpeechListener() {
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { isListening = false }

            override fun onError(error: Int) {
                isListening = false
                Toast.makeText(this@MainActivity, "Speech recognition error ($error). You can type message manually!", Toast.LENGTH_LONG).show()
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    recognizedText = matches[0]
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        super.onDestroy()
    }
}