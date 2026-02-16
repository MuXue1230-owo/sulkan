#version 450

layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;

layout(binding = 0, rgba16f) uniform readonly image2D colorimg0;
layout(binding = 1, rgba16f) uniform writeonly image2D colorimg1;

void main() {
	ivec2 p = ivec2(gl_GlobalInvocationID.xy);
	vec4 color = imageLoad(colorimg0, p);
	imageStore(colorimg1, p, vec4(color.rgb * 0.995, color.a));
}
