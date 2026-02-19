package com.example.glitchgl

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GlitchRenderer
 *
 * Implements GLSurfaceView.Renderer — the three callbacks are your entire render lifecycle:
 *
 *   onSurfaceCreated  → runs once, like window.onload. Compile shaders, allocate buffers.
 *   onSurfaceChanged  → runs on resize, like window.onresize. Update viewport/projection.
 *   onDrawFrame       → runs every frame, like requestAnimationFrame. Draw the scene.
 *
 * Pipeline overview:
 *   1. A full-screen quad (two triangles) is defined in CPU memory as vertex data
 *   2. Vertex shader positions those triangles to fill the screen
 *   3. Fragment shader runs per-pixel, sampling the image texture + applying glitch math
 *   4. Result appears on screen
 */
class GlitchRenderer(private val context: Context) : GLSurfaceView.Renderer {

    // --- GL object handles (integers that reference GPU-side resources) ---
    // Like IDs returned by the GPU driver. Web analogy: WebGL buffer/program handles.
    private var programHandle = 0        // Compiled+linked shader program
    private var textureHandle = 0        // GPU texture holding the image
    private var vertexBufferHandle = 0   // GPU buffer holding quad geometry

    // --- Uniform locations (pointers into the shader program) ---
    // Like CSS custom property handles — you write values into these slots each frame
    private var uTextureLocation = 0
    private var uTimeLocation = 0
    private var uIntensityLocation = 0
    private var uResolutionLocation = 0

    // --- State ---
    private var glitchIntensity = 0.3f   // 0.0 = no glitch, 1.0 = maximum chaos
    private var elapsedTime = 0f         // Increments each frame, drives animation
    private var surfaceWidth = 1f
    private var surfaceHeight = 1f
    private var pendingBitmap: Bitmap? = null  // Queued from UI thread
    private var bitmapDirty = false            // Flag: upload new texture this frame

    // --- Vertex data for a full-screen quad ---
    // Two triangles that together cover the entire screen (-1..1 in both axes = NDC)
    // Each row: X, Y (position)  U, V (texture coordinate)
    // Web analogy: the geometry a WebGL program uses to "blit" a texture to canvas
    private val quadVertices = floatArrayOf(
        // X      Y     U     V
        -1f,  -1f,  0f,  1f,   // bottom-left
         1f,  -1f,  1f,  1f,   // bottom-right
        -1f,   1f,  0f,  0f,   // top-left
         1f,   1f,  1f,  0f,   // top-right
    )
    private lateinit var vertexBuffer: FloatBuffer

