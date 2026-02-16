#version 450

layout(binding = 0) uniform sampler2D DiffuseSampler;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

#include "local_tone.glsl" // same-folder relative include
#include <shaders/showcase/include/color_curve.glsl> // absolute include path
#include <shaders/showcase/include/color_math.glsl>
// @sulkan_option path=debug.auto_extracted type=bool default=true key=debug.auto_extract target=shaders/showcase/post/color_grade.fsh label_key=showcase.option.debug.auto_extracted.label description_key=showcase.option.debug.auto_extracted.desc

const bool OPT_ENABLE = @SULKAN:post.enable@;
const vec3 OPT_GRADE = @SULKAN:post.grade@;
const float OPT_STRENGTH = @SULKAN:post.strength@;
const int OPT_ITERATIONS = @SULKAN:debug.iterations@;
const float OPT_SATURATION = @SULKAN:debug.saturation@;
const bool OPT_AUTO_EXTRACT = @SULKAN:debug.auto_extract@;
const float PIPE_EXPOSURE = @SULKAN_PARAM:EXPOSURE@;

void main() {
	vec4 src = texture(DiffuseSampler, texCoord);
	if (!OPT_ENABLE) {
		fragColor = src;
		return;
	}

	vec3 graded = src.rgb * OPT_GRADE * PIPE_EXPOSURE;
	for (int i = 0; i < OPT_ITERATIONS; i++) {
		graded = mix(graded, curve_color(graded), 0.25);
	}
	graded = apply_local_tone(graded);
	if (OPT_AUTO_EXTRACT) {
		graded = saturate_rgb(graded * 1.01);
	}
	graded = saturate_rgb(mix(src.rgb, graded, OPT_STRENGTH));
	fragColor = vec4(apply_saturation(graded, OPT_SATURATION), src.a);
}
