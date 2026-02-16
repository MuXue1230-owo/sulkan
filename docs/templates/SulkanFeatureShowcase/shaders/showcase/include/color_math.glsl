#pragma once

float luma(vec3 color) {
	return dot(color, vec3(0.2126, 0.7152, 0.0722));
}

vec3 saturate_rgb(vec3 color) {
	return clamp(color, vec3(0.0), vec3(1.0));
}

vec3 apply_saturation(vec3 color, float saturation) {
	float y = luma(color);
	return mix(vec3(y), color, saturation);
}
