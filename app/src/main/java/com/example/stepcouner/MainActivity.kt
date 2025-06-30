package com.example.stepcouner

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.databinding.DataBindingUtil
import com.example.stepcouner.databinding.ActivityMainBinding
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val stepUpdateHandler = Handler(Looper.getMainLooper())

    // Track current activity state
    private var currentActivity = "Unknown"
    private var isWalking = false
    private var currentSteps = 0

    companion object {
        private const val PERMISSIONS_REQUEST_CODE = 2001
    }

    // Modified updateTask - now just updates UI with current state
    private val updateTask = object : Runnable {
        override fun run() {
            updateUI()
            stepUpdateHandler.postDelayed(this, 2000) // Reduced frequency to 2 seconds
        }
    }

    // New method to handle UI updates consistently
    @SuppressLint("SetTextI18n")
    private fun updateUI() {
        val statusText = if (isWalking) {
            "Steps: $currentSteps\nActivity: $currentActivity"
        } else {
            "Steps: $currentSteps\nActivity: $currentActivity (Not counting)"
        }
        binding.stepText.text = statusText
        Log.d("MainActivity", "UI Updated - Steps: $currentSteps, Activity: $currentActivity, Walking: $isWalking")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)

        // Initialize current state from preferences
        currentSteps = StepPrefs.getTodaySteps(this)

        debugPermissionStatus()
        checkDeviceCompatibility()

        if (checkAndRequestAllPermissions()) {
            initializeStepTracking()
        }

        binding.stopTracking.setOnClickListener {
            stopService(Intent(this, StepCounterService::class.java))
            Toast.makeText(this, "Service stopped", Toast.LENGTH_SHORT).show()
        }

        binding.root.setOnLongClickListener {
            showActivityRecognitionStatus()
            true
        }
    }

    private fun debugPermissionStatus() {
        Log.d("PermissionDebug", "=== Permission Status Debug ===")

        val bodySensorsStatus =
            ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS)
        Log.d(
            "PermissionDebug",
            "BODY_SENSORS: ${if (bodySensorsStatus == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"}"
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val activityStatus =
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
            Log.d(
                "PermissionDebug",
                "ACTIVITY_RECOGNITION: ${if (activityStatus == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"}"
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notificationStatus =
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            Log.d(
                "PermissionDebug",
                "POST_NOTIFICATIONS: ${if (notificationStatus == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"}"
            )
        }

        try {
            val packageInfo =
                packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            val declaredPermissions = packageInfo.requestedPermissions?.toList() ?: emptyList()

            Log.d("PermissionDebug", "Declared permissions:")
            declaredPermissions.forEach { permission ->
                if (permission.contains("BODY_SENSORS") || permission.contains("ACTIVITY_RECOGNITION") || permission.contains(
                        "POST_NOTIFICATIONS"
                    )
                ) {
                    Log.d("PermissionDebug", "  - $permission")
                }
            }
        } catch (e: Exception) {
            Log.e("PermissionDebug", "Failed to check declared permissions", e)
        }
    }

    private fun checkDeviceCompatibility() {
        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        Log.d("DeviceInfo", "Device: ${Build.MANUFACTURER} ${Build.MODEL}")

        // Check for step sensors specifically
        val stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

        Log.d("DeviceInfo", "Step Counter Sensor: ${stepCounter?.name ?: "Not Available"}")
        Log.d("DeviceInfo", "Step Detector Sensor: ${stepDetector?.name ?: "Not Available"}")

        val allSensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
        Log.d("DeviceInfo", "Total sensors available: ${allSensors.size}")

        if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
            Log.d("DeviceInfo", "Samsung device detected - checking for Samsung Health integration")
            allSensors.forEach { sensor ->
                if (sensor.name.contains(
                        "step",
                        ignoreCase = true
                    ) || sensor.vendor.contains("samsung", ignoreCase = true)
                ) {
                    Log.d("DeviceInfo", "Samsung sensor: ${sensor.name} by ${sensor.vendor}")
                }
            }
        }
    }

    private fun checkAndRequestAllPermissions(): Boolean {
        val permissionsNeeded = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsNeeded.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BODY_SENSORS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsNeeded.add(Manifest.permission.BODY_SENSORS)
        }

        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsNeeded.toTypedArray(),
                PERMISSIONS_REQUEST_CODE
            )
            return false
        }

        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            val deniedPermissions = mutableListOf<String>()

            for (i in permissions.indices) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    deniedPermissions.add(permissions[i])
                }
            }

            if (deniedPermissions.isEmpty()) {
                Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show()
                initializeStepTracking()
            } else {
                Toast.makeText(this, "Some permissions denied. App may not work properly.", Toast.LENGTH_LONG).show()
                // Still try to initialize - some features may work
                initializeStepTracking()
            }
        }
    }

    private fun initializeStepTracking() {
        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // Check if at least one step sensor is available
        if (stepCounter == null && stepDetector == null) {
            Toast.makeText(
                this,
                "No step sensors available on this device",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (stepCounter != null) {
            Log.d("MainActivity", "Using Step Counter sensor: ${stepCounter.name}")
        } else if (stepDetector != null) {
            Log.d("MainActivity", "Using Step Detector sensor: ${stepDetector.name}")
        }

        if (accelSensor == null) {
            Log.w("MainActivity", "Accelerometer not available - activity detection may not work")
        }

        // Start the step counter service
        ContextCompat.startForegroundService(this, Intent(this, StepCounterService::class.java))
        scheduleMidnightReset()

        // Initial UI update and start periodic updates
        updateUI()
        updateTask.run()

        // Load and display history if available
        try {
            val history = StepPrefs.getHistory(this)
            binding.historyText.text = if (history.isNotEmpty()) {
                history.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            } else {
                "No history available"
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error loading history", e)
            binding.historyText.text = "Error loading history"
        }

        if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
            showSamsungTips()
        }
    }

    private fun showSamsungTips() {
        Toast.makeText(
            this,
            "Samsung device detected. For best results, keep the app running in background and avoid task killing.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun showActivityRecognitionStatus() {
        val hasActivityPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        val hasBodySensorsPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BODY_SENSORS
        ) == PackageManager.PERMISSION_GRANTED

        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

        val status = """
            Device: ${Build.MANUFACTURER} ${Build.MODEL}
            Activity Permission: ${if (hasActivityPermission) "✓ Granted" else "✗ Denied"}
            Body Sensors Permission: ${if (hasBodySensorsPermission) "✓ Granted" else "✗ Denied"}
            
            Step Counter Sensor: ${if (stepCounter != null) "✓ Available" else "✗ Not Available"}
            Step Detector Sensor: ${if (stepDetector != null) "✓ Available" else "✗ Not Available"}
            
            Steps today: ${StepPrefs.getTodaySteps(this)}
            Current Activity: $currentActivity
            Walking Status: ${if (isWalking) "Active" else "Inactive"}
        """.trimIndent()

        AlertDialog.Builder(this).setTitle("Step Counter Status").setMessage(status)
            .setPositiveButton("OK", null).show()
    }

    override fun onResume() {
        super.onResume()
        // Register receivers for both step updates and activity updates
        val stepFilter = IntentFilter("com.example.stepcouner.STEP_UPDATE")
        val activityFilter = IntentFilter("com.example.stepcouner.ACTIVITY_UPDATE")

        ContextCompat.registerReceiver(
            this,
            stepReceiver,
            stepFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        ContextCompat.registerReceiver(
            this,
            activityReceiver,
            activityFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Update UI when resuming
        updateUI()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(stepReceiver)
            unregisterReceiver(activityReceiver)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error unregistering receiver", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stepUpdateHandler.removeCallbacks(updateTask)
    }

    // Modified stepReceiver - updates local state and UI immediately
    private val stepReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val steps = intent?.getIntExtra("steps", 0) ?: return
            val activity = intent.getStringExtra("activity") ?: "Unknown"
            val walking = intent.getBooleanExtra("isWalking", false)

            // Update local state
            currentSteps = steps
            currentActivity = activity
            isWalking = walking

            // Update UI immediately
            updateUI()

            Log.d("StepReceiver", "Updated steps: $steps, activity: $activity, walking: $walking")
        }
    }

    // Modified activityReceiver - updates local state and UI immediately
    private val activityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val activity = intent?.getStringExtra("activity") ?: return

            // Update local state
            currentActivity = activity
            isWalking = activity == "Walking" || activity == "Running" || activity == "On Foot"

            // Update UI immediately
            updateUI()

            Log.d("ActivityReceiver", "Activity changed to: $activity, walking: $isWalking")
        }
    }

    private fun scheduleMidnightReset() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, ResetReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this, 1002, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val midnight = Calendar.getInstance().apply {
            timeInMillis = System.currentTimeMillis()
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, 1)
        }

        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, midnight.timeInMillis, pendingIntent
        )
        Log.d("MainActivity", "Midnight reset scheduled for: ${midnight.time}")
    }
}