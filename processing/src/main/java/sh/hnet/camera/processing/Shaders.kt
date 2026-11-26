package sh.hnet.camera.processing

/**
 * The development's compute programs (GLSL ES 3.1). Each follows one step of the computer prototype (`develop.py`) and the OpenCV call it makes, border
 * handling included (OpenCV's default: mirrored without repeating the edge), so the two pictures can be compared pixel by pixel. Images are written
 * through image units (`r32f`, `rgba16f`, `rgba32f`, `rgba8` – the formats ES 3.1 can store) and read through samplers with `texelFetch`.
 */
internal object Shaders {
    private const val HEAD = """#version 310 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp usampler2D;
const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
int mirror(int i, int n) {
    if (n == 1) return 0;
    if (i < 0) i = -i;
    if (i >= n) i = 2 * (n - 1) - i;
    return clamp(i, 0, n - 1);
}
float srgb(float x) {
    x = clamp(x, 0.0, 1.0);
    return x <= 0.0031308 ? 12.92 * x : 1.055 * pow(x, 1.0 / 2.4) - 0.055;
}
float linearFromSrgb(float e) {
    return e <= 0.04045 ? e / 12.92 : pow((e + 0.055) / 1.055, 2.4);
}
"""

    private const val GRID = "layout(local_size_x = 8, local_size_y = 8) in;\n"

    /** Copies a texture's texels into a storage buffer (for reading back). */
    const val READ_BACK = HEAD + GRID + """
layout(binding = 0) uniform sampler2D src;
layout(std430, binding = 0) writeonly buffer Out { vec4 v[]; } outs;
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    ivec2 s = textureSize(src, 0);
    if (p.x >= s.x || p.y >= s.y) return;
    outs.v[p.y * s.x + p.x] = texelFetch(src, p, 0);
}
"""

    /**
     * The raw samples to the white-balanced mosaic: black taken off, 0..1 by the white level, the lens shading gain of the sample's 2 × 2 position
     * (bilinear on its grid, as the DNG GainMap), each color by its balance, clipped at 1 where the least sensitive channel saturates.
     */
    const val PREPARE = HEAD + GRID + """
layout(binding = 0) uniform usampler2D raw;
layout(binding = 1) uniform sampler2D shading;
layout(r32f, binding = 0) writeonly uniform highp image2D dst;
uniform vec4 black;
uniform float range;
uniform ivec4 cfa;
uniform vec3 balance;
uniform int hasShading;
uniform ivec2 mapSize;
uniform ivec2 size;
float gainAt(int position, ivec2 p) {
    int dy = p.y & 1;
    int dx = p.x & 1;
    float gv = clamp(float(p.y - dy) / float(size.y - dy) * float(mapSize.y - 1), 0.0, float(mapSize.y - 1));
    float gu = clamp(float(p.x - dx) / float(size.x - dx) * float(mapSize.x - 1), 0.0, float(mapSize.x - 1));
    int v0 = int(floor(gv));
    int u0 = int(floor(gu));
    int v1 = min(v0 + 1, mapSize.y - 1);
    int u1 = min(u0 + 1, mapSize.x - 1);
    float fv = gv - float(v0);
    float fu = gu - float(u0);
    float a = mix(texelFetch(shading, ivec2(u0, v0), 0)[position], texelFetch(shading, ivec2(u1, v0), 0)[position], fu);
    float b = mix(texelFetch(shading, ivec2(u0, v1), 0)[position], texelFetch(shading, ivec2(u1, v1), 0)[position], fu);
    return mix(a, b, fv);
}
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= size.x || p.y >= size.y) return;
    int position = (p.y & 1) * 2 + (p.x & 1);
    float v = (float(texelFetch(raw, p, 0).r) - black[position]) / range;
    if (hasShading != 0) v *= gainAt(position, p);
    v *= balance[cfa[position]];
    imageStore(dst, p, vec4(min(v, 1.0)));
}
"""

