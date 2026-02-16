#version 450

layout(location = 0) in vec2 inUV;
layout(location = 0) out vec4 outColor0;

void main() {
	outColor0 = vec4(inUV, 0.0, 1.0);
}
