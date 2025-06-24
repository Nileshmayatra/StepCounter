package com.example.stepcouner

import android.app.*
import android.content.*
import android.hardware.*
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlin.math.sqrt

class StepCounterService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var stepDetector: Sensor? = null
    private var accelSensor: Sensor? = null

    private var isShaking = false
    private var lastShakeTime: Long = 0
    private var shakeCount = 0

    private val SHAKE_THRESHOLD = 13.0
    private val SHAKE_COUNT_REQUIRED = 2
    private val SHAKE_TIMEOUT = 1500L // ms

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, getStepNotification())

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        stepDetector?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        accelSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        val now = System.currentTimeMillis()

        when (event.sensor.type) {

            Sensor.TYPE_STEP_DETECTOR -> {
                if (!isShaking) {
                    var steps = StepPrefs.getTodaySteps(applicationContext)
                    steps += event.values.size
                    Log.d("StepDetector", "Detected step. Total: $steps")

                    val intent = Intent("com.example.stepcouner.STEP_UPDATE")
                    intent.putExtra("steps", steps)
                    sendBroadcast(intent)
                } else {
                    Log.d("StepDetector", "Step ignored due to shake")
                }
            }

            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]

                val acceleration = sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH

                if (acceleration > SHAKE_THRESHOLD) {
                    shakeCount++
                    if (shakeCount >= SHAKE_COUNT_REQUIRED && now - lastShakeTime > 1000) {
                        isShaking = true
                        lastShakeTime = now
                        Log.d("Shake", "Strong shake detected!")
                        shakeCount = 0
                    }
                } else {
                    shakeCount = 0
                }

                if (now - lastShakeTime > SHAKE_TIMEOUT) {
                    isShaking = false
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "step_channel",
                "Step Counter Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun getStepNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, "step_channel")
            .setContentTitle("Step Detector Running")
            .setContentText("Tracking steps in background")
            .setSmallIcon(R.drawable.walking)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