    /**
     * Malvar–He–Cutler demosaicing (bilinear corrected by the other channels' gradient, 5 × 5 kernels, any 2 × 2 pattern), then the camera's RGB to
     * linear sRGB by [matrix], negatives cut.
     */
    const val DEMOSAIC = HEAD + GRID + """
layout(binding = 0) uniform sampler2D mosaic;
layout(rgba16f, binding = 0) writeonly uniform highp image2D dst;
uniform ivec4 cfa;
uniform mat3 matrix;
uniform ivec2 size;
float at(ivec2 p, int dx, int dy) {
    return texelFetch(mosaic, ivec2(mirror(p.x + dx, size.x), mirror(p.y + dy, size.y)), 0).r;
}
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= size.x || p.y >= size.y) return;
    float c = at(p, 0, 0);
    float n1 = at(p, 0, -1), s1 = at(p, 0, 1), w1 = at(p, -1, 0), e1 = at(p, 1, 0);
    float n2 = at(p, 0, -2), s2 = at(p, 0, 2), w2 = at(p, -2, 0), e2 = at(p, 2, 0);
    float d = at(p, -1, -1) + at(p, 1, -1) + at(p, -1, 1) + at(p, 1, 1);
    float gAtRb = (4.0 * c + 2.0 * (n1 + s1 + w1 + e1) - (n2 + s2 + w2 + e2)) / 8.0;
    float row = (5.0 * c + 4.0 * (w1 + e1) - (w2 + e2) - d + 0.5 * (n2 + s2)) / 8.0;
    float col = (5.0 * c + 4.0 * (n1 + s1) - (n2 + s2) - d + 0.5 * (w2 + e2)) / 8.0;
    float diag = (6.0 * c + 2.0 * d - 1.5 * (n2 + s2 + w2 + e2)) / 8.0;
    int dy = p.y & 1;
    int dx = p.x & 1;
    int here = cfa[dy * 2 + dx];
    vec3 rgb = vec3(0.0);
    rgb[here] = c;
    if (here == 1) {
        rgb[cfa[dy * 2 + (1 - dx)]] = row;
        rgb[cfa[(1 - dy) * 2 + dx]] = col;
    } else {
        rgb[1] = gAtRb;
        rgb[2 - here] = diag;
    }
    imageStore(dst, p, vec4(max(matrix * rgb, vec3(0.0)), 0.0));
}
"""

    /**
     * The lens distortion corrected (DNG WarpRectilinear, bicubic as OpenCV's remap: a = −0.75, exact positions, mirrored borders) and
     * the default crop cut, in one pass: each output pixel reads where the warp says in the full frame.
     */
    const val WARP = HEAD + GRID + """
layout(binding = 0) uniform sampler2D src;
layout(rgba16f, binding = 0) writeonly uniform highp image2D dst;
uniform ivec2 size;
uniform ivec2 outSize;
uniform ivec2 origin;
uniform int hasWarp;
uniform vec4 radial;
uniform vec2 tangential;
uniform vec2 center;
uniform float corner;
vec4 weights(float t) {
    const float A = -0.75;
    float w0 = ((A * (t + 1.0) - 5.0 * A) * (t + 1.0) + 8.0 * A) * (t + 1.0) - 4.0 * A;
    float w1 = ((A + 2.0) * t - (A + 3.0)) * t * t + 1.0;
    float w2 = ((A + 2.0) * (1.0 - t) - (A + 3.0)) * (1.0 - t) * (1.0 - t) + 1.0;
    return vec4(w0, w1, w2, 1.0 - w0 - w1 - w2);
}
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= outSize.x || p.y >= outSize.y) return;
    ivec2 q = p + origin;
    if (hasWarp == 0) {
        imageStore(dst, p, texelFetch(src, q, 0));
        return;
    }
    float x0 = center.x * float(size.x - 1);
    float y0 = center.y * float(size.y - 1);
    float dx = (float(q.x) - x0) / corner;
    float dy = (float(q.y) - y0) / corner;
    float r2 = dx * dx + dy * dy;
    float f = radial.x + r2 * (radial.y + r2 * (radial.z + r2 * radial.w));
    float sx = x0 + corner * (dx * f + tangential.x * 2.0 * dx * dy + tangential.y * (r2 + 2.0 * dx * dx));
    float sy = y0 + corner * (dy * f + tangential.y * 2.0 * dx * dy + tangential.x * (r2 + 2.0 * dy * dy));
    int ix = int(floor(sx));
    int iy = int(floor(sy));
    vec4 wx = weights(sx - float(ix));
    vec4 wy = weights(sy - float(iy));
    vec4 sum = vec4(0.0);
    for (int j = 0; j < 4; j++) {
        int y = mirror(iy - 1 + j, size.y);
        vec4 line = vec4(0.0);
        for (int i = 0; i < 4; i++) line += wx[i] * texelFetch(src, ivec2(mirror(ix - 1 + i, size.x), y), 0);
        sum += wy[j] * line;
    }
    imageStore(dst, p, sum);
}
"""

