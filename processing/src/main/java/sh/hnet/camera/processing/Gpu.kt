package sh.hnet.camera.processing

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * An OpenGL ES 3.1 context of its own (no window: a 1 × 1 pbuffer) for compute shaders, used from one thread only – the one that made it. Programs are
 * compiled once and kept; textures are made and deleted by the caller ([Texture.close]). With [share], its textures are the other context's too (a series'
 * frames uploaded on one thread while another develops); what one context wrote is seen by the other once it has finished ([finish]).
 */
internal class Gpu(share: Gpu? = null) : AutoCloseable {
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context: android.opengl.EGLContext
    private val surface: android.opengl.EGLSurface
    private val programs = HashMap<String, Int>()

    /** Every texture made and not yet closed: a development that fails half-way closes what it left ([closeAll]). A shared one may be closed on the other thread. */
    private val live = LinkedHashSet<Texture>()
    private val forget: (Texture) -> Unit = { synchronized(live) { live -= it } }

    private fun keep(texture: Texture) = texture.also { synchronized(live) { live += it } }

    init {
        check(EGL14.eglInitialize(display, IntArray(1), 0, IntArray(1), 0)) { "EGL not initialized" }
        var made: android.opengl.EGLContext = EGL14.EGL_NO_CONTEXT
        var pbuffer: android.opengl.EGLSurface = EGL14.EGL_NO_SURFACE
        try {
            val configs = arrayOfNulls<EGLConfig>(1)
            val found = IntArray(1)
            EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE), 0, configs, 0, 1, found, 0)
            val config = checkNotNull(configs[0]) { "no OpenGL ES 3 configuration" }
            made = EGL14.eglCreateContext(display, config, share?.context ?: EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            check(made != EGL14.EGL_NO_CONTEXT) { "no EGL context (0x${Integer.toHexString(EGL14.eglGetError())})" }
            pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            check(pbuffer != EGL14.EGL_NO_SURFACE) { "no EGL surface (0x${Integer.toHexString(EGL14.eglGetError())})" }
            check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, made)) { "EGL context not made current" }
            val version = GLES20.glGetString(GLES20.GL_VERSION).orEmpty()
            check("OpenGL ES 3.1" in version || "OpenGL ES 3.2" in version) { "no OpenGL ES 3.1 ($version)" }
        } catch (e: Exception) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
            if (made != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, made)
            EGL14.eglTerminate(display)
            throw e
        }
        context = made
        surface = pbuffer
    }

    val renderer: String get() = GLES20.glGetString(GLES20.GL_RENDERER).orEmpty()

    /** A texture of [format] (sized internal format); single-level, nearest sampling. */
    fun texture(width: Int, height: Int, format: Int): Texture {
        val id = IntArray(1)
        GLES20.glGenTextures(1, id, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0])
        GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, format, width, height)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return keep(Texture(id[0], width, height, format, onClose = forget))
    }

    /** An array of [layers] textures of one size (the frames of a series), each filled by [uploadLayer]. */
    fun textureArray(width: Int, height: Int, layers: Int, format: Int): Texture {
        val id = IntArray(1)
        GLES20.glGenTextures(1, id, 0)
        GLES20.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, id[0])
        GLES30.glTexStorage3D(GLES30.GL_TEXTURE_2D_ARRAY, 1, format, width, height, layers)
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        // Not left bound: a binding in this context would keep the array alive after the other context deleted it
        GLES20.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, 0)
        return keep(Texture(id[0], width, height, format, layers, GLES30.GL_TEXTURE_2D_ARRAY, onClose = forget))
    }

    /** Fills one layer from client memory; [rowLength] the source's row in pixels where its rows are padded (0: packed). */
    fun uploadLayer(texture: Texture, layer: Int, format: Int, type: Int, data: Buffer, alignment: Int = 4, rowLength: Int = 0) {
        GLES20.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, texture.id)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, alignment)
        GLES20.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, rowLength)
        GLES30.glTexSubImage3D(GLES30.GL_TEXTURE_2D_ARRAY, 0, 0, 0, layer, texture.width, texture.height, 1, format, type, data.position(0))
        GLES20.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0)
        GLES20.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, 0)
    }

    /** Closes every texture of this context still open (after a failed development). */
    fun closeAll() = synchronized(live) { live.toList() }.forEach { it.close() }

    /** The bytes the open textures take (for reports: how close a development comes to the phone's memory). */
    val liveBytes: Long get() = synchronized(live) { live.sumOf { it.width.toLong() * it.height * it.layers * bytesPerPixel(it.format) } }

    private fun bytesPerPixel(format: Int) = when (format) {
        GLES30.GL_R16UI -> 2
        GLES30.GL_R32F, GLES30.GL_RGBA8 -> 4
        GLES30.GL_RGBA16F -> 8
        else -> 16
    }

    /** Fills a texture from client memory ([format]/[type] as glTexSubImage2D takes them; rows tightly packed). */
    fun upload(texture: Texture, format: Int, type: Int, data: Buffer, alignment: Int = 4) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.id)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, alignment)
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, texture.width, texture.height, format, type, data.position(0))
    }

    /**
     * Runs a compute program over [width] × [height] invocations (8 × 8 groups): [samplers] bound to texture units in order (the shader's `binding`s
     * follow the same order), [images] to image units (written or read as their format), then [uniforms] set while the program is in use.
     */
    fun run(
        source: String,
        width: Int,
        height: Int,
        samplers: List<Texture> = emptyList(),
        images: List<Texture> = emptyList(),
        uniforms: Uniforms.() -> Unit = {},
    ) {
        val program = program(source)
        GLES20.glUseProgram(program)
        samplers.forEachIndexed { unit, texture ->
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
            GLES20.glBindTexture(texture.target, texture.id)
        }
        images.forEachIndexed { unit, texture -> GLES31.glBindImageTexture(unit, texture.id, 0, false, 0, GLES31.GL_READ_WRITE, texture.format) }
        Uniforms(program).uniforms()
        GLES31.glDispatchCompute((width + 7) / 8, (height + 7) / 8, 1)
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GLES31.GL_TEXTURE_FETCH_BARRIER_BIT or GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
    }

    /** Runs a program in [groupsX] × [groupsY] work groups (the program's own group size, e.g. one 16 × 16 group per tile). */
    fun runGroups(source: String, groupsX: Int, groupsY: Int, samplers: List<Texture>, images: List<Texture>, uniforms: Uniforms.() -> Unit = {}) {
        val program = program(source)
        GLES20.glUseProgram(program)
        samplers.forEachIndexed { unit, texture ->
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
            GLES20.glBindTexture(texture.target, texture.id)
        }
        images.forEachIndexed { unit, texture -> GLES31.glBindImageTexture(unit, texture.id, 0, false, 0, GLES31.GL_READ_WRITE, texture.format) }
        Uniforms(program).uniforms()
        GLES31.glDispatchCompute(groupsX, groupsY, 1)
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)
    }

    /** Runs a program with one invocation per row or column (a running sum along it): [count] invocations in groups of 64. */
    fun runLines(source: String, count: Int, samplers: List<Texture>, images: List<Texture>, uniforms: Uniforms.() -> Unit = {}) {
        val program = program(source)
        GLES20.glUseProgram(program)
        samplers.forEachIndexed { unit, texture ->
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
            GLES20.glBindTexture(texture.target, texture.id)
        }
        images.forEachIndexed { unit, texture -> GLES31.glBindImageTexture(unit, texture.id, 0, false, 0, GLES31.GL_READ_WRITE, texture.format) }
        Uniforms(program).uniforms()
        GLES31.glDispatchCompute((count + 63) / 64, 1, 1)
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)
    }

    /** A small float texture's values (up to four channels, [channels] of them kept) read back through a storage buffer, row by row. */
    fun read(texture: Texture, channels: Int): FloatArray {
        val count = texture.width * texture.height
        val buffer = IntArray(1)
        GLES20.glGenBuffers(1, buffer, 0)
        try {
            GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffer[0])
            GLES20.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, count * 16, null, GLES30.GL_STREAM_READ)
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, buffer[0])
            run(Shaders.READ_BACK, texture.width, texture.height, samplers = listOf(texture))
            // The shader's writes visible to the mapping (a shader-storage barrier only covers later shaders)
            GLES31.glMemoryBarrier(GLES31.GL_BUFFER_UPDATE_BARRIER_BIT)
            GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffer[0])
            val mapped = GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, count * 16, GLES30.GL_MAP_READ_BIT) as ByteBuffer
            val floats = mapped.order(ByteOrder.nativeOrder()).asFloatBuffer()
            val out = FloatArray(count * channels)
            if (channels == 4) {
                floats.get(out)
            } else {
                val all = FloatArray(count * 4).also { floats.get(it) }
                for (i in 0 until count) for (c in 0 until channels) out[i * channels + c] = all[i * 4 + c]
            }
            GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
            return out
        } finally {
            GLES20.glDeleteBuffers(1, buffer, 0)
        }
    }

    /** An RGBA8 texture's bytes (row by row, top row first as the picture's) through a framebuffer. */
    fun readRgba8(texture: Texture): ByteBuffer {
        // The image stores visible to glReadPixels
        GLES31.glMemoryBarrier(GLES31.GL_FRAMEBUFFER_BARRIER_BIT or GLES31.GL_PIXEL_BUFFER_BARRIER_BIT)
        val framebuffer = IntArray(1)
        GLES20.glGenFramebuffers(1, framebuffer, 0)
        try {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer[0])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture.id, 0)
            val out = ByteBuffer.allocateDirect(texture.width * texture.height * 4).order(ByteOrder.nativeOrder())
            GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 4)
            GLES20.glReadPixels(0, 0, texture.width, texture.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, out)
            return out
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glDeleteFramebuffers(1, framebuffer, 0)
        }
    }

    /** Waits until the GPU has done everything asked so far (for timing). */
    fun finish() = GLES20.glFinish()

    fun error(): Int = GLES20.glGetError()

    private fun program(source: String): Int = programs.getOrPut(source) {
        val shader = GLES20.glCreateShader(GLES31.GL_COMPUTE_SHADER)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("shader: $log\n${source.lineSequence().take(3).joinToString(" ")}")
        }
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, shader)
        GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(shader)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "program: " + GLES20.glGetProgramInfoLog(program) }
        program
    }

    override fun close() {
        closeAll()
        programs.values.forEach { GLES20.glDeleteProgram(it) }
        programs.clear()
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
    }
}

