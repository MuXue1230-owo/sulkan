#pragma once

#include "color_math.glsl"

vec3 curve_color(vec3 color) {
	vec3 mapped = color / (color + vec3(1.0));
	return saturate_rgb(mapped * 1.05);
}
