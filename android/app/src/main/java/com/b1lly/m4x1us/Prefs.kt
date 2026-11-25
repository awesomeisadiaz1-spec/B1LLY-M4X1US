package com.b1lly.m4x1us

import android.content.Context

/** Simple settings store. Autonomy: "full" (default), "risky" or "ask". */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("billy", Context.MODE_PRIVATE)
    fun brain(c: Context): String = sp(c).getString("brain", "http://127.0.0.1:8787")!!
    fun setBrain(c: Context, v: String) = sp(c).edit().putString("brain", v.trim().trimEnd('/')).apply()
    fun autonomy(c: Context): String = sp(c).getString("autonomy", "full")!!
    fun setAutonomy(c: Context, v: String) = sp(c).edit().putString("autonomy", v).apply()
    fun speak(c: Context): Boolean = sp(c).getBoolean("speak", true)
    fun setSpeak(c: Context, v: Boolean) = sp(c).edit().putBoolean("speak", v).apply()
    fun wander(c: Context): Boolean = sp(c).getBoolean("wander", true)
    fun setWander(c: Context, v: Boolean) = sp(c).edit().putBoolean("wander", v).apply()
}
