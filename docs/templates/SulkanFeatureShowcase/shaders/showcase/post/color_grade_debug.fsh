#version 450

layout(binding = 0) uniform sampler2D DiffuseSampler;
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

const bool OPT_ENABLE = @SULKAN:post.enable@;

void main() {
	vec4 src = texture(DiffuseSampler, texCoord);
	if (!OPT_ENABLE) {
		fragColor = src;
		return;
	}
	fragColor = vec4(src.r, src.g * 0.8, src.b * 1.2, src.a);
}
