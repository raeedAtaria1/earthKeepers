package com.google.mediapipe.examples.gesturerecognizer

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SessionSummaryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_session_summary)

        val classCountMap = intent.getSerializableExtra("classCountMap") as? Map<String, Int>
        val totalPoints = intent.getIntExtra("totalPoints", 0)

        // Log the received data for debugging
        Log.d("SessionSummaryActivity", "Received Class Count Map: $classCountMap")
        Log.d("SessionSummaryActivity", "Received Total Points: $totalPoints")

        val summaryTextView: TextView = findViewById(R.id.summaryTextView)
        val pointsTextView: TextView = findViewById(R.id.pointsTextView)
        val backToHomeButton: Button = findViewById(R.id.backToHomeButton)

        summaryTextView.text = buildSummaryString(classCountMap)
        pointsTextView.text = "Total Points: $totalPoints"

        backToHomeButton.setOnClickListener {
            Log.d("SessionSummaryActivity", "Back to Home button clicked")
            val intent = Intent(this, HomeActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            finish() // Finish the SessionSummaryActivity
        }
    }

    private fun buildSummaryString(classCountMap: Map<String, Int>?): String {
        if (classCountMap == null) return "No items collected"

        val stringBuilder = StringBuilder()
        classCountMap.forEach { (clsName, count) ->
            stringBuilder.append("$clsName: $count items\n")
        }
        return stringBuilder.toString()
    }
}
