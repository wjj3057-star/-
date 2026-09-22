package com.imaxcam.gl

/**
 * GLSL ES 3.00 shaders for the capture pass.
 *
 * Everything the pipeline does per frame lives here: a crop (expressed entirely in the
 * texture matrix, so it is free), an optional tone map for SDR preview, and a downsample
 * that feeds the HDR10+ histogram. There is no intermediate full-resolution buffer
 * anywhere, which is what keeps the camera-to-encoder path down to a single draw call.
 *
 * Sources are assembled by concatenation rather than interpolation so that `#version`
 * always lands in column zero; some drivers reject a shader whose version directive is
 * indented.
 */
internal object Shaders {

    const val ATTRIB_POSITION = 0
    const val ATTRIB_TEXCOORD = 1

    val VERTEX = """
#version 300 es
layout(location = $ATTRIB_POSITION) in vec4 aPosition;
layout(location = $ATTRIB_TEXCOORD) in vec2 aTexCoord;
uniform mat4 uStMatrix;
uniform mat4 uCropMatrix;
out vec2 vTexCoord;
void main() {
    gl_Position = aPosition;
    vTexCoord = (uStMatrix * uCropMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
}
""".trimStart()

    private val FRAGMENT_HEADER = """
#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES sTexture;
in vec2 vTexCoord;
out vec4 fragColor;
""".trimStart()

    /** Shared helpers: PQ / HLG transfer functions and the BT.2020 -> BT.709 matrix. */
    private val COLOR_LIB = """
const float PQ_M1 = 0.1593017578125;
const float PQ_M2 = 78.84375;
const float PQ_C1 = 0.8359375;
const float PQ_C2 = 18.8515625;
const float PQ_C3 = 18.6875;

// ST 2084 code value -> normalised linear light (1.0 == 10000 nits).
vec3 pqEotf(vec3 e) {
    vec3 p = pow(max(e, 0.0), vec3(1.0 / PQ_M2));
    vec3 num = max(p - PQ_C1, 0.0);
    vec3 den = PQ_C2 - PQ_C3 * p;
    return pow(num / max(den, 1e-6), vec3(1.0 / PQ_M1));
}

// Normalised linear light -> ST 2084 code value.
vec3 pqOetf(vec3 l) {
    vec3 p = pow(clamp(l, 0.0, 1.0), vec3(PQ_M1));
    return pow((PQ_C1 + PQ_C2 * p) / (1.0 + PQ_C3 * p), vec3(PQ_M2));
}

// ARIB STD-B67 (HLG) code value -> normalised scene light in [0,1]. The OOTF is left
// out: these values only feed a preview tone map and the metadata statistics, neither of
// which is sensitive to the system gamma.
const float HLG_A = 0.17883277;
const float HLG_B = 0.28466892;
const float HLG_C = 0.55991073;
vec3 hlgEotf(vec3 e) {
    vec3 lo = (e * e) / 3.0;
    vec3 hi = (exp((e - HLG_C) / HLG_A) + HLG_B) / 12.0;
    return mix(lo, hi, step(vec3(0.5), e));
}

const mat3 BT2020_TO_BT709 = mat3(
    1.660491, -0.124550, -0.018151,
    -0.587641, 1.132900, -0.100579,
    -0.072850, -0.008349, 1.118730
);

vec3 bt709Oetf(vec3 l) {
    l = clamp(l, 0.0, 1.0);
    return mix(l * 12.92, 1.055 * pow(l, vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), l));
}
""".trimStart()

    /**
     * Pass-through blit.
     *
     * The camera frame is already BT.2020 with the transfer function the dynamic range
     * profile selected, and the destination EGL surface is tagged with the same colour
     * space, so copying the sample verbatim is both correct and the cheapest thing the
     * GPU can do.
     */
    val FRAGMENT_PASSTHROUGH = FRAGMENT_HEADER + """
void main() {
    fragColor = texture(sTexture, vTexCoord);
}
""".trimStart()

    /**
     * Tone-mapped preview for displays that cannot show BT.2020 PQ directly.
     *
     * uTransfer: 0 = PQ source, 1 = HLG source.
     * uPeakNits: display light that source diffuse white is mapped to.
     */
    val FRAGMENT_TONEMAP = FRAGMENT_HEADER + """
uniform int uTransfer;
uniform float uPeakNits;
""".trimStart() + COLOR_LIB + """
void main() {
    vec3 e = texture(sTexture, vTexCoord).rgb;
    // Normalise both transfer functions to linear light scaled so 1.0 is diffuse white
    // on an SDR display.
    vec3 linear = (uTransfer == 0)
        ? pqEotf(e) * 10000.0 / uPeakNits
        : hlgEotf(e) * 1000.0 / uPeakNits;

    // Reinhard shoulder with the toe left alone: mid-tones keep their contrast while
    // specular highlights compress instead of clipping.
    vec3 mapped = linear / (1.0 + max(linear - 0.6, 0.0));
    vec3 rec709 = clamp(BT2020_TO_BT709 * mapped, 0.0, 1.0);
    fragColor = vec4(bt709Oetf(rec709), 1.0);
}
""".trimStart()

    /**
     * Histogram downsample feeding the HDR10+ metadata builder.
     *
     * Takes four taps per output texel and keeps the maxima, so small specular highlights
     * survive a large reduction instead of being averaged away — those highlights are
     * exactly what the ST 2094-40 tone curve needs to know about. Values stay in the PQ
     * domain: it is perceptually uniform, so 8 bits per channel is plenty of precision
     * and the read-back stays at a few tens of kilobytes.
     */
    val FRAGMENT_HISTOGRAM = FRAGMENT_HEADER + """
uniform vec2 uTapOffset;
uniform int uTransfer;
""".trimStart() + COLOR_LIB + """
void main() {
    vec3 m = vec3(0.0);
    for (int y = 0; y < 2; ++y) {
        for (int x = 0; x < 2; ++x) {
            vec2 uv = vTexCoord + vec2(float(x) - 0.5, float(y) - 0.5) * uTapOffset;
            m = max(m, texture(sTexture, uv).rgb);
        }
    }
    // HLG sources are re-expressed in PQ so the CPU side has a single decode path.
    // 1.0 HLG is nominally 1000 nits, which is 0.1 on the PQ 10000-nit scale.
    if (uTransfer == 1) {
        m = pqOetf(hlgEotf(m) * 0.1);
    }
    fragColor = vec4(max(m.r, max(m.g, m.b)), m.r, m.g, m.b);
}
""".trimStart()
}
