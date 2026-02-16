#version 450

layout(binding = 0) uniform sampler2D DiffuseSampler;
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
	vec4 src = texture(DiffuseSampler, texCoord);
	vec3 tint = vec3(1.0, 0.88, 0.82);
	vec3 graded = src.rgb * tint;
	float scan = 0.04 * sin(texCoord.y * 900.0);
	fragColor = vec4(clamp(graded + scan, 0.0, 1.0), src.a);
}
