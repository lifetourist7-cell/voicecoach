package com.jecheon.voicecoach

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity

class CoachSettingsActivity : AppCompatActivity() {

    /** 시스템 폰트 스케일 무시 — 항상 1.0x 로 렌더링. */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase.withFixedFontScale())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_coach_settings)
        applyEdgeToEdge()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "코치 설정"

        val prefs = getSharedPreferences("voicecoach_settings", Context.MODE_PRIVATE)

        val cbUpper = findViewById<CheckBox>(R.id.cbUpperEnabled)
        val etUpper = findViewById<EditText>(R.id.etUpperBpm)
        val btnUpperMinus = findViewById<Button>(R.id.btnUpperMinus)
        val btnUpperPlus = findViewById<Button>(R.id.btnUpperPlus)

        val cbLower = findViewById<CheckBox>(R.id.cbLowerEnabled)
        val etLower = findViewById<EditText>(R.id.etLowerBpm)
        val btnLowerMinus = findViewById<Button>(R.id.btnLowerMinus)
        val btnLowerPlus = findViewById<Button>(R.id.btnLowerPlus)

        val etRealert = findViewById<EditText>(R.id.etRealertInterval)
        val btnRealertMinus = findViewById<Button>(R.id.btnRealertMinus)
        val btnRealertPlus = findViewById<Button>(R.id.btnRealertPlus)

        // Load
        cbUpper.isChecked = prefs.getBoolean("coach_upper_enabled", false)
        val upperBpm = prefs.getInt("coach_upper_bpm", 180)
        etUpper.setText(upperBpm.toString())

        cbLower.isChecked = prefs.getBoolean("coach_lower_enabled", false)
        val lowerBpm = prefs.getInt("coach_lower_bpm", 120)
        etLower.setText(lowerBpm.toString())

        val realertSec = prefs.getInt("coach_realert_interval_sec", 30).coerceIn(5, 120)
        etRealert.setText(realertSec.toString())

        fun validateAndWarn() {
            val u = prefs.getInt("coach_upper_bpm", 180)
            val l = prefs.getInt("coach_lower_bpm", 120)
            val uOn = prefs.getBoolean("coach_upper_enabled", false)
            val lOn = prefs.getBoolean("coach_lower_enabled", false)
            if (uOn && lOn && u <= l) {
                showMessage("상한(${u})이 하한(${l})보다 작거나 같습니다")
            }
        }

        fun saveBpm(et: EditText, key: String) {
            val v = et.text.toString().toIntOrNull()?.coerceIn(30, 250) ?: return
            prefs.edit().putInt(key, v).apply()
            validateAndWarn()
        }

        fun saveRealert() {
            val v = etRealert.text.toString().toIntOrNull()?.coerceIn(5, 120) ?: return
            prefs.edit().putInt("coach_realert_interval_sec", v).apply()
        }

        // Listeners
        cbUpper.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("coach_upper_enabled", checked).apply()
            validateAndWarn()
        }
        cbLower.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("coach_lower_enabled", checked).apply()
            validateAndWarn()
        }

        btnUpperMinus.setOnClickListener {
            val v = (etUpper.text.toString().toIntOrNull() ?: 180).coerceIn(30, 250) - 1
            etUpper.setText(v.coerceIn(30, 250).toString())
            saveBpm(etUpper, "coach_upper_bpm")
        }
        btnUpperPlus.setOnClickListener {
            val v = (etUpper.text.toString().toIntOrNull() ?: 180).coerceIn(30, 250) + 1
            etUpper.setText(v.coerceIn(30, 250).toString())
            saveBpm(etUpper, "coach_upper_bpm")
        }
        etUpper.setOnFocusChangeListener { _, focused ->
            if (!focused) saveBpm(etUpper, "coach_upper_bpm")
        }

        btnLowerMinus.setOnClickListener {
            val v = (etLower.text.toString().toIntOrNull() ?: 120).coerceIn(30, 250) - 1
            etLower.setText(v.coerceIn(30, 250).toString())
            saveBpm(etLower, "coach_lower_bpm")
        }
        btnLowerPlus.setOnClickListener {
            val v = (etLower.text.toString().toIntOrNull() ?: 120).coerceIn(30, 250) + 1
            etLower.setText(v.coerceIn(30, 250).toString())
            saveBpm(etLower, "coach_lower_bpm")
        }
        etLower.setOnFocusChangeListener { _, focused ->
            if (!focused) saveBpm(etLower, "coach_lower_bpm")
        }

        btnRealertMinus.setOnClickListener {
            val v = (etRealert.text.toString().toIntOrNull() ?: 30).coerceIn(5, 120) - 1
            etRealert.setText(v.coerceIn(5, 120).toString())
            saveRealert()
        }
        btnRealertPlus.setOnClickListener {
            val v = (etRealert.text.toString().toIntOrNull() ?: 30).coerceIn(5, 120) + 1
            etRealert.setText(v.coerceIn(5, 120).toString())
            saveRealert()
        }
        etRealert.setOnFocusChangeListener { _, focused ->
            if (!focused) saveRealert()
        }
    }

    override fun onPause() {
        super.onPause()
        // Ensure any pending EditText edit is saved
        val prefs = getSharedPreferences("voicecoach_settings", Context.MODE_PRIVATE)
        findViewById<EditText>(R.id.etUpperBpm).text.toString().toIntOrNull()?.coerceIn(30, 250)?.let {
            prefs.edit().putInt("coach_upper_bpm", it).apply()
        }
        findViewById<EditText>(R.id.etLowerBpm).text.toString().toIntOrNull()?.coerceIn(30, 250)?.let {
            prefs.edit().putInt("coach_lower_bpm", it).apply()
        }
        findViewById<EditText>(R.id.etRealertInterval).text.toString().toIntOrNull()?.coerceIn(5, 120)?.let {
            prefs.edit().putInt("coach_realert_interval_sec", it).apply()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
