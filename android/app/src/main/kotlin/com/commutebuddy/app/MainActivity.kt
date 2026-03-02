package com.commutebuddy.app

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var codeTextView: TextView
    private lateinit var statusTextView: TextView
    private lateinit var sendButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        codeTextView = findViewById(R.id.codeTextView)
        statusTextView = findViewById(R.id.statusTextView)
        sendButton = findViewById(R.id.sendButton)

        sendButton.setOnClickListener {
            val code = Random.nextInt(1000, 10000)
            codeTextView.text = code.toString()
            statusTextView.text = getString(R.string.status_ready_to_send)
        }
    }
}