    /**
     * The mean over [factor] × [factor] blocks (OpenCV's area resize by a whole factor): of the luminance (≥ 0) of RGB, or of the first channel. OpenCV
     * weighs partly covered pixels when the size doesn't divide; the crops here (the pre-correction array less 16) divide on the phones we know.
     */
    const val DOWN_MEAN = HEAD + GRID + """
layout(binding = 0) uniform sampler2D src;
layout(rgba32f, binding = 0) writeonly uniform highp image2D dst;
uniform int factor;
uniform int luminance;
uniform ivec2 outSize;
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= outSize.x || p.y >= outSize.y) return;
    vec4 sum = vec4(0.0);
    for (int y = 0; y < factor; y++) for (int x = 0; x < factor; x++) {
        vec4 v = texelFetch(src, p * factor + ivec2(x, y), 0);
        sum += luminance != 0 ? vec4(max(dot(v.rgb, LUMA), 0.0)) : v;
    }
    imageStore(dst, p, sum / float(factor * factor));
}
"""

    /**
     * The fusion's inputs: up to three exposures of the luminance (from the highlights' gain up, sRGB-encoded) and their weights – where each neither
     * clips nor sinks into black, the brighter ones more – normalized to sum 1.
     */
    const val FUSE_INPUT = HEAD + GRID + """
layout(binding = 0) uniform sampler2D src;
layout(rgba16f, binding = 0) writeonly uniform highp image2D images;
layout(rgba16f, binding = 1) writeonly uniform highp image2D weights;
uniform float gain;
uniform vec3 exposures;
uniform int count;
uniform float bias;
uniform float high;
uniform float soft;
uniform ivec2 size;
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= size.x || p.y >= size.y) return;
    float lum = max(dot(texelFetch(src, p, 0).rgb * gain, LUMA), 1e-6);
    vec4 i = vec4(0.0);
    vec4 w = vec4(0.0);
    float total = 0.0;
    for (int k = 0; k < count; k++) {
        float e = srgb(lum * exposures[k]);
        float over = max(e - high, 0.0);
        float under = max(0.1 - e, 0.0);
        float weight = pow(bias, float(k)) * exp(-over * over / (2.0 * soft * soft)) * exp(-under * under / (2.0 * 0.05 * 0.05)) + 1e-6;
        i[k] = e;
        w[k] = weight;
        total += weight;
    }
    imageStore(images, p, i);
    imageStore(weights, p, w / total);
}
"""

    /** One level of a Gaussian pyramid from the one below (OpenCV's pyrDown: 5 × 5 binomial, size halved rounding up). */
    const val PYR_DOWN = HEAD + GRID + """
layout(binding = 0) uniform sampler2D src;
layout(rgba16f, binding = 0) writeonly uniform highp image2D dst;
uniform ivec2 srcSize;
uniform ivec2 outSize;
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= outSize.x || p.y >= outSize.y) return;
    const float k[5] = float[5](1.0, 4.0, 6.0, 4.0, 1.0);
    vec4 sum = vec4(0.0);
    for (int j = 0; j < 5; j++) {
        int y = mirror(p.y * 2 + j - 2, srcSize.y);
        vec4 line = vec4(0.0);
        for (int i = 0; i < 5; i++) line += k[i] * texelFetch(src, ivec2(mirror(p.x * 2 + i - 2, srcSize.x), y), 0);
        sum += k[j] * line;
    }
    imageStore(dst, p, sum / 256.0);
}
"""

    /**
     * OpenCV's pyrUp at one output pixel: along each axis an even output takes (1, 6, 1) / 8 of the source around it, an odd one (½, ½); the left and top
     * mirrored, the right and bottom repeating the last sample, as OpenCV does.
     */
    private const val UP = """
void taps(int x, int n, out ivec3 at, out vec3 w) {
    if (n == 1) { at = ivec3(0); w = vec3(1.0, 0.0, 0.0); return; }
    if ((x & 1) == 0) {
        int i = x / 2;
        at = ivec3(i - 1 < 0 ? 1 : i - 1, i, min(i + 1, n - 1));
        w = vec3(1.0, 6.0, 1.0) / 8.0;
    } else {
        int i = (x - 1) / 2;
        at = ivec3(i, min(i + 1, n - 1), i);
        w = vec3(0.5, 0.5, 0.0);
    }
}
vec4 up(sampler2D s, ivec2 p, ivec2 n) {
    ivec3 ax, ay;
    vec3 wx, wy;
    taps(p.x, n.x, ax, wx);
    taps(p.y, n.y, ay, wy);
    vec4 sum = vec4(0.0);
    for (int j = 0; j < 3; j++) {
        vec4 line = vec4(0.0);
        for (int i = 0; i < 3; i++) line += wx[i] * texelFetch(s, ivec2(ax[i], ay[j]), 0);
        sum += wy[j] * line;
    }
    return sum;
}
"""

