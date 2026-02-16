package cn.mxst.sulkan.shaderpack;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record SemVer(int major, int minor, int patch, String preRelease, String build) {
	private static final Pattern PATTERN = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?(?:\\+([0-9A-Za-z.-]+))?$");

	public static SemVer parse(String value) {
		if (value == null) {
			return null;
		}
		Matcher matcher = PATTERN.matcher(value.trim());
		if (!matcher.matches()) {
			return null;
		}
		int major = Integer.parseInt(matcher.group(1));
		int minor = Integer.parseInt(matcher.group(2));
		int patch = Integer.parseInt(matcher.group(3));
		String preRelease = matcher.group(4);
		String build = matcher.group(5);
		return new SemVer(major, minor, patch, preRelease, build);
	}

	public String toString() {
		return major + "." + minor + "." + patch;
	}
}
