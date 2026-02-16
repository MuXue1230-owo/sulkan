#version 450

layout(binding = 0) uniform sampler2D DiffuseSampler;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
	vec4 color = texture(DiffuseSampler, texCoord);
	vec3 warm = vec3(1.03, 1.00, 0.97);
	fragColor = vec4(color.rgb * warm, color.a);
}