    /** One level of the fused Laplacian pyramid: the exposures' Laplacian (this level minus the next one up-sampled) weighted by their weights. */
    const val LAPLACE_BLEND = HEAD + GRID + UP + """
layout(binding = 0) uniform sampler2D images;
layout(binding = 1) uniform sampler2D coarser;
layout(binding = 2) uniform sampler2D weights;
layout(r32f, binding = 0) writeonly uniform highp image2D dst;
uniform ivec2 size;
uniform ivec2 coarserSize;
uniform int top;
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= size.x || p.y >= size.y) return;
    vec4 i = texelFetch(images, p, 0);
    vec4 lap = top != 0 ? i : i - up(coarser, p, coarserSize);
    imageStore(dst, p, vec4(dot(lap.rgb, texelFetch(weights, p, 0).rgb)));
}
"""

    /** Collapsing the fused pyramid: this level's blend plus the next one's result up-sampled; the finest level clipped to 0..1. */
    const val COLLAPSE = HEAD + GRID + UP + """
layout(binding = 0) uniform sampler2D blend;
layout(binding = 1) uniform sampler2D coarser;
layout(r32f, binding = 0) writeonly uniform highp image2D dst;
uniform ivec2 size;
uniform ivec2 coarserSize;
uniform int finest;
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= size.x || p.y >= size.y) return;
    float v = texelFetch(blend, p, 0).r + up(coarser, p, coarserSize).r;
    imageStore(dst, p, vec4(finest != 0 ? clamp(v, 0.0, 1.0) : v));
}
"""

    /** The global curve: black point and stretch, the S curve, the mid-tones' lift; also (v, v²) for the local contrast's guided filter. */
    const val CURVE = HEAD + GRID + """
layout(binding = 0) uniform sampler2D fused;
layout(r32f, binding = 0) writeonly uniform highp image2D dst;
layout(rgba32f, binding = 1) writeonly uniform highp image2D moments;
uniform float black;
uniform float scale;
uniform float contrast;
uniform float midtones;
uniform ivec2 size;
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= size.x || p.y >= size.y) return;
    float v = clamp((texelFetch(fused, p, 0).r - black) * scale, 0.0, 1.0);
    v = v - contrast * sin(6.283185307179586 * v) / 6.283185307179586;
    v = v + midtones * v * (1.0 - v) * (1.0 - v);
    imageStore(dst, p, vec4(v));
    imageStore(moments, p, vec4(v, v * v, 0.0, 0.0));
}
"""

    /** A normalized box filter along rows (one invocation per row, a running sum), mirrored borders; four channels at once, written as [format]. */
    fun boxRows(format: String) = BOX_ROWS_TEMPLATE.replace("FORMAT", format)

    fun boxColumns(format: String) = BOX_COLUMNS_TEMPLATE.replace("FORMAT", format)

    /** A separable Gaussian blur along one axis, written as [format] (see [GAUSS_TEMPLATE]). */
    fun gauss(format: String) = GAUSS_TEMPLATE.replace("FORMAT", format)

    private const val BOX_ROWS_TEMPLATE = HEAD + """
layout(local_size_x = 64) in;
layout(binding = 0) uniform sampler2D src;
layout(FORMAT, binding = 0) writeonly uniform highp image2D dst;
uniform ivec2 size;
uniform int radius;
void main() {
    int y = int(gl_GlobalInvocationID.x);
    if (y >= size.y) return;
    vec4 sum = vec4(0.0);
    for (int k = -radius; k <= radius; k++) sum += texelFetch(src, ivec2(mirror(k, size.x), y), 0);
    float n = float(2 * radius + 1);
    for (int x = 0; x < size.x; x++) {
        imageStore(dst, ivec2(x, y), sum / n);
        sum += texelFetch(src, ivec2(mirror(x + radius + 1, size.x), y), 0) - texelFetch(src, ivec2(mirror(x - radius, size.x), y), 0);
    }
}
"""

