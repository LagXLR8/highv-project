#version 330 core

#define PI       3.14159265358979323846
#define SUBDIVISIONS  128
#define TARGET_ALPHA  0.20
#define RELATIVE_SPEED 7.0

uniform sampler2D DiffuseSampler;
uniform float STime;       // thời gian (ticks + delta) / 20
uniform float Weight;      // intensity 0..1
uniform float BiasAngle;   // góc hướng di chuyển (radian)
uniform float BiasWeight;  // mức độ lệch về hướng di chuyển 0..1

in  vec2 texCoord;
out vec4 fragColor;

// Hash từ Shadertoy (https://www.shadertoy.com/view/Xt3cDn)
uint base_hash(uint p) {
    p = 1103515245U * ((p >> 1U) ^ p);
    uint h32 = 1103515245U * (p ^ (p >> 3U));
    return h32 ^ (h32 >> 16);
}
float hash(in float x) {
    return float(base_hash(floatBitsToUint(x))) * (1.0 / float(0xFFFFFFFFU));
}

// SDF n-star polygon (https://iquilezles.org/articles/distfunctions2d/)
float lines_sdf(in vec2 p, in float r, in int n, in float m) {
    float an = PI / float(n);
    float en = PI / m;
    vec2 acs = vec2(cos(an), sin(an));
    vec2 ecs = vec2(cos(en), sin(en));

    float bn = mod(atan(p.x, p.y), 2.0 * an) - an;
    p = length(p) * vec2(cos(bn), abs(sin(bn)));

    p -= r * acs;
    p += ecs * clamp(-dot(p, ecs), 0.0, r * acs.y / ecs.y);
    return length(p) * sign(p.x);
}

mat2 rotate(in float a) {
    return mat2(cos(a), -sin(a), sin(a), cos(a));
}

void main() {
    // Không làm gì nếu chưa có tốc độ
    if (Weight <= 0.0) {
        fragColor = vec4(texture(DiffuseSampler, texCoord).rgb, 1.0);
        return;
    }

    vec3 tex = texture(DiffuseSampler, texCoord).rgb;
    vec2 st  = texCoord - 0.5;

    float weight_normalized = mix(0.8, 1.0, Weight);

    // Dịch tâm về hướng di chuyển (tạo cảm giác lao về phía trước)
    st += -0.08 * BiasWeight * vec2(cos(BiasAngle), sin(BiasAngle));

    // Góc và seed
    float angle            = atan(st.t, st.s);
    float angle_normalized = (0.5 * angle) / PI;
    float angle_bias       = BiasWeight * cos(angle - BiasAngle) + (1.0 - BiasWeight);
    float s_rand           = mix(0.5, 1.0, hash(floor(angle_normalized * float(SUBDIVISIONS))));

    // Animation progress
    float s_time    = RELATIVE_SPEED * s_rand * STime;
    float shape     = fract(s_time);
    float iteration = floor(s_time);

    float p_rand    = mix(0.5, 1.0, hash(iteration));
    float luminance = dot(tex, vec3(0.2126, 0.7152, 0.0722));

    // Adjust target alpha với luminance: tối hơn → ít thấy; sáng hơn → thấy rõ hơn
    float target_alpha = TARGET_ALPHA * mix(0.15, 1.0, luminance);
    float line_radius  = 1.0 - pow(max(0.0, 1.6 * shape - 0.6), 2.0);
    float line_alpha   = 1.0 - 2.0 * shape * target_alpha * Weight;

    st *= 0.6;
    st *= rotate(s_rand + p_rand);

    float w = 162.0 * line_radius * float(SUBDIVISIONS) / 90.0;
    w *= 0.4 * p_rand;
    w *= angle_bias * weight_normalized;
    w  = max(2.0, w);

    float d   = lines_sdf(st, 0.7, SUBDIVISIONS, w);
    vec3  col = (d > 0.0) ? vec3(1.0) : tex;
    col = mix(col, vec3(1.0), 1.0 - smoothstep(0.0, 0.001, abs(d)));
    col = mix(col, tex, line_alpha);

    fragColor = vec4(col, 1.0);
}
