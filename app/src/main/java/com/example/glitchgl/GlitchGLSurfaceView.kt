package com.example.glitchgl

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.util.AttributeSet

/**
 * GlitchGLSurfaceView
 *
 * A custom View that hosts an OpenGL ES 2.0 rendering context.
 *
 * Web analogy: This is like a <canvas> element. It is just the surface
 * that OpenGL draws onto. The actual drawing logic lives in the Renderer,
 * just like your canvas 2D/WebGL context logic lives in JS.
 *
 * GLSurfaceView handles:
 *  - Creating the EGL context (OpenGL's connection to the display)
 *  - Managing a dedicated GL thread (separate from the UI thread)
 *  - Calling Renderer methods at the right time
 */
class GlitchGLSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val renderer: GlitchRenderer

    init {
        // Tell the surface we want OpenGL ES 2.0
        // Like requesting a "webgl2" context from canvas.getContext()
        setEGLContextClientVersion(2)

        // Create our renderer and attach it
        renderer = GlitchRenderer(context)
        setRenderer(renderer)

        // RENDERMODE_CONTINUOUSLY = draw every frame (like requestAnimationFrame loop)
        // Alternative: RENDERMODE_WHEN_DIRTY = only draw when requestRender() is called
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    /**
     * Pass a new bitmap to the renderer.
     * Must post to GL thread — you cannot touch GL state from the UI thread.
     * Web analogy: like postMessage() to a Web Worker that owns the canvas.
     */
    fun loadBitmap(bitmap: Bitmap) {
        queueEvent {
            renderer.loadBitmap(bitmap)
        }
    }

    /**
     * Update glitch intensity uniform in the shader.
     * queueEvent ensures this runs on the GL thread safely.
     */
    fun setGlitchIntensity(intensity: Float) {
        queueEvent {
            renderer.setGlitchIntensity(intensity)
        }
    }
}