    /** The same along columns. */
    private const val BOX_COLUMNS_TEMPLATE = HEAD + """
layout(local_size_x = 64) in;
layout(binding = 0) uniform sampler2D src;
layout(FORMAT, binding = 0) writeonly uniform highp image2D dst;
uniform ivec2 size;
uniform int radius;
void main() {
    int x = int(gl_GlobalInvocationID.x);
    if (x >= size.x) return;
    vec4 sum = vec4(0.0);
    for (int k = -radius; k <= radius; k++) sum += texelFetch(src, ivec2(x, mirror(k, size.y)), 0);
    float n = float(2 * radius + 1);
    for (int y = 0; y < size.y; y++) {
        imageStore(dst, ivec2(x, y), sum / n);
        sum += texelFetch(src, ivec2(x, mirror(y + radius + 1, size.y)), 0) - texelFetch(src, ivec2(x, mirror(y - radius, size.y)), 0);
    }
}
"""

    /** A separable Gaussian blur along one axis ([axis] 0 rows, 1 columns), up to 33 taps, mirrored borders; four channels at once. */
    private const val GAUSS_TEMPLATE = HEAD + GRID + """
layout(binding = 0) uniform sampler2D src;
layout(FORMAT, binding = 0) writeonly uniform highp image2D dst;
uniform ivec2 size;
uniform int axis;
uniform int taps;
uniform float kernel[33];
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= size.x || p.y >= size.y) return;
    int r = taps / 2;
    vec4 sum = vec4(0.0);
    for (int k = 0; k < taps; k++) {
        ivec2 q = axis == 0 ? ivec2(mirror(p.x + k - r, size.x), p.y) : ivec2(p.x, mirror(p.y + k - r, size.y));
        sum += kernel[k] * texelFetch(src, q, 0);
    }
    imageStore(dst, p, sum);
}
"""

    /** The guided filter's coefficients from the box means of (I, I²) (the image its own guide): a = var / (var + eps), b = mean − a·mean. */
    const val GUIDED_SELF = HEAD + GRID + """
layout(binding = 0) uniform sampler2D moments;
layout(rgba16f, binding = 0) writeonly uniform highp image2D dst;
uniform float eps;
uniform ivec2 size;
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= size.x || p.y >= size.y) return;
    vec4 m = texelFetch(moments, p, 0);
    float variance = m.y - m.x * m.x;
    float a = variance / (variance + eps);
    imageStore(dst, p, vec4(a, m.x - a * m.x, 0.0, 0.0));
}
"""

    /** Local contrast in the bright parts: the curve's value against its guided base (box means of a and b), added back as much as [clarity] says. */
    const val CLARITY = HEAD + GRID + """
layout(binding = 0) uniform sampler2D curve;
layout(binding = 1) uniform sampler2D coefficients;
layout(r32f, binding = 0) writeonly uniform highp image2D dst;
uniform float clarity;
uniform float low;
uniform ivec2 size;
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= size.x || p.y >= size.y) return;
    float v = texelFetch(curve, p, 0).r;
    vec4 ab = texelFetch(coefficients, p, 0);
    float base = ab.x * v + ab.y;
    imageStore(dst, p, vec4(v + clarity * (v - base) * clamp((v - low) / 0.2, 0.0, 1.0) * min(4.0 * (1.0 - v), 1.0)));
}
"""

    /**
     * The tone mapped picture: the color follows the luminance's ratio from the linear (highlights' gain) to the curve's value, sRGB-encoded; and each
     * pixel's relative noise from the sensor's profile at its raw level (the variance divided by the frames merged).
     */
    const val TONE_OUT = HEAD + GRID + """
layout(binding = 0) uniform sampler2D rgb;
layout(binding = 1) uniform sampler2D curve;
layout(rgba16f, binding = 0) writeonly uniform highp image2D display;
layout(r32f, binding = 1) writeonly uniform highp image2D noise;
uniform float gain;
uniform float noiseS;
uniform float noiseO;
uniform float frames;
uniform ivec2 size;
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= size.x || p.y >= size.y) return;
    vec3 c = texelFetch(rgb, p, 0).rgb;
    vec3 lin = c * gain;
    float lum = max(dot(lin, LUMA), 1e-6);
    vec3 out3 = lin * (linearFromSrgb(clamp(texelFetch(curve, p, 0).r, 0.0, 1.0)) / lum);
    imageStore(display, p, vec4(srgb(out3.r), srgb(out3.g), srgb(out3.b), 1.0));
    float level = max(dot(c, LUMA), 1e-5);
    imageStore(noise, p, vec4(sqrt((noiseS * level + noiseO) / frames) / level));
}
"""