    // ------------------------------------------------------------------
    // Lifecycle: onSurfaceCreated
    // ------------------------------------------------------------------
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Set clear colour (shown when no texture loaded yet)
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1f)

        // Upload vertex data to the GPU
        vertexBuffer = ByteBuffer
            .allocateDirect(quadVertices.size * 4)  // 4 bytes per float
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(quadVertices)
                position(0)
            }

        // Compile vertex + fragment shaders and link into a program
        programHandle = buildShaderProgram(VERTEX_SHADER_SRC, FRAGMENT_SHADER_SRC)

        // Look up uniform locations once (cheap, done at init not per-frame)
        uTextureLocation    = GLES20.glGetUniformLocation(programHandle, "uTexture")
        uTimeLocation       = GLES20.glGetUniformLocation(programHandle, "uTime")
        uIntensityLocation  = GLES20.glGetUniformLocation(programHandle, "uIntensity")
        uResolutionLocation = GLES20.glGetUniformLocation(programHandle, "uResolution")

        // Generate a texture slot on the GPU
        val texHandles = IntArray(1)
        GLES20.glGenTextures(1, texHandles, 0)
        textureHandle = texHandles[0]
    }

    // ------------------------------------------------------------------
    // Lifecycle: onSurfaceChanged (called on first creation + every resize)
    // ------------------------------------------------------------------
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        surfaceWidth = width.toFloat()
        surfaceHeight = height.toFloat()
    }

    // ------------------------------------------------------------------
    // Lifecycle: onDrawFrame — called every frame on the GL thread
    // ------------------------------------------------------------------
    override fun onDrawFrame(gl: GL10?) {
        // If a new bitmap was queued from the UI thread, upload it now
        if (bitmapDirty) {
            pendingBitmap?.let { uploadTexture(it) }
            bitmapDirty = false
        }

        elapsedTime += 0.016f   // ~60fps tick. Real apps use actual delta time.

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // Activate our shader program (like gl.useProgram() in WebGL)
        GLES20.glUseProgram(programHandle)

        // Bind the image texture to texture unit 0
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle)
        GLES20.glUniform1i(uTextureLocation, 0)

        // Upload uniform values (shader "parameters") for this frame
        GLES20.glUniform1f(uTimeLocation, elapsedTime)
        GLES20.glUniform1f(uIntensityLocation, glitchIntensity)
        GLES20.glUniform2f(uResolutionLocation, surfaceWidth, surfaceHeight)

        // Point the shader's attribute slots at our vertex buffer data
        // Stride = 4 floats per vertex * 4 bytes = 16 bytes
        val stride = 4 * 4

        val positionAttr = GLES20.glGetAttribLocation(programHandle, "aPosition")
        GLES20.glEnableVertexAttribArray(positionAttr)
        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(positionAttr, 2, GLES20.GL_FLOAT, false, stride, vertexBuffer)

        val texCoordAttr = GLES20.glGetAttribLocation(programHandle, "aTexCoord")
        GLES20.glEnableVertexAttribArray(texCoordAttr)
        vertexBuffer.position(2)  // UV starts at offset 2 floats in
        GLES20.glVertexAttribPointer(texCoordAttr, 2, GLES20.GL_FLOAT, false, stride, vertexBuffer)

        // Draw the quad as a triangle strip (4 vertices = 2 triangles = full screen rect)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionAttr)
        GLES20.glDisableVertexAttribArray(texCoordAttr)
    }

    // ------------------------------------------------------------------
    // Public API (called from UI thread via queueEvent)
    // ------------------------------------------------------------------

    fun loadBitmap(bitmap: Bitmap) {
        pendingBitmap = bitmap
        bitmapDirty = true
    }

    fun setGlitchIntensity(intensity: Float) {
        glitchIntensity = intensity
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Upload a Bitmap to the currently bound GL texture.
     * GLUtils.texImage2D handles the pixel format conversion for us.
     */
    private fun uploadTexture(bitmap: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle)
        // Filtering: how to sample when the texture is scaled
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        // Wrapping: what happens when UV coords go outside 0..1 (clamp, not repeat)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        // Upload pixels from Bitmap to GPU memory
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    /**
     * Compile a shader source string into a GL shader object.
     * Like compiling a single JS module before linking.
     */
    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)

        // Check for compile errors (like a syntax error in your shader code)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compile error: $log")
        }
        return shader
    }

    /**
     * Link vertex + fragment shaders into a complete shader program.
     * Like bundling two JS modules into a final executable bundle.
     */
    private fun buildShaderProgram(vertSrc: String, fragSrc: String): Int {
        val vertShader = compileShader(GLES20.GL_VERTEX_SHADER, vertSrc)
        val fragShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragSrc)

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertShader)
        GLES20.glAttachShader(program, fragShader)
        GLES20.glLinkProgram(program)

        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("Program link error: $log")
        }

        // Shaders are compiled into the program — individual objects no longer needed
        GLES20.glDeleteShader(vertShader)
        GLES20.glDeleteShader(fragShader)

        return program
    }

    // ------------------------------------------------------------------
    // Shader source code (GLSL — OpenGL Shading Language)
    // ------------------------------------------------------------------

    companion object {

        /**
         * VERTEX SHADER
         *
         * Runs once per vertex (4 times for our quad).
         * Its only job: set gl_Position (where on screen this vertex lands).
         * Also passes UV texture coordinates through to the fragment shader.
         *
         * Web analogy: this is like setting element positions in CSS —
         * it determines geometry, not colour.
         */
        private val VERTEX_SHADER_SRC = """
            attribute vec2 aPosition;   // Input: vertex XY from our buffer
            attribute vec2 aTexCoord;   // Input: UV coordinate from our buffer
            varying vec2 vTexCoord;     // Output: passed to fragment shader

            void main() {
                vTexCoord = aTexCoord;
                // gl_Position expects a vec4 (X, Y, Z, W). Z=0 (flat), W=1 (no perspective)
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """.trimIndent()

        /**
         * FRAGMENT SHADER
         *
         * Runs once per PIXEL across the entire quad — this is where the magic happens.
         * Every pixel can read from the texture at any UV coordinate,
         * enabling distortion, channel shifting, and other effects.
         *
         * Web analogy: like a CSS filter or Canvas pixel manipulation,
         * but running on every pixel in parallel on the GPU.
         *
         * Effects implemented:
         *  1. RGB channel split  — shifts R/B channels horizontally by different amounts
         *  2. Scanline glitch    — randomly displaces horizontal bands of pixels
         *  3. Block corruption   — replaces chunks of pixels with a shifted sample
         *  4. CRT scanlines      — subtle dark lines for retro feel
         *  5. Noise vignette     — darkens edges slightly
         */
        private val FRAGMENT_SHADER_SRC = """
            precision mediump float;

            uniform sampler2D uTexture;    // The image (uploaded from Bitmap)
            uniform float uTime;           // Elapsed time in seconds (drives animation)
            uniform float uIntensity;      // 0.0 = clean, 1.0 = full glitch
            uniform vec2 uResolution;      // Viewport size in pixels

            varying vec2 vTexCoord;        // UV from vertex shader (0..1 range)

            // --- Utility: pseudo-random number from a 2D seed ---
            // Classic hash function used in shader programming.
            // Web analogy: Math.random() but deterministic per-pixel.
            float rand(vec2 co) {
                return fract(sin(dot(co.xy, vec2(12.9898, 78.233))) * 43758.5453);
            }

            void main() {
                vec2 uv = vTexCoord;

                // -------------------------------------------------------
                // 1. SCANLINE GLITCH
                // Divide the image into horizontal bands. Some bands get
                // their UV shifted left/right based on time + randomness.
                // -------------------------------------------------------
                float bandHeight = 0.05 + rand(vec2(uTime * 0.1, 0.0)) * 0.1;
                float band = floor(uv.y / bandHeight);
                float bandRand = rand(vec2(band, floor(uTime * 8.0)));

                // Only shift bands above a threshold (sparse glitching)
                float shift = 0.0;
                if (bandRand > (1.0 - uIntensity * 0.6)) {
                    shift = (bandRand - 0.5) * 0.15 * uIntensity;
                }
                vec2 shiftedUV = vec2(uv.x + shift, uv.y);

                // -------------------------------------------------------
                // 2. BLOCK CORRUPTION
                // Large rectangular regions get their UV replaced with a
                // sample from a completely different part of the image.
                // -------------------------------------------------------
                float blockRand = rand(vec2(floor(uv.x * 8.0), floor(uv.y * 12.0) + floor(uTime * 4.0)));
                if (blockRand > (1.0 - uIntensity * 0.3)) {
                    shiftedUV.x = fract(shiftedUV.x + blockRand * 0.4);
                    shiftedUV.y = fract(shiftedUV.y + blockRand * 0.2);
                }

                // -------------------------------------------------------
                // 3. RGB CHANNEL SPLIT (chromatic aberration)
                // Sample R, G, B channels from slightly different UV coords.
                // Mimics lens chromatic aberration or CRT misalignment.
                // -------------------------------------------------------
                float chromaAmt = uIntensity * 0.015;
                float r = texture2D(uTexture, vec2(shiftedUV.x + chromaAmt, shiftedUV.y)).r;
                float g = texture2D(uTexture, shiftedUV).g;
                float b = texture2D(uTexture, vec2(shiftedUV.x - chromaAmt, shiftedUV.y)).b;

                vec3 color = vec3(r, g, b);

                // -------------------------------------------------------
                // 4. CRT SCANLINES
                // Multiply by a sine wave aligned to pixel rows.
                // Creates subtle dark horizontal lines.
                // -------------------------------------------------------
                float scanline = sin(uv.y * uResolution.y * 3.14159) * 0.04 * uIntensity;
                color -= scanline;

                // -------------------------------------------------------
                // 5. NOISE FLASH
                // Occasionally flash a pixel to white or a bright colour.
                // -------------------------------------------------------
                float noiseFlash = rand(vec2(uv.x + uTime * 0.3, uv.y + uTime * 0.17));
                if (noiseFlash > (1.0 - uIntensity * 0.05)) {
                    color = vec3(noiseFlash, 0.2, 1.0 - noiseFlash);
                }

                // -------------------------------------------------------
                // 6. VIGNETTE
                // Darken the edges of the screen. Always applied, subtle.
                // -------------------------------------------------------
                vec2 vigUV = uv * (1.0 - uv.yx);
                float vignette = vigUV.x * vigUV.y * 15.0;
                vignette = pow(vignette, 0.25);
                color *= vignette;

                gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
            }
        """.trimIndent()
    }
}
