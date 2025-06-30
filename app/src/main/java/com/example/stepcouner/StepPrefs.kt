package com.example.stepcouner

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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
    private const val KEY_LAST_SAVED_DATE = "last_saved_date"
    private const val KEY_STEP_OFFSET = "step_offset"
    private const val KEY_DAILY_GOAL = "daily_goal"
    private const val KEY_TOTAL_STEPS = "total_steps"
    private const val KEY_LAST_RESET_DATE = "last_reset_date"

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
        val yesterdayDate =
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(calendar.time)
        saveToHistory(context, yesterdayDate, stepsToday)
        setTodaySteps(context, 0)
        setInitialSteps(context, -1)
        setLastResetDate(context, getCurrentDate())
    }

    fun getLastSavedDate(context: Context): String {
        return getPrefs(context).getString(KEY_LAST_SAVED_DATE, "") ?: ""
    }

    fun setLastSavedDate(context: Context, date: String) {
        getPrefs(context).edit()
            .putString(KEY_LAST_SAVED_DATE, date)
            .apply()
    }

    fun getStepOffset(context: Context): Int {
        return getPrefs(context).getInt(KEY_STEP_OFFSET, -1)
    }

    fun setStepOffset(context: Context, steps: Int) {
        getPrefs(context).edit()
            .putInt(KEY_STEP_OFFSET, steps)
            .apply()
    }

    // Additional useful methods

    /**
     * Get daily step goal (default 10000)
     */
    fun getDailyGoal(context: Context): Int {
        return getPrefs(context).getInt(KEY_DAILY_GOAL, 10000)
    }

    /**
     * Set daily step goal
     */
    fun setDailyGoal(context: Context, goal: Int) {
        getPrefs(context).edit().putInt(KEY_DAILY_GOAL, goal).apply()
    }

    /**
     * Check if daily goal is achieved
     */
    fun isDailyGoalAchieved(context: Context): Boolean {
        return getTodaySteps(context) >= getDailyGoal(context)
    }

    /**
     * Get progress percentage towards daily goal
     */
    fun getDailyProgress(context: Context): Float {
        val todaySteps = getTodaySteps(context)
        val goal = getDailyGoal(context)
        return if (goal > 0) (todaySteps.toFloat() / goal.toFloat()) * 100f else 0f
    }

    /**
     * Get current date in yyyy-MM-dd format
     */
    fun getCurrentDate(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    /**
     * Check if it's a new day since last reset
     */
    fun isNewDay(context: Context): Boolean {
        val lastResetDate = getLastResetDate(context)
        val currentDate = getCurrentDate()
        return lastResetDate != currentDate
    }

    /**
     * Get last reset date
     */
    fun getLastResetDate(context: Context): String {
        return getPrefs(context).getString(KEY_LAST_RESET_DATE, "") ?: ""
    }

    /**
     * Set last reset date
     */
    fun setLastResetDate(context: Context, date: String) {
        getPrefs(context).edit().putString(KEY_LAST_RESET_DATE, date).apply()
    }

    /**
     * Get steps for a specific date from history
     */
    fun getStepsForDate(context: Context, date: String): Int {
        return getHistory(context)[date] ?: 0
    }

    /**
     * Get total steps from all history
     */
    fun getTotalSteps(context: Context): Int {
        val history = getHistory(context)
        val todaySteps = getTodaySteps(context)
        return history.values.sum() + todaySteps
    }

    /**
     * Get weekly steps (last 7 days including today)
     */
    fun getWeeklySteps(context: Context): Int {
        val history = getHistory(context)
        val calendar = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        var weeklySteps = getTodaySteps(context) // Include today's steps

        // Get last 6 days
        for (i in 1..6) {
            calendar.add(Calendar.DATE, -1)
            val date = dateFormat.format(calendar.time)
            weeklySteps += history[date] ?: 0
        }

        return weeklySteps
    }

    /**
     * Get monthly steps (current month)
     */
    fun getMonthlySteps(context: Context): Int {
        val history = getHistory(context)
        val calendar = Calendar.getInstance()
        val currentMonth = calendar.get(Calendar.MONTH)
        val currentYear = calendar.get(Calendar.YEAR)

        return history.filter { (date, _) ->
            val parts = date.split("-")
            if (parts.size == 3) {
                val year = parts[0].toIntOrNull() ?: 0
                val month = parts[1].toIntOrNull()?.minus(1) ?: 0 // Month is 0-based in Calendar
                year == currentYear && month == currentMonth
            } else false
        }.values.sum() + getTodaySteps(context)
    }

    /**
     * Get average daily steps from history
     */
    fun getAverageDailySteps(context: Context): Float {
        val history = getHistory(context)
        return if (history.isNotEmpty()) {
            history.values.sum().toFloat() / history.size
        } else 0f
    }

    /**
     * Clear all data
     */
    fun clearAllData(context: Context) {
        getPrefs(context).edit().clear().apply()
    }

    /**
     * Remove specific date from history
     */
    fun removeFromHistory(context: Context, date: String) {
        val json = getPrefs(context).getString(KEY_HISTORY, "{}")
        val history = JSONObject(json ?: "{}")
        history.remove(date)
        getPrefs(context).edit().putString(KEY_HISTORY, history.toString()).apply()
    }

    /**
     * Get best day (highest step count) from history
     */
    fun getBestDay(context: Context): Pair<String, Int>? {
        val history = getHistory(context)
        val todaySteps = getTodaySteps(context)
        val currentDate = getCurrentDate()

        // Include today's steps in comparison
        val allSteps = history.toMutableMap()
        allSteps[currentDate] = todaySteps

        return allSteps.maxByOrNull { it.value }?.toPair()
    }

    /**
     * Get streak of consecutive days meeting daily goal
     */
    fun getCurrentStreak(context: Context): Int {
        val history = getHistory(context)
        val goal = getDailyGoal(context)
        val calendar = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        var streak = 0

        // Check if today's goal is met
        if (getTodaySteps(context) >= goal) {
            streak++
        } else {
            return 0 // Streak is broken
        }

        // Check previous days
        for (i in 1..365) { // Check up to a year back
            calendar.add(Calendar.DATE, -1)
            val date = dateFormat.format(calendar.time)
            val steps = history[date] ?: 0

            if (steps >= goal) {
                streak++
            } else {
                break
            }
        }

        return streak
    }

    /**
     * Save history entry - used by StepCounterService
     * This is an alias for saveToHistory but with a different date format
     */
    fun saveHistoryEntry(context: Context, date: String, steps: Int) {
        // Convert from yyyyMMdd format to yyyy-MM-dd format if needed
        val formattedDate = if (date.length == 8 && date.matches(Regex("\\d{8}"))) {
            "${date.substring(0, 4)}-${date.substring(4, 6)}-${date.substring(6, 8)}"
        } else {
            date
        }
        saveToHistory(context, formattedDate, steps)
    }

    /**
     * Update weekly average - calculates and stores the weekly average
     */
    fun updateWeeklyAverage(context: Context) {
        val weeklySteps = getWeeklySteps(context)
        val weeklyAverage = weeklySteps / 7.0f

        // Store the weekly average in preferences
        getPrefs(context).edit()
            .putFloat("weekly_average", weeklyAverage)
            .apply()

        Log.d("StepPrefs", "Weekly average updated: $weeklyAverage steps/day")
    }

    /**
     * Get the current weekly average
     */
    fun getWeeklyAverage(context: Context): Float {
        return getPrefs(context).getFloat("weekly_average", 0f)
    }
}