    /**
     * The color's guided filter inputs at half size, from the display picture's 2 × 2 means: A = (I, I², R, G), B = (B, I·R, I·G, I·B), I the luma (at
     * least 1e-4); also the half-size picture's own ratios.
     */
    const val CHROMA_INPUT = HEAD + GRID + """
layout(binding = 0) uniform sampler2D display;
layout(rgba32f, binding = 0) writeonly uniform highp image2D outA;
layout(rgba32f, binding = 1) writeonly uniform highp image2D outB;
layout(rgba32f, binding = 2) writeonly uniform highp image2D ratios;
uniform ivec2 size;
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= size.x || p.y >= size.y) return;
    vec3 c = (texelFetch(display, p * 2, 0).rgb + texelFetch(display, p * 2 + ivec2(1, 0), 0).rgb
        + texelFetch(display, p * 2 + ivec2(0, 1), 0).rgb + texelFetch(display, p * 2 + ivec2(1, 1), 0).rgb) * 0.25;
    float i = max(dot(c, LUMA), 1e-4);
    imageStore(outA, p, vec4(i, i * i, c.r, c.g));
    imageStore(outB, p, vec4(c.b, i * c.r, i * c.g, i * c.b));
    imageStore(ratios, p, vec4(c / i, 0.0));
}
"""

    /** The guided filter's coefficients for the luma itself and the three channels, guided by the luma: a = cov / (var + eps), b = mean − a·mean(I). */
    const val CHROMA_COEFFICIENTS = HEAD + GRID + """
layout(binding = 0) uniform sampler2D meanA;
layout(binding = 1) uniform sampler2D meanB;
layout(rgba32f, binding = 0) writeonly uniform highp image2D outA;
layout(rgba32f, binding = 1) writeonly uniform highp image2D outB;
uniform float eps;
uniform ivec2 size;
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= size.x || p.y >= size.y) return;
    vec4 a = texelFetch(meanA, p, 0);
    vec4 b = texelFetch(meanB, p, 0);
    float mi = a.x;
    float denominator = a.y - mi * mi + eps;
    vec4 mp = vec4(mi, a.z, a.w, b.x);
    vec4 mip = vec4(a.y, b.y, b.z, b.w);
    vec4 coefficient = (mip - mi * mp) / denominator;
    imageStore(outA, p, coefficient);
    imageStore(outB, p, mp - coefficient * mi);
}
"""

    /**
     * The color at half size: the wide one (each channel's guided output over the luma's, the box means of a and b at the luma) and the local one (the
     * half-size pixel's own ratios through a 5 × 5 bilateral filter, OpenCV's: color distance the sum of absolute differences, a round window); where
     * they differ by more than [keep] × the noise, the local color comes back.
     */
    const val CHROMA_MIX = HEAD + GRID + """
layout(binding = 0) uniform sampler2D coefficientsA;
layout(binding = 1) uniform sampler2D coefficientsB;
layout(binding = 2) uniform sampler2D inputA;
layout(binding = 3) uniform sampler2D ratios;
layout(rgba32f, binding = 0) writeonly uniform highp image2D wideOut;
layout(rgba32f, binding = 1) writeonly uniform highp image2D localOut;
layout(r32f, binding = 2) writeonly uniform highp image2D apartOut;
uniform ivec2 size;
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= size.x || p.y >= size.y) return;
    float i = texelFetch(inputA, p, 0).x;
    vec4 q = texelFetch(coefficientsA, p, 0) * i + texelFetch(coefficientsB, p, 0);
    vec3 wide = q.yzw / max(q.x, 1e-4);
    vec3 here = texelFetch(ratios, p, 0).rgb;
    vec3 sum = vec3(0.0);
    float total = 0.0;
    for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) {
        if (dx * dx + dy * dy > 4) continue;
        vec3 there = texelFetch(ratios, ivec2(mirror(p.x + dx, size.x), mirror(p.y + dy, size.y)), 0).rgb;
        vec3 diff = abs(there - here);
        float dist = diff.r + diff.g + diff.b;
        float w = exp(-float(dx * dx + dy * dy) / (2.0 * 1.5 * 1.5)) * exp(-dist * dist / (2.0 * 0.1 * 0.1));
        sum += w * there;
        total += w;
    }
    vec3 local = sum / total;
    imageStore(wideOut, p, vec4(wide, 0.0));
    imageStore(localOut, p, vec4(local, 0.0));
    imageStore(apartOut, p, vec4(max(max(abs(local.r - wide.r), abs(local.g - wide.g)), abs(local.b - wide.b))));
}
"""

