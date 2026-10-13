package com.example.volumebuttons

import android.content.Context
import org.json.JSONArray

object Changelogs {

    data class Entry(val version: String, val code: Int, val notes: String)

    fun load(context: Context): List<Entry> {
        return try {
            val text = context.assets.open("changelog.json")
                .bufferedReader().readText()
            val arr = JSONArray(text)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Entry(
                    o.getString("version"),
                    o.getInt("code"),
                    o.optString("notes", "")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
