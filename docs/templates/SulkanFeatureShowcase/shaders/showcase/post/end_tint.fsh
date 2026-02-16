#version 450

layout(binding = 0) uniform sampler2D DiffuseSampler;
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
	vec4 src = texture(DiffuseSampler, texCoord);
	fragColor = vec4(src.rgb * vec3(0.90, 0.95, 1.05), src.a);
}