    /** The half-size color: wide, or local where the two are further apart than [keep] × the noise (fully at twice that). */
    const val CHROMA_KEEP = HEAD + GRID + """
layout(binding = 0) uniform sampler2D wide;
layout(binding = 1) uniform sampler2D local;
layout(binding = 2) uniform sampler2D apart;
layout(binding = 3) uniform sampler2D noise;
layout(rgba32f, binding = 0) writeonly uniform highp image2D dst;
uniform float keep;
uniform ivec2 size;
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= size.x || p.y >= size.y) return;
    float sigma = (texelFetch(noise, p * 2, 0).r + texelFetch(noise, p * 2 + ivec2(1, 0), 0).r + texelFetch(noise, p * 2 + ivec2(0, 1), 0).r
        + texelFetch(noise, p * 2 + ivec2(1, 1), 0).r) * 0.25;
    float k = clamp((texelFetch(apart, p, 0).r - keep * sigma) / (keep * sigma + 1e-4), 0.0, 1.0);
    vec3 w = texelFetch(wide, p, 0).rgb;
    imageStore(dst, p, vec4(w + k * (texelFetch(local, p, 0).rgb - w), 0.0));
}
"""

    /** The luma's guided filter inputs at full size: (y, y², 0, 0), y the display picture's luma (at least 1e-4). */
    const val LUMA_INPUT = HEAD + GRID + """
layout(binding = 0) uniform sampler2D display;
layout(rgba32f, binding = 0) writeonly uniform highp image2D dst;
uniform ivec2 size;
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= size.x || p.y >= size.y) return;
    float y = max(dot(texelFetch(display, p, 0).rgb, LUMA), 1e-4);
    imageStore(dst, p, vec4(y, y * y, 0.0, 0.0));
}
"""

    /** The luma's guided filter coefficients with each pixel's own eps: (luma edge × how noisy + 0.001)²; noisy from the noise blurred by 4 px. */
    const val LUMA_COEFFICIENTS = HEAD + GRID + """
layout(binding = 0) uniform sampler2D moments;
layout(binding = 1) uniform sampler2D noise4;
layout(rgba16f, binding = 0) writeonly uniform highp image2D dst;
uniform float edge;
uniform float noisyAt;
uniform ivec2 size;
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= size.x || p.y >= size.y) return;
    vec4 m = texelFetch(moments, p, 0);
    float noisy = clamp(texelFetch(noise4, p, 0).r / noisyAt, 0.0, 1.0);
    float eps = (edge * noisy + 1e-3) * (edge * noisy + 1e-3);
    float variance = m.y - m.x * m.x;
    float a = variance / (variance + eps);
    imageStore(dst, p, vec4(a, m.x - a * m.x, 0.0, 0.0));
}
"""

    /** The luma smoothed where noisy: y + (guided − y) × noisy × strength. */
    const val LUMA_DENOISE = HEAD + GRID + """
layout(binding = 0) uniform sampler2D moments;
layout(binding = 1) uniform sampler2D coefficients;
layout(binding = 2) uniform sampler2D noise4;
layout(r32f, binding = 0) writeonly uniform highp image2D dst;
uniform float noisyAt;
uniform float strength;
uniform ivec2 size;
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= size.x || p.y >= size.y) return;
    float y = texelFetch(moments, p, 0).r;
    vec4 ab = texelFetch(coefficients, p, 0);
    float noisy = clamp(texelFetch(noise4, p, 0).r / noisyAt, 0.0, 1.0);
    imageStore(dst, p, vec4(y + (ab.x * y + ab.y - y) * noisy * strength, 0.0, 0.0, 0.0));
}
"""

