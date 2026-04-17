package com.jecheon.voicecoach

import android.content.Context
import android.os.Bundle
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "설정"

        val prefs = getSharedPreferences("voicecoach_settings", Context.MODE_PRIVATE)

        val seekTts = findViewById<SeekBar>(R.id.seekTtsVolume)
        val tvTts = findViewById<TextView>(R.id.tvTtsVolume)
        val seekMetro = findViewById<SeekBar>(R.id.seekMetronomeVolume)
        val tvMetro = findViewById<TextView>(R.id.tvMetronomeVolume)

        val ttsInit = prefs.getInt("tts_volume", 100)
        val metroInit = prefs.getInt("metronome_volume", 100)

        seekTts.progress = ttsInit
        tvTts.text = "$ttsInit%"
        seekMetro.progress = metroInit
        tvMetro.text = "$metroInit%"

        seekTts.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvTts.text = "$progress%"
                prefs.edit().putInt("tts_volume", progress).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        seekMetro.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvMetro.text = "$progress%"
                prefs.edit().putInt("metronome_volume", progress).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
