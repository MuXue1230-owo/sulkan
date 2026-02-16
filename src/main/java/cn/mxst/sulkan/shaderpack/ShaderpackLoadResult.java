package cn.mxst.sulkan.shaderpack;

import java.util.Collections;
import java.util.List;

public final class ShaderpackLoadResult {
	private final ShaderpackMetadata metadata;
	private final List<String> errors;
	private final List<String> warnings;

	public ShaderpackLoadResult(ShaderpackMetadata metadata, List<String> errors, List<String> warnings) {
		this.metadata = metadata;
		this.errors = errors == null ? List.of() : List.copyOf(errors);
		this.warnings = warnings == null ? List.of() : List.copyOf(warnings);
	}

	public static ShaderpackLoadResult error(String message) {
		return new ShaderpackLoadResult(null, List.of(message), Collections.emptyList());
	}

	public ShaderpackMetadata metadata() {
		return metadata;
	}

	public List<String> errors() {
		return errors;
	}

	public List<String> warnings() {
		return warnings;
	}

	public boolean isValid() {
		return errors.isEmpty();
	}
}