    /**
     * The finished picture: the half-size color up-sampled (OpenCV's bilinear resize, pixel centers), saturated, times the sharpened luma (less where
     * noisy); then the hue curve in CIELAB (OpenCV's sRGB D65 conversion); 0..1.
     */
    const val FINISH = HEAD + GRID + """
layout(binding = 0) uniform sampler2D ratio;
layout(binding = 1) uniform sampler2D luma;
layout(binding = 2) uniform sampler2D blurred;
layout(binding = 3) uniform sampler2D noise4;
layout(rgba32f, binding = 0) writeonly uniform highp image2D dst;
uniform ivec2 size;
uniform ivec2 halfSize;
uniform float saturation;
uniform float sharpen;
uniform int hueCount;
uniform float hueAt[16];
uniform float hueTurn[16];
vec3 ratioAt(ivec2 p) {
    float sx = max((float(p.x) + 0.5) * 0.5 - 0.5, 0.0);
    float sy = max((float(p.y) + 0.5) * 0.5 - 0.5, 0.0);
    int x0 = min(int(floor(sx)), halfSize.x - 1);
    int y0 = min(int(floor(sy)), halfSize.y - 1);
    int x1 = min(x0 + 1, halfSize.x - 1);
    int y1 = min(y0 + 1, halfSize.y - 1);
    float fx = sx - float(x0);
    float fy = sy - float(y0);
    vec3 a = mix(texelFetch(ratio, ivec2(x0, y0), 0).rgb, texelFetch(ratio, ivec2(x1, y0), 0).rgb, fx);
    vec3 b = mix(texelFetch(ratio, ivec2(x0, y1), 0).rgb, texelFetch(ratio, ivec2(x1, y1), 0).rgb, fx);
    return mix(a, b, fy);
}
float labF(float t) { return t > 0.008856 ? pow(t, 1.0 / 3.0) : 7.787 * t + 16.0 / 116.0; }
float labInverse(float f) { return f > 0.206893 ? f * f * f : (f - 16.0 / 116.0) / 7.787; }
float encode(float x) { return x <= 0.0031308 ? 12.92 * x : 1.055 * pow(x, 1.0 / 2.4) - 0.055; }
vec3 turnHues(vec3 c) {
    vec3 lin = vec3(linearFromSrgb(c.r), linearFromSrgb(c.g), linearFromSrgb(c.b));
    vec3 xyz = mat3(0.412453, 0.212671, 0.019334, 0.357580, 0.715160, 0.119193, 0.180423, 0.072169, 0.950227) * lin;
    float fx = labF(xyz.x / 0.950456);
    float fy = labF(xyz.y);
    float fz = labF(xyz.z / 1.088754);
    float l = xyz.y > 0.008856 ? 116.0 * fy - 16.0 : 903.3 * xyz.y;
    float a = 500.0 * (fx - fy);
    float b = 200.0 * (fy - fz);
    float hue = mod(degrees(atan(b, a)), 360.0);
    float turn = 0.0;
    for (int k = 0; k + 1 < hueCount; k++) {
        if (hue >= hueAt[k] && hue <= hueAt[k + 1]) {
            turn = mix(hueTurn[k], hueTurn[k + 1], (hue - hueAt[k]) / max(hueAt[k + 1] - hueAt[k], 1e-6));
            break;
        }
    }
    float t = radians(turn);
    float a2 = a * cos(t) - b * sin(t);
    float b2 = a * sin(t) + b * cos(t);
    float gy = l <= 8.0 ? 7.787 * (l / 903.3) + 16.0 / 116.0 : (l + 16.0) / 116.0;
    float y = l <= 8.0 ? l / 903.3 : gy * gy * gy;
    vec3 back = vec3(labInverse(a2 / 500.0 + gy) * 0.950456, y, labInverse(gy - b2 / 200.0) * 1.088754);
    vec3 rgb = mat3(3.240479, -0.969256, 0.055648, -1.53715, 1.875991, -0.204043, -0.498535, 0.041556, 1.057311) * back;
    return clamp(vec3(encode(rgb.r), encode(rgb.g), encode(rgb.b)), 0.0, 1.0);
}
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= size.x || p.y >= size.y) return;
    vec3 r = max(1.0 + (ratioAt(p) - 1.0) * saturation, vec3(0.0));
    float y = texelFetch(luma, p, 0).r;
    float n = texelFetch(noise4, p, 0).r / 0.05;
    float amount = sharpen / (1.0 + n * n);
    float sharp = max(y + amount * (y - texelFetch(blurred, p, 0).r), 0.0);
    vec3 c = clamp(r * sharp, 0.0, 1.0);
    if (hueCount > 1) c = turnHues(c);
    imageStore(dst, p, vec4(c, 1.0));
}
"""

    /** 0..1 to 8 bits as the prototype saves (× 255 + ½, cut), for the JPEG. */
    const val TO_BYTES = HEAD + GRID + """
layout(binding = 0) uniform sampler2D src;
layout(rgba8, binding = 0) writeonly uniform highp image2D dst;
uniform ivec2 size;
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= size.x || p.y >= size.y) return;
    vec3 c = floor(clamp(texelFetch(src, p, 0).rgb, 0.0, 1.0) * 255.0 + 0.5) / 255.0;
    imageStore(dst, p, vec4(c, 1.0));
}
"""
}
