package com.android.quicknotesvibe

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val etToken = findViewById<EditText>(R.id.etToken)
        val etOwner = findViewById<EditText>(R.id.etOwner)
        val etRepo  = findViewById<EditText>(R.id.etRepo)

        etToken.setText(Prefs.token()); etOwner.setText(Prefs.owner()); etRepo.setText(Prefs.repo())

        findViewById<Button>(R.id.btnSaveSettings).setOnClickListener {
            if (etToken.text.isBlank() || etOwner.text.isBlank() || etRepo.text.isBlank()) {
                Toast.makeText(this, "All fields are required", Toast.LENGTH_SHORT).show(); return@setOnClickListener
            }
            Prefs.save(etToken.text.toString(), etOwner.text.toString(), etRepo.text.toString())
            Toast.makeText(this, "Saved (encrypted) ✓", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}