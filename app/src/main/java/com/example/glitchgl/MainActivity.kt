package com.example.glitchgl

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * MainActivity
 *
 * Entry point of the app. Hosts:
 *  - A GlitchGLSurfaceView that renders the image via OpenGL
 *  - A SeekBar to control glitch intensity
 *  - A button to pick an image from the gallery
 *
 * Web analogy: This is like your index.html + main.js combined.
 * It wires up the UI and kicks off the OpenGL rendering surface.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var glitchView: GlitchGLSurfaceView
    private lateinit var intensityLabel: TextView

    // Request code for the image picker intent (like a callback ID)
    private val PICK_IMAGE_REQUEST = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Inflate the XML layout — like rendering your HTML template
        setContentView(R.layout.activity_main)

        // Find views by ID — like document.getElementById()
        glitchView = findViewById(R.id.glitchGLSurface)
        intensityLabel = findViewById(R.id.intensityLabel)
        val seekBar = findViewById<SeekBar>(R.id.intensitySeekBar)
        val pickButton = findViewById<Button>(R.id.pickImageButton)

        // Load a default bundled image on startup
        val defaultBitmap = BitmapFactory.decodeResource(resources, R.drawable.sample_image)
        glitchView.loadBitmap(defaultBitmap)

        // SeekBar controls glitch intensity (0.0 - 1.0)
        // Like an <input type="range"> with an onChange handler
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val intensity = progress / 100f
                glitchView.setGlitchIntensity(intensity)
                intensityLabel.text = "Glitch: ${progress}%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        // Button opens the system image picker
        // Like <input type="file" accept="image/*"> but Android style
        pickButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            intent.type = "image/*"
            startActivityForResult(intent, PICK_IMAGE_REQUEST)
        }
    }

    // Called when the image picker returns a result
    // Like a Promise.then() or file input onChange callback
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_IMAGE_REQUEST && resultCode == Activity.RESULT_OK) {
            val imageUri: Uri? = data?.data
            imageUri?.let {
                val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, it)
                glitchView.loadBitmap(bitmap)
            }
        }
    }

    // Lifecycle: forward GL lifecycle events to the surface view
    // Android pauses/resumes apps — OpenGL context must be managed explicitly
    override fun onResume() {
        super.onResume()
        glitchView.onResume()   // Restart the render loop
    }

    override fun onPause() {
        super.onPause()
        glitchView.onPause()    // Pause the render loop to save battery
    }
}
