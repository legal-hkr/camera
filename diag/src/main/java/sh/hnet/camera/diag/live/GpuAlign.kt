package sh.hnet.camera.diag.live

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.media.Image
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import android.util.Size
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import sh.hnet.camera.capabilities.sizeText

/**
 * The GPU's part of the app's own processing measured (LOGIKA §33, §36.3, D23: OpenGL ES 3.1 compute): two RAW frames taken one after the other, then on the
 * GPU each made a half-size gray picture (2 × 2 Bayer cells), a four-level pyramid, and the second aligned to the first tile by tile from the coarsest level
 * to the finest (16 × 16 tiles, ±4 at each level, the least absolute difference) – the steps of HDR+'s alignment. Each step's time (median of five runs) and
 * how far the tiles moved. No files.
 */
object GpuAlign {
    private const val TILE = 16
    private const val RADIUS = 4
    private const val LEVELS = 4
    private const val RUNS = 5

    suspend fun run(manager: CameraManager, preview: PreviewHost, id: String): JsonObject {
        val chars = manager.getCameraCharacteristics(id)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val raw = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height } ?: return buildJsonObject { put("skipped", "RAW_SENSOR not offered") }
        val frames = capture(manager, preview, id, raw, map.getOutputSizes(android.graphics.SurfaceTexture::class.java))
        val executor = Executors.newSingleThreadExecutor()
        return try {
            withContext(executor.asCoroutineDispatcher()) { measure(raw, frames) }
        } finally {
            executor.shutdown()
        }
    }

    /** Two RAW frames of a running preview, as 16-bit samples row after row. */
    private suspend fun capture(manager: CameraManager, preview: PreviewHost, id: String, raw: Size, previewSizes: Array<Size>?): List<ByteBuffer> {
        val previewSize = Size(1920, 1080).takeIf { previewSizes?.contains(it) == true } ?: Size(1280, 720)
        val surface = preview.surface(previewSize)
        return manager.withCamera(id) { device, thread ->
            ImageQueue(imageReader(raw.width, raw.height, ImageFormat.RAW_SENSOR, 3), thread).use { queue ->
                val session = device.session(listOf(OutputConfiguration(surface), OutputConfiguration(queue.reader.surface)), thread)
                try {
                    device.repeating(session, thread, listOf(surface))
                    delay(1500)
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surface)
                        addTarget(queue.reader.surface)
                        set(CaptureRequest.CONTROL_AE_LOCK, true)
                    }.build()
                    session.captureBurstRequests(listOf(request, request), thread.executor, noCallback)
                    List(2) { queue.next().use { packed(it) } }
                } finally {
                    session.close()
                }
            }
        }
    }

    private fun packed(image: Image): ByteBuffer {
        val plane = image.planes[0]
        val rowBytes = image.width * 2
        val out = ByteBuffer.allocateDirect(rowBytes * image.height).order(ByteOrder.nativeOrder())
        val row = ByteArray(rowBytes)
        for (y in 0 until image.height) {
            plane.buffer.position(y * plane.rowStride)
            plane.buffer.get(row)
            out.put(row)
        }
        out.flip()
        return out
    }

    private fun measure(raw: Size, frames: List<ByteBuffer>): JsonObject {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        EGL14.eglInitialize(display, IntArray(1), 0, IntArray(1), 0)
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val found = IntArray(1)
        EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE), 0, configs, 0, 1, found, 0)
        val config = configs[0] ?: return buildJsonObject { put("skipped", "no OpenGL ES 3 configuration") }
        val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        val surface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        try {
            EGL14.eglMakeCurrent(display, surface, surface, context)
            val version = GLES20.glGetString(GLES20.GL_VERSION) ?: ""
            if (!version.contains("OpenGL ES 3.1") && !version.contains("OpenGL ES 3.2")) return buildJsonObject { put("skipped", "no OpenGL ES 3.1 ($version)") }
            return buildJsonObject {
                put("gl", version)
                put("renderer", GLES20.glGetString(GLES20.GL_RENDERER) ?: "")
                put("raw", sizeText(raw))
                putAll(Pipeline(raw.width, raw.height).use { it.run(frames) })
            }
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putAll(from: JsonObject) = from.forEach { (k, v) -> put(k, v) }

    /** The textures and programs, made once and run [RUNS] times. */
    private class Pipeline(val width: Int, val height: Int) : AutoCloseable {
        private val textures = mutableListOf<Int>()
        private val programs = mutableListOf<Int>()
        private val gray = program(GRAY)
        private val down = program(DOWN)
        private val align = program(ALIGN)
        private val raws = List(2) { texture(width, height, GLES30.GL_R16UI) }
        private val sizes = List(LEVELS) { level -> (width / 2 shr level) to (height / 2 shr level) }
        private val pyramids = List(2) { sizes.map { (w, h) -> texture(w, h, GLES30.GL_R32F) } }
        private val tiles = sizes.map { (w, h) -> (w + TILE - 1) / TILE to (h + TILE - 1) / TILE }
        private val offsets = tiles.map { (w, h) -> texture(w, h, GLES30.GL_RGBA32I) }
        private val noPrior = texture(1, 1, GLES30.GL_RGBA32I)

        fun run(frames: List<ByteBuffer>): JsonObject {
            val times = mutableMapOf<String, MutableList<Double>>()
            fun timed(name: String, block: () -> Unit) {
                val start = System.nanoTime()
                block()
                GLES20.glFinish()
                times.getOrPut(name) { mutableListOf() } += (System.nanoTime() - start) / 1e6
            }
            repeat(RUNS) {
                timed("uploadMs") {
                    for (i in 0 until 2) {
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, raws[i])
                        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 2)
                        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height, GLES30.GL_RED_INTEGER, GLES20.GL_UNSIGNED_SHORT, frames[i].position(0))
                    }
                }
                timed("grayMs") {
                    GLES20.glUseProgram(gray)
                    for (i in 0 until 2) {
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, raws[i])
                        GLES31.glBindImageTexture(1, pyramids[i][0], 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_R32F)
                        dispatch(sizes[0].first, sizes[0].second)
                    }
                    GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
                }
                timed("pyramidMs") {
                    GLES20.glUseProgram(down)
                    for (level in 1 until LEVELS) for (i in 0 until 2) {
                        GLES31.glBindImageTexture(0, pyramids[i][level - 1], 0, false, 0, GLES31.GL_READ_ONLY, GLES30.GL_R32F)
                        GLES31.glBindImageTexture(1, pyramids[i][level], 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_R32F)
                        dispatch(sizes[level].first, sizes[level].second)
                        GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
                    }
                }
                timed("alignMs") {
                    GLES20.glUseProgram(align)
                    GLES20.glUniform1i(GLES20.glGetUniformLocation(align, "tile"), TILE)
                    GLES20.glUniform1i(GLES20.glGetUniformLocation(align, "radius"), RADIUS)
                    for (level in LEVELS - 1 downTo 0) {
                        val coarsest = level == LEVELS - 1
                        GLES20.glUniform1i(GLES20.glGetUniformLocation(align, "hasPrior"), if (coarsest) 0 else 1)
                        GLES31.glBindImageTexture(0, pyramids[0][level], 0, false, 0, GLES31.GL_READ_ONLY, GLES30.GL_R32F)
                        GLES31.glBindImageTexture(1, pyramids[1][level], 0, false, 0, GLES31.GL_READ_ONLY, GLES30.GL_R32F)
                        GLES31.glBindImageTexture(2, if (coarsest) noPrior else offsets[level + 1], 0, false, 0, GLES31.GL_READ_ONLY, GLES30.GL_RGBA32I)
                        GLES31.glBindImageTexture(3, offsets[level], 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA32I)
                        dispatch(tiles[level].first, tiles[level].second)
                        GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
                    }
                }
            }
            // How far the finest level's tiles moved (read back through a framebuffer)
            val (tw, th) = tiles[0]
            val moved = readOffsets(offsets[0], tw, th)
            val distances = moved.map { (x, y) -> abs(x) + abs(y) }.sorted()
            return buildJsonObject {
                put("levels", LEVELS)
                put("tilesFinest", "${tw}x$th")
                for ((name, list) in times) put(name, list.sorted()[list.size / 2])
                put("totalMs", times.values.sumOf { it.sorted()[it.size / 2] })
                put("medianShiftPx", distances.getOrElse(distances.size / 2) { 0 })
                put("maxShiftPx", distances.lastOrNull() ?: 0)
                put("glError", GLES20.glGetError())
            }
        }

        private fun readOffsets(texture: Int, w: Int, h: Int): List<Pair<Int, Int>> {
            val framebuffer = IntArray(1)
            GLES20.glGenFramebuffers(1, framebuffer, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer[0])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0)
            val out = ByteBuffer.allocateDirect(w * h * 4 * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, w, h, GLES30.GL_RGBA_INTEGER, GLES20.GL_INT, out)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glDeleteFramebuffers(1, framebuffer, 0)
            val ints = out.asIntBuffer()
            return List(w * h) { ints.get(it * 4) to ints.get(it * 4 + 1) }
        }

        private fun dispatch(w: Int, h: Int) = GLES31.glDispatchCompute((w + 7) / 8, (h + 7) / 8, 1)

        private fun texture(w: Int, h: Int, format: Int): Int {
            val id = IntArray(1)
            GLES20.glGenTextures(1, id, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0])
            GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, format, w, h)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            textures += id[0]
            return id[0]
        }

        private fun program(source: String): Int {
            val shader = GLES20.glCreateShader(GLES31.GL_COMPUTE_SHADER)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: " + GLES20.glGetShaderInfoLog(shader) }
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, shader)
            GLES20.glLinkProgram(program)
            GLES20.glDeleteShader(shader)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { "program: " + GLES20.glGetProgramInfoLog(program) }
            programs += program
            return program
        }

        override fun close() {
            GLES20.glDeleteTextures(textures.size, textures.toIntArray(), 0)
            programs.forEach { GLES20.glDeleteProgram(it) }
        }
    }

    /** A Bayer RAW frame to a half-size gray picture: the mean of each 2 × 2 cell. */
    private const val GRAY = """#version 310 es
layout(local_size_x = 8, local_size_y = 8) in;
layout(binding = 0) uniform highp usampler2D raw;
layout(r32f, binding = 1) writeonly uniform highp image2D dst;
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    ivec2 s = imageSize(dst);
    if (p.x >= s.x || p.y >= s.y) return;
    ivec2 q = p * 2;
    uint sum = texelFetch(raw, q, 0).r + texelFetch(raw, q + ivec2(1, 0), 0).r + texelFetch(raw, q + ivec2(0, 1), 0).r + texelFetch(raw, q + ivec2(1, 1), 0).r;
    imageStore(dst, p, vec4(float(sum) * 0.25));
}
"""

    /** One pyramid level from the one below: the mean of each 2 × 2. */
    private const val DOWN = """#version 310 es
layout(local_size_x = 8, local_size_y = 8) in;
layout(r32f, binding = 0) readonly uniform highp image2D src;
layout(r32f, binding = 1) writeonly uniform highp image2D dst;
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    ivec2 s = imageSize(dst);
    if (p.x >= s.x || p.y >= s.y) return;
    ivec2 q = p * 2;
    float v = imageLoad(src, q).r + imageLoad(src, q + ivec2(1, 0)).r + imageLoad(src, q + ivec2(0, 1)).r + imageLoad(src, q + ivec2(1, 1)).r;
    imageStore(dst, p, vec4(v * 0.25));
}
"""

    /** One tile per invocation: the shift of the other frame's tile, within ±radius of the coarser level's (doubled), with the least absolute difference. */
    private const val ALIGN = """#version 310 es
layout(local_size_x = 8, local_size_y = 8) in;
layout(r32f, binding = 0) readonly uniform highp image2D ref;
layout(r32f, binding = 1) readonly uniform highp image2D alt;
layout(rgba32i, binding = 2) readonly uniform highp iimage2D prior;
layout(rgba32i, binding = 3) writeonly uniform highp iimage2D dst;
uniform int tile;
uniform int radius;
uniform int hasPrior;
void main() {
    ivec2 t = ivec2(gl_GlobalInvocationID.xy);
    ivec2 n = imageSize(dst);
    if (t.x >= n.x || t.y >= n.y) return;
    ivec2 size = imageSize(ref);
    ivec2 start = hasPrior != 0 ? imageLoad(prior, min(t / 2, imageSize(prior) - 1)).xy * 2 : ivec2(0);
    ivec2 origin = t * tile;
    float best = 3.4e38;
    ivec2 bestShift = start;
    for (int dy = -radius; dy <= radius; dy++) for (int dx = -radius; dx <= radius; dx++) {
        ivec2 shift = start + ivec2(dx, dy);
        float sum = 0.0;
        for (int y = 0; y < tile; y++) for (int x = 0; x < tile; x++) {
            ivec2 a = min(origin + ivec2(x, y), size - 1);
            ivec2 b = clamp(a + shift, ivec2(0), size - 1);
            sum += abs(imageLoad(ref, a).r - imageLoad(alt, b).r);
        }
        if (sum < best) {
            best = sum;
            bestShift = shift;
        }
    }
    imageStore(dst, t, ivec4(bestShift, 0, 0));
}
"""
}
