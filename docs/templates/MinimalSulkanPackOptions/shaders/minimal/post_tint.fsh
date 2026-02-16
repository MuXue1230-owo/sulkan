#version 450

layout(binding = 0) uniform sampler2D DiffuseSampler;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

const bool WARM_ENABLED = @SULKAN:warm.enable@;
const float WARM_STRENGTH = @SULKAN:warm.strength@;

void main() {
	vec4 color = texture(DiffuseSampler, texCoord);
	if (!WARM_ENABLED) {
		fragColor = color;
		return;
	}
	vec3 warm = vec3(WARM_STRENGTH, 1.00, 2.00 - WARM_STRENGTH);
	fragColor = vec4(color.rgb * warm, color.a);
}
