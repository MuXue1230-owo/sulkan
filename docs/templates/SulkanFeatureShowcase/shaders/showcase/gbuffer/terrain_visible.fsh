#version 450

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 texCoord0;
layout(location = 2) in float sphericalVertexDistance;
layout(location = 3) in float cylindricalVertexDistance;
layout(location = 4) in flat float fadeFactor;

layout(location = 0) out vec4 fragColor;

const vec3 TERRAIN_TINT = @SULKAN:terrain.tint@;

void main() {
	vec3 base = clamp(vertexColor.rgb * TERRAIN_TINT, 0.0, 1.0);
	fragColor = vec4(base, 1.0);
}
