#version 450

layout(binding = 0) uniform sampler2D DiffuseSampler;
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

#include <shaders/showcase/include/color_curve.glsl>

const bool OPT_ENABLE = @SULKAN:post.enable@;
const vec3 OPT_GRADE = @SULKAN:post.grade@;
const float OPT_STRENGTH = @SULKAN:post.strength@;
const float OPT_SATURATION = @SULKAN:debug.saturation@;

void main() {
	vec4 src = texture(DiffuseSampler, texCoord);
	if (!OPT_ENABLE) {
		fragColor = src;
		return;
	}
	vec3 graded = curve_color(src.rgb * OPT_GRADE * 1.15);
	graded = mix(src.rgb, graded, OPT_STRENGTH + 0.20);
	fragColor = vec4(apply_saturation(graded, OPT_SATURATION), src.a);
}
