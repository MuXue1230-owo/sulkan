#version 450

layout(triangles) in;
layout(triangle_strip, max_vertices = 3) out;

layout(location = 0) in vec2 texCoordIn[];
layout(location = 0) out vec2 texCoord;

void main() {
	for (int i = 0; i < 3; i++) {
		texCoord = texCoordIn[i];
		gl_Position = gl_in[i].gl_Position;
		EmitVertex();
	}
	EndPrimitive();
}
