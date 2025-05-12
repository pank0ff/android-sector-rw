package com.example.usb_sector_rw

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class GraphActivity : AppCompatActivity() {

    private var isRunning = true
    private lateinit var graphView: CustomGraphView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_graph)

        graphView = findViewById(R.id.graphView)

        Thread {
            while (isRunning) {
                val value = LospDevVariables.getFrec()
                runOnUiThread { graphView.addPoint(value) }
                Thread.sleep(500)
            }
        }.start()

        findViewById<Button>(R.id.backButton).setOnClickListener {
            isRunning = false
            finish()
        }
    }
}