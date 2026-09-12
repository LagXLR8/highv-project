#version 150

uniform sampler2D DiffuseSampler;
uniform sampler2D History;
uniform float Decay;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec4 current = texture(DiffuseSampler, texCoord);
    vec4 history = texture(History, texCoord);
    fragColor = vec4(mix(current.rgb, history.rgb, Decay), 1.0);
}
