package cn.mxst.sulkan.shaderpack;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class ShaderpackMetadata {
	public final String name;
	public final String version;
	public final SemVer specVersion;
	public final ShaderpackStages stages;
	public final List<ShaderpackResourceDecl> resources;
	public final ShaderpackFeatures features;
	public final ShaderpackExtensions extensions;
	public final List<ShaderpackOptionDecl> options;
	public final Map<String, Map<String, ShaderpackPipelineProgram>> pipelinePrograms;
	public final Map<String, String> translations;
	public final Path sourcePath;

	public ShaderpackMetadata(
		String name,
		String version,
		SemVer specVersion,
		ShaderpackStages stages,
		List<ShaderpackResourceDecl> resources,
		ShaderpackFeatures features,
		ShaderpackExtensions extensions,
		List<ShaderpackOptionDecl> options,
		Map<String, Map<String, ShaderpackPipelineProgram>> pipelinePrograms,
		Map<String, String> translations,
		Path sourcePath
	) {
		this.name = name;
		this.version = version;
		this.specVersion = specVersion;
		this.stages = stages;
		this.resources = resources;
		this.features = features;
		this.extensions = extensions;
		this.options = options;
		this.pipelinePrograms = pipelinePrograms;
		this.translations = translations;
		this.sourcePath = sourcePath;
	}
}