/** A GPU texture (or an array of [layers]); deleted by [close] (once). */
internal class Texture(
    val id: Int,
    val width: Int,
    val height: Int,
    val format: Int,
    val layers: Int = 1,
    val target: Int = GLES20.GL_TEXTURE_2D,
    private val onClose: (Texture) -> Unit,
) : AutoCloseable {
    @Volatile
    var closed = false
        private set

    override fun close() {
        if (closed) return
        closed = true
        GLES20.glDeleteTextures(1, intArrayOf(id), 0)
        onClose(this)
    }
}

/** Sets a program's uniforms by name. */
internal class Uniforms(private val program: Int) {
    private fun at(name: String) = GLES20.glGetUniformLocation(program, name)

    fun int(name: String, value: Int) = GLES20.glUniform1i(at(name), value)

    fun ints(name: String, vararg values: Int) = when (values.size) {
        2 -> GLES20.glUniform2i(at(name), values[0], values[1])
        4 -> GLES20.glUniform4i(at(name), values[0], values[1], values[2], values[3])
        else -> GLES20.glUniform1iv(at(name), values.size, values, 0)
    }

    fun float(name: String, value: Float) = GLES20.glUniform1f(at(name), value)

    fun vec(name: String, vararg values: Float) = when (values.size) {
        2 -> GLES20.glUniform2f(at(name), values[0], values[1])
        3 -> GLES20.glUniform3f(at(name), values[0], values[1], values[2])
        4 -> GLES20.glUniform4f(at(name), values[0], values[1], values[2], values[3])
        else -> error("vec of ${values.size}")
    }

    fun floats(name: String, values: FloatArray) = GLES20.glUniform1fv(at(name), values.size, values, 0)

    /** An array of vec4 given as 4 × n floats. */
    fun vec4s(name: String, values: FloatArray) = GLES20.glUniform4fv(at(name), values.size / 4, values, 0)

    /** A 3 × 3 matrix given row by row. */
    fun mat3(name: String, rows: FloatArray) = GLES20.glUniformMatrix3fv(at(name), 1, true, rows, 0)
}
