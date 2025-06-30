package com.example.stepcouner

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import android.app.PendingIntent as AndroidPendingIntent

class StepCounterService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var stepCounterSensor: Sensor? = null
    private var stepDetectorSensor: Sensor? = null
    private var accelerometerSensor: Sensor? = null

    // Core step tracking variables
    private var currentSteps = 0
    private var currentActivity = "Unknown"
    private var isWalking = false

    // Enhanced sensor tracking
    private var lastSensorStepCount = -1
    private var isStepCounterInitialized = false
    private var lastUpdateTime = System.currentTimeMillis()

    // CRITICAL: Track pending steps that occurred during non-walking activities
    private var pendingStepsBuffer = 0
    private var lastValidWalkingTime = System.currentTimeMillis()

    // Step smoothing and validation
    private val stepBuffer = mutableListOf<Int>()
    private var consecutiveValidSteps = 0
    private val maxBufferSize = 5

    // Activity recognition
    private lateinit var activityRecognitionClient: ActivityRecognitionClient
    private lateinit var activityTransitionPendingIntent: AndroidPendingIntent
    private var isActivityRecognitionActive = false

    private var sessionStartTime = System.currentTimeMillis()

    companion object {
        private const val SERVICE_ID = 1001
        private const val CHANNEL_ID = "StepCounterChannel"
        private const val ACTIVITY_RECOGNITION_REQUEST_CODE = 2001
        private const val TAG = "StepCounterService"

        // Validation constants
        private const val MAX_STEPS_PER_SECOND = 5
        private const val MAX_STEPS_PER_UPDATE = 50
        private const val MIN_TIME_BETWEEN_UPDATES = 500L // 0.5 seconds
        private const val STEP_VALIDATION_THRESHOLD = 3
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        setupNotificationChannel()
        setupSensors()
        setupActivityRecognition()

        // Load today's data from preferences
        loadTodayData()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")

        // Handle activity update if provided
        intent?.getStringExtra("activity_update")?.let { activity ->
            updateActivity(activity)
        }

        val notification = createNotification()
        startForeground(SERVICE_ID, notification)

        startStepDetection()
        startActivityRecognition()
        startRegularActivityRecognition()

        return START_STICKY
    }

    private fun setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Step Counter Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Tracks your steps and activity in the background"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = AndroidPendingIntent.getActivity(
            this,
            0,
            intent,
            AndroidPendingIntent.FLAG_UPDATE_CURRENT or AndroidPendingIntent.FLAG_IMMUTABLE
        )

        val statusText = buildString {
            append("Steps: $currentSteps")
            if (currentActivity != "Unknown") append(" • $currentActivity")
        }

        val expandedText = buildString {
            append("Steps: $currentSteps")
            append("\nActivity: $currentActivity")
            if (pendingStepsBuffer > 0) append("\nPending: $pendingStepsBuffer")
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Step Counter Active")
            .setContentText(statusText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expandedText))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun setupSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        stepDetectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        Log.d(TAG, "Available sensors:")
        Log.d(TAG, "Step Counter: ${stepCounterSensor?.name ?: "Not available"}")
        Log.d(TAG, "Step Detector: ${stepDetectorSensor?.name ?: "Not available"}")
        Log.d(TAG, "Accelerometer: ${accelerometerSensor?.name ?: "Not available"}")
    }

    private fun setupActivityRecognition() {
        try {
            activityRecognitionClient = ActivityRecognition.getClient(this)

            val intent = Intent(this, ActivityRecognitionReceiver::class.java)
            activityTransitionPendingIntent = AndroidPendingIntent.getBroadcast(
                this,
                ACTIVITY_RECOGNITION_REQUEST_CODE,
                intent,
                AndroidPendingIntent.FLAG_UPDATE_CURRENT or AndroidPendingIntent.FLAG_MUTABLE
            )

            Log.d(TAG, "Activity recognition client setup complete")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup activity recognition", e)
        }
    }

    private fun loadTodayData() {
        currentSteps = StepPrefs.getTodaySteps(this)
        Log.d(TAG, "Loaded today's data: $currentSteps steps")
    }

    @SuppressLint("MissingPermission")
    private fun startRegularActivityRecognition() {
        try {
            val intent = Intent(this, ActivityRecognitionReceiver::class.java)
            val pendingIntent = AndroidPendingIntent.getBroadcast(
                this,
                ACTIVITY_RECOGNITION_REQUEST_CODE + 1,
                intent,
                AndroidPendingIntent.FLAG_UPDATE_CURRENT or AndroidPendingIntent.FLAG_MUTABLE
            )

            // Request activity updates every 10 seconds for better accuracy
            activityRecognitionClient.requestActivityUpdates(10000, pendingIntent)
                .addOnSuccessListener {
                    Log.d(TAG, "Regular activity recognition started successfully")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to start regular activity recognition", e)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up regular activity recognition", e)
        }
    }

    private fun startStepDetection() {
        // Prioritize step counter sensor (more accurate)
        stepCounterSensor?.let { sensor ->
            val registered = sensorManager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_UI
            )
            Log.d(TAG, "Step counter sensor registered: $registered")
        }

        // Register step detector only if step counter is not available
        if (stepCounterSensor == null) {
            stepDetectorSensor?.let { sensor ->
                val registered = sensorManager.registerListener(
                    this,
                    sensor,
                    SensorManager.SENSOR_DELAY_UI
                )
                Log.d(TAG, "Step detector sensor registered: $registered")
            }
        }

        if (stepCounterSensor == null && stepDetectorSensor == null) {
            Log.w(TAG, "No step sensors available on this device")
            // Could implement accelerometer-based step detection here as fallback
        }
    }

    @SuppressLint("MissingPermission")
    private fun startActivityRecognition() {
        try {
            val transitions = mutableListOf<ActivityTransition>()

            // Define all activity transitions we care about
            val activities = listOf(
                DetectedActivity.WALKING,
                DetectedActivity.RUNNING,
                DetectedActivity.STILL,
                DetectedActivity.IN_VEHICLE,
                DetectedActivity.ON_BICYCLE,
                DetectedActivity.ON_FOOT
            )

            activities.forEach { activityType ->
                transitions.add(
                    ActivityTransition.Builder()
                        .setActivityType(activityType)
                        .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                        .build()
                )
                transitions.add(
                    ActivityTransition.Builder()
                        .setActivityType(activityType)
                        .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                        .build()
                )
            }

            val request = ActivityTransitionRequest(transitions)

            activityRecognitionClient.requestActivityTransitionUpdates(request, activityTransitionPendingIntent)
                .addOnSuccessListener {
                    isActivityRecognitionActive = true
                    Log.d(TAG, "Activity transition recognition started successfully")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to start activity transition recognition", e)
                }

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up activity transition recognition", e)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                handleStepCounterSensor(event)
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                handleStepDetectorSensor(event)
            }
        }
    }

    private fun handleStepCounterSensor(event: SensorEvent) {
        val totalSteps = event.values[0].toInt()
        val currentTime = System.currentTimeMillis()
        val todayDate = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val savedDate = StepPrefs.getLastSavedDate(this)

        // Check if it's a new day
        if (savedDate != todayDate) {
            handleNewDay(totalSteps, todayDate)
            return
        }

        // Initialize on first run
        if (!isStepCounterInitialized) {
            initializeStepCounter(totalSteps)
            return
        }

        // Calculate raw steps since last reading
        if (lastSensorStepCount != -1) {
            val stepsSinceLastReading = totalSteps - lastSensorStepCount

            if (validateStepIncrement(stepsSinceLastReading, currentTime)) {
                val smoothedSteps = smoothStepCount(stepsSinceLastReading)

                if (smoothedSteps > 0) {
                    // CRITICAL FIX: Only count steps if we're walking
                    if (isWalking) {
                        currentSteps += smoothedSteps
                        lastValidWalkingTime = currentTime
                        consecutiveValidSteps++

                        Log.d(TAG, "Steps added while walking: $smoothedSteps (raw: $stepsSinceLastReading), Total: $currentSteps")
                        updateStepCount()
                    } else {
                        // Store steps that occurred during non-walking activities
                        pendingStepsBuffer += smoothedSteps
                        Log.d(TAG, "Steps buffered during non-walking activity ($currentActivity): $smoothedSteps, Buffer: $pendingStepsBuffer")

                        // Update notification to show pending steps
                        updateStepCount()
                    }

                    lastUpdateTime = currentTime
                }
            } else {
                handleInvalidStepReading(stepsSinceLastReading, totalSteps)
            }
        }

        lastSensorStepCount = totalSteps
    }

    private fun handleNewDay(totalSteps: Int, todayDate: String) {
        // Save yesterday's data to history
        val yesterdayDate = StepPrefs.getLastSavedDate(this)
        if (yesterdayDate.isNotEmpty() && currentSteps > 0) {
            StepPrefs.saveHistoryEntry(this, yesterdayDate, currentSteps)
            Log.d(TAG, "Saved yesterday's steps: $currentSteps for date: $yesterdayDate")
        }

        // Reset for new day
        StepPrefs.setStepOffset(this, totalSteps)
        StepPrefs.setLastSavedDate(this, todayDate)
        StepPrefs.setTodaySteps(this, 0)

        currentSteps = 0
        pendingStepsBuffer = 0
        lastSensorStepCount = totalSteps
        isStepCounterInitialized = true
        consecutiveValidSteps = 0
        stepBuffer.clear()

        // Update weekly average
        StepPrefs.updateWeeklyAverage(this)

        Log.d(TAG, "New day detected. Reset step count. Offset: $totalSteps")
        updateStepCount()
    }

    private fun initializeStepCounter(totalSteps: Int) {
        val stepOffset = StepPrefs.getStepOffset(this)

        if (stepOffset == 0) {
            // First time running, set offset
            StepPrefs.setStepOffset(this, totalSteps)
            currentSteps = 0
        } else {
            // Service restarted, calculate current steps
            val calculatedSteps = maxOf(0, totalSteps - stepOffset)
            currentSteps = maxOf(currentSteps, calculatedSteps) // Don't decrease steps
        }

        lastSensorStepCount = totalSteps
        isStepCounterInitialized = true

        Log.d(TAG, "Step counter initialized. Current steps: $currentSteps, Sensor: $totalSteps, Offset: ${StepPrefs.getStepOffset(this)}")
        updateStepCount()
    }

    private fun validateStepIncrement(stepIncrement: Int, currentTime: Long): Boolean {
        val timeSinceLastUpdate = currentTime - lastUpdateTime

        // Basic range check
        if (stepIncrement <= 0 || stepIncrement > MAX_STEPS_PER_UPDATE) {
            return false
        }

        // Time-based validation
        if (timeSinceLastUpdate < MIN_TIME_BETWEEN_UPDATES && stepIncrement > 5) {
            return false
        }

        // Rate limiting (steps per second)
        val maxStepsForTimePeriod = ((timeSinceLastUpdate / 1000.0) * MAX_STEPS_PER_SECOND).toInt() + 1
        if (stepIncrement > maxStepsForTimePeriod) {
            Log.w(TAG, "Step rate too high: $stepIncrement steps in ${timeSinceLastUpdate}ms")
            return false
        }

        return true
    }

    private fun smoothStepCount(rawSteps: Int): Int {
        stepBuffer.add(rawSteps)

        // Keep only recent readings
        while (stepBuffer.size > maxBufferSize) {
            stepBuffer.removeAt(0)
        }

        // If we have enough consecutive valid readings, trust the sensor more
        return if (consecutiveValidSteps >= STEP_VALIDATION_THRESHOLD) {
            rawSteps // Trust raw sensor data
        } else {
            // Use median filtering for initial readings
            val sorted = stepBuffer.sorted()
            sorted[sorted.size / 2]
        }
    }

    private fun handleInvalidStepReading(invalidIncrement: Int, totalSteps: Int) {
        Log.w(TAG, "Invalid step reading: $invalidIncrement")

        // Reset consecutive valid steps counter
        consecutiveValidSteps = 0

        // If increment is way too large, might be sensor reset
        if (invalidIncrement > 1000) {
            Log.w(TAG, "Possible sensor reset detected. Recalibrating...")

            // Recalculate offset based on current known steps
            val newOffset = totalSteps - currentSteps
            StepPrefs.setStepOffset(this, newOffset)

            Log.d(TAG, "Sensor recalibrated. New offset: $newOffset")
        }
    }

    private fun handleStepDetectorSensor(event: SensorEvent) {
        // Only use step detector as fallback when step counter is unavailable
        if (stepCounterSensor == null) {
            val currentTime = System.currentTimeMillis()

            // Simple rate limiting for step detector
            if (currentTime - lastUpdateTime >= 300) { // Max ~3.3 steps per second
                // CRITICAL FIX: Only count steps if we're walking
                if (isWalking) {
                    currentSteps++
                    lastUpdateTime = currentTime
                    lastValidWalkingTime = currentTime

                    Log.d(TAG, "Step detector - New step while walking. Total: $currentSteps")
                    updateStepCount()
                } else {
                    // Buffer steps during non-walking activities
                    pendingStepsBuffer++
                    Log.d(TAG, "Step detector - Step buffered during non-walking activity ($currentActivity). Buffer: $pendingStepsBuffer")
                    updateStepCount()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        val accuracyText = when (accuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "HIGH"
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "MEDIUM"
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "LOW"
            SensorManager.SENSOR_STATUS_UNRELIABLE -> "UNRELIABLE"
            else -> "UNKNOWN"
        }

        Log.d(TAG, "Sensor accuracy changed: ${sensor?.name}, accuracy: $accuracyText")

        // Could adjust validation thresholds based on accuracy
        if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            Log.w(TAG, "Sensor accuracy is unreliable. Increasing validation strictness.")
        }
    }

    private fun updateStepCount() {
        // Save to preferences
        StepPrefs.setTodaySteps(this, currentSteps)

        // Update notification
        val notification = createNotification()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(SERVICE_ID, notification)

        // Broadcast update to MainActivity
        val intent = Intent("com.example.stepcouner.STEP_UPDATE").apply {
            putExtra("steps", currentSteps)
            putExtra("activity", currentActivity)
            putExtra("isWalking", isWalking)
            putExtra("pendingSteps", pendingStepsBuffer)
        }
        sendBroadcast(intent)

        Log.d(TAG, "Step update broadcast: steps=$currentSteps, activity=$currentActivity, pending=$pendingStepsBuffer")
    }

    fun updateActivity(activity: String) {
        if (currentActivity != activity) {
            Log.d(TAG, "Activity changed from '$currentActivity' to '$activity'")

            val wasWalking = isWalking
            currentActivity = activity

            // Update walking status
            isWalking = when (activity) {
                "Walking", "On Foot", "Running" -> true
                else -> false
            }

            if (!wasWalking && isWalking) {
                Log.d(TAG, "Resumed walking. Evaluating buffered steps: $pendingStepsBuffer")

                // ✅ Safeguard: Only add buffered steps if count is reasonable
                if (pendingStepsBuffer in 1..100) {
                    currentSteps += pendingStepsBuffer
                    Log.d(TAG, "Buffered steps added to current count: $pendingStepsBuffer")
                } else {
                    Log.w(TAG, "Buffered steps discarded due to abnormal value: $pendingStepsBuffer")
                }

                pendingStepsBuffer = 0
                lastValidWalkingTime = System.currentTimeMillis()
            } else if (wasWalking && !isWalking) {
                Log.d(TAG, "Stopped walking. Future steps will be buffered.")
            }

            if (wasWalking != isWalking) {
                Log.d(TAG, "Walking status changed to: $isWalking")
            }

            updateStepCount()
        }
    }


    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")

        // Unregister sensors
        try {
            sensorManager.unregisterListener(this)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering sensor listener", e)
        }

        // Stop activity recognition
        try {
            if (isActivityRecognitionActive) {
                activityRecognitionClient.removeActivityTransitionUpdates(activityTransitionPendingIntent)
                val regularIntent = Intent(this, ActivityRecognitionReceiver::class.java)
                val regularPendingIntent = AndroidPendingIntent.getBroadcast(
                    this,
                    ACTIVITY_RECOGNITION_REQUEST_CODE + 1,
                    regularIntent,
                    AndroidPendingIntent.FLAG_UPDATE_CURRENT or AndroidPendingIntent.FLAG_MUTABLE
                )
                activityRecognitionClient.removeActivityUpdates(regularPendingIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping activity recognition", e)
        }

        // Save final state
        StepPrefs.setTodaySteps(this, currentSteps)
    }

    // Enhanced Activity Recognition Receiver
    class ActivityRecognitionReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "Activity recognition broadcast received")

            try {
                // Handle activity transition results
                if (ActivityTransitionResult.hasResult(intent)) {
                    val result = ActivityTransitionResult.extractResult(intent)
                    result?.let { transitionResult ->
                        for (event in transitionResult.transitionEvents) {
                            val activity = getActivityString(event.activityType)
                            val transitionType = if (event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) "ENTER" else "EXIT"

                            Log.d(TAG, "Activity transition: $activity $transitionType at ${Date(event.elapsedRealTimeNanos / 1000000)}")

                            // Only update activity on ENTER transitions
                            if (event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                                updateServiceActivity(context, activity)
                            }
                        }
                    }
                }
                // Handle regular activity recognition results
                else {
                    val result = com.google.android.gms.location.ActivityRecognitionResult.extractResult(intent)
                    result?.let { activityResult ->
                        val mostProbableActivity = activityResult.mostProbableActivity
                        val confidence = mostProbableActivity.confidence
                        val activity = getActivityString(mostProbableActivity.type)

                        Log.d(TAG, "Activity detected: $activity with confidence: $confidence%")

                        // Only update if confidence is reasonable (>40% for flexibility)
                        if (confidence > 40) {
                            updateServiceActivity(context, activity)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing activity recognition result", e)
            }
        }

        private fun updateServiceActivity(context: Context, activity: String) {
            // Update the running service
            val serviceIntent = Intent(context, StepCounterService::class.java)
            serviceIntent.putExtra("activity_update", activity)
            context.startService(serviceIntent)

            // Also broadcast the activity change
            val broadcastIntent = Intent("com.example.stepcouner.ACTIVITY_UPDATE").apply {
                putExtra("activity", activity)
            }
            context.sendBroadcast(broadcastIntent)
        }

        private fun getActivityString(activityType: Int): String {
            return when (activityType) {
                DetectedActivity.WALKING -> "Walking"
                DetectedActivity.RUNNING -> "Running"
                DetectedActivity.STILL -> "Still"
                DetectedActivity.IN_VEHICLE -> "In Vehicle"
                DetectedActivity.ON_BICYCLE -> "Cycling"
                DetectedActivity.ON_FOOT -> "On Foot"
                DetectedActivity.TILTING -> "Tilting"
                DetectedActivity.UNKNOWN -> "Unknown"
                else -> "Unknown ($activityType)"
            }
        }
    }
}