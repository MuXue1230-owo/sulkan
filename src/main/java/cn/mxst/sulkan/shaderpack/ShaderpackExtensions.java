package cn.mxst.sulkan.shaderpack;

import java.util.List;

public record ShaderpackExtensions(
	List<String> required,
	List<String> optional
) {
}
