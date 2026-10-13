package com.example.volumebuttons

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar

class ChangelogActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        applySavedTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_changelog)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            finish()
        }

        val list = findViewById<RecyclerView>(R.id.changelogList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = Adapter(Changelogs.load(this))
    }

    private fun applySavedTheme() {
        when (FloatingVolumeService.prefsOf(this)
            .getString(FloatingVolumeService.KEY_THEME, "dark")) {
            "light" -> AppCompatDelegate.setDefaultNightMode(
                AppCompatDelegate.MODE_NIGHT_NO)
            "system" -> AppCompatDelegate.setDefaultNightMode(
                AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            else -> AppCompatDelegate.setDefaultNightMode(
                AppCompatDelegate.MODE_NIGHT_YES)
        }
    }

    private class Adapter(private val items: List<Changelogs.Entry>) :
        RecyclerView.Adapter<Adapter.Holder>() {

        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.entryTitle)
            val notes: TextView = v.findViewById(R.id.entryNotes)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_changelog, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            val e = items[position]
            h.title.text = "v${e.version}"
            h.notes.text = e.notes
        }

        override fun getItemCount() = items.size
    }
}
