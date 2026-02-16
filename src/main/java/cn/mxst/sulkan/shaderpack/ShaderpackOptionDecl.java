package cn.mxst.sulkan.shaderpack;

import java.util.List;

public record ShaderpackOptionDecl(
	String id,
	String path,
	String type,
	String target,
	String key,
	List<String> renderValues,
	Object defaultValue,
	List<String> values,
	Number min,
	Number max,
	Number step,
	String labelKey,
	String descriptionKey,
	String label,
	String description,
	List<ShaderpackOptionDecl> children
) {
}
