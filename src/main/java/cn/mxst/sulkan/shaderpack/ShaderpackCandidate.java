package cn.mxst.sulkan.shaderpack;

import java.nio.file.Path;

public record ShaderpackCandidate(
	String id,
	Path path,
	ShaderpackLoadResult loadResult
) {
	public boolean isValid() {
		return loadResult != null && loadResult.isValid() && loadResult.metadata() != null;
	}

	public String displayName() {
		if (isValid()) {
			return loadResult.metadata().name;
		}
		return id;
	}
}
