package com.example.stepcouner

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object StepPrefs {
    private const val PREF_NAME = "step_prefs"
    private const val KEY_INITIAL = "initial_steps"
    private const val KEY_TODAY = "steps_today"
    private const val KEY_HISTORY = "step_history"

    fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getInitialSteps(context: Context): Int =
        getPrefs(context).getInt(KEY_INITIAL, -1)

    fun setInitialSteps(context: Context, steps: Int) {
        getPrefs(context).edit().putInt(KEY_INITIAL, steps).apply()
    }

    fun setTodaySteps(context: Context, steps: Int) {
        getPrefs(context).edit().putInt(KEY_TODAY, steps).apply()
    }

    fun getTodaySteps(context: Context): Int =
        getPrefs(context).getInt(KEY_TODAY, 0)

    fun saveToHistory(context: Context, date: String, steps: Int) {
        val prefs = getPrefs(context)
        val json = prefs.getString(KEY_HISTORY, "{}")
        val history = JSONObject(json ?: "{}")
        history.put(date, steps)
        prefs.edit().putString(KEY_HISTORY, history.toString()).apply()
    }

    fun getHistory(context: Context): Map<String, Int> {
        val json = getPrefs(context).getString(KEY_HISTORY, "{}")
        val history = mutableMapOf<String, Int>()
        val jsonObj = JSONObject(json ?: "{}")
        jsonObj.keys().forEach {
            history[it] = jsonObj.getInt(it)
        }
        return history
    }

    fun resetDaily(context: Context) {
        val stepsToday = getTodaySteps(context)
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DATE, -1)
        val yesterdayDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(calendar.time)
        saveToHistory(context, yesterdayDate, stepsToday)
        setTodaySteps(context, 0)
        setInitialSteps(context, -1)
    }
}
