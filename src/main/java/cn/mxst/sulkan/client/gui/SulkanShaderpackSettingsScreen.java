package cn.mxst.sulkan.client.gui;

import cn.mxst.sulkan.compat.vulkanmod.SulkanVulkanPipelineReloader;
import cn.mxst.sulkan.shaderpack.ShaderpackConfig;
import cn.mxst.sulkan.shaderpack.ShaderpackConfigSession;
import cn.mxst.sulkan.shaderpack.ShaderpackManager;
import cn.mxst.sulkan.shaderpack.ShaderpackMetadata;
import cn.mxst.sulkan.shaderpack.ShaderpackOptionDecl;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.vulkanmod.config.gui.render.GuiRenderer;
import net.vulkanmod.config.gui.widget.VButtonWidget;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.util.ColorUtil;

public final class SulkanShaderpackSettingsScreen extends Screen {
	private final Screen parent;
	private final ShaderpackMetadata metadata;
	private final ShaderpackConfigSession session;
	private final List<ShaderpackOptionDecl> options;
	private final String pageTitle;
	private final String screenKey;
	private final Map<String, ShaderpackOptionDecl> allOptionsByPath;
	private SulkanOptionList optionList;
	private VButtonWidget applyButton;
	private VButtonWidget backButton;
	private final List<VButtonWidget> profileButtons = new ArrayList<>();
	private Text statusMessage;
	private long statusUntil;
	private int statusColor = -1;
	private int buttonRowY;
	private boolean initialized;
	private List<ShaderpackOptionDecl> displayOptions = List.of();
	private int optionColumns = 1;
	private final Map<String, SulkanOptionEntryWidget> widgetByPath = new HashMap<>();

	public SulkanShaderpackSettingsScreen(Screen parent, ShaderpackMetadata metadata, ShaderpackConfigSession session) {
		this(parent, metadata, session, metadata.options, metadata.name, "main", buildOptionPathMap(metadata.options));
	}

	private SulkanShaderpackSettingsScreen(
		Screen parent,
		ShaderpackMetadata metadata,
		ShaderpackConfigSession session,
		List<ShaderpackOptionDecl> options,
		String pageTitle,
		String screenKey,
		Map<String, ShaderpackOptionDecl> allOptionsByPath
	) {
		super(Text.translatable("sulkan.shaderpack_settings.title", pageTitle));
		this.parent = parent;
		this.metadata = metadata;
		this.session = session;
		this.options = options;
		this.pageTitle = pageTitle;
		this.screenKey = screenKey == null || screenKey.isBlank() ? "main" : screenKey;
		this.allOptionsByPath = allOptionsByPath == null ? Map.of() : allOptionsByPath;
	}

	@Override
	protected void init() {
		this.initialized = true;
		this.widgetByPath.clear();
		this.profileButtons.clear();
		this.displayOptions = this.resolveDisplayOptions();
		this.optionColumns = this.resolveOptionColumns();

		int contentWidth = Math.min(this.width - 40, 420);
		int left = (this.width - contentWidth) / 2;

		int buttonHeight = 20;
		int bottomMargin = 8;
		int buttonY = this.height - buttonHeight - bottomMargin;
		int listTop = 36;
		int profileHeight = this.addProfileButtons(left, contentWidth, listTop, buttonHeight);
		if (profileHeight > 0) {
			listTop += profileHeight + 6;
		}
		int listHeight = Math.max(40, buttonY - listTop - 8);

		this.optionList = new SulkanOptionList(left, listTop, contentWidth, listHeight, 22);
		this.optionList.setEntries(this.buildEntryWidgets(), this.optionColumns);
		this.addSelectableChild(this.optionList);

		this.addBottomButtons(buttonY, buttonHeight);
		this.updateButtons();
	}

	private void addBottomButtons(int buttonY, int buttonHeight) {
		this.buttonRowY = buttonY;
		int padding = 10;
		int margin = 6;
		Text applyText = Text.translatable("sulkan.shaderpack_screen.apply");
		Text backText = Text.translatable("sulkan.shaderpack_screen.back");
		int applyWidth = this.textRenderer.getWidth(applyText) + padding * 2;
		int backWidth = this.textRenderer.getWidth(backText) + padding * 2;
		int totalWidth = applyWidth + backWidth + margin;
		int startX = (this.width - totalWidth) / 2;

		this.applyButton = new VButtonWidget(
			startX,
			buttonY,
			applyWidth,
			buttonHeight,
			applyText,
			button -> this.applyChanges()
		);
		startX += applyWidth + margin;
		this.backButton = new VButtonWidget(
			startX,
			buttonY,
			backWidth,
			buttonHeight,
			backText,
			button -> this.client.setScreen(this.parent)
		);

		this.addSelectableChild(this.applyButton);
		this.addSelectableChild(this.backButton);
	}

	private int addProfileButtons(int left, int contentWidth, int topY, int buttonHeight) {
		if (this.metadata.uiLayout == null || this.metadata.uiLayout.profiles().isEmpty()) {
			return 0;
		}
		List<String> profileNames = new ArrayList<>(this.metadata.uiLayout.profiles().keySet());
		if (profileNames.isEmpty()) {
			return 0;
		}

		int margin = 6;
		int padding = 8;
		int x = left;
		for (String profileName : profileNames) {
			Text label = Text.literal(profileName);
			int width = Math.max(50, this.textRenderer.getWidth(label) + padding * 2);
			if (x + width > left + contentWidth) {
				break;
			}
			VButtonWidget button = new VButtonWidget(
				x,
				topY,
				width,
				buttonHeight,
				label,
				widget -> this.applyProfile(profileName)
			);
			this.profileButtons.add(button);
			this.addSelectableChild(button);
			x += width + margin;
		}
		return this.profileButtons.isEmpty() ? 0 : buttonHeight;
	}

	private List<SulkanOptionEntryWidget> buildEntryWidgets() {
		List<SulkanOptionEntryWidget> widgets = new ArrayList<>();
		for (ShaderpackOptionDecl option : this.displayOptions) {
			Text label = resolveLabel(option);
			Text value = resolveValueText(option);
			boolean interactive = isInteractive(option);
			SulkanOptionEntryWidget widget = new SulkanOptionEntryWidget(option.path(), label, value, () -> this.onOptionClicked(option));
			widget.setActive(interactive);
			Text tooltip = resolveDescription(option);
			if (tooltip != null) {
				widget.setTooltip(tooltip);
			}
			this.widgetByPath.put(option.path(), widget);
			widgets.add(widget);
		}
		return widgets;
	}

	private List<ShaderpackOptionDecl> resolveDisplayOptions() {
		if (this.options == null || this.options.isEmpty()) {
			return List.of();
		}
		if (this.metadata.uiLayout == null || !this.metadata.uiLayout.hasAnyLayout()) {
			return List.copyOf(this.options);
		}
		List<String> layoutOrder = this.metadata.uiLayout.resolveScreenOrder(this.screenKey);
		if (layoutOrder == null || layoutOrder.isEmpty()) {
			return List.copyOf(this.options);
		}
		Map<String, ShaderpackOptionDecl> available = new HashMap<>();
		for (ShaderpackOptionDecl option : this.options) {
			if (option == null || option.path() == null || option.path().isBlank()) {
				continue;
			}
			available.put(option.path(), option);
		}
		LinkedHashSet<ShaderpackOptionDecl> ordered = new LinkedHashSet<>();
		for (String key : layoutOrder) {
			ShaderpackOptionDecl matched = available.get(key);
			if (matched == null && this.screenKey != null && !"main".equals(this.screenKey) && key != null && !key.contains(".")) {
				matched = available.get(this.screenKey + "." + key);
			}
			if (matched == null && key != null) {
				for (ShaderpackOptionDecl option : this.options) {
					if (option != null && key.equals(option.id())) {
						matched = option;
						break;
					}
				}
			}
			if (matched != null) {
				ordered.add(matched);
			}
		}
		for (ShaderpackOptionDecl option : this.options) {
			if (option != null) {
				ordered.add(option);
			}
		}
		return List.copyOf(ordered);
	}

	private int resolveOptionColumns() {
		if (this.metadata.uiLayout == null) {
			return 1;
		}
		return Math.max(1, Math.min(3, this.metadata.uiLayout.resolveScreenColumns(this.screenKey, 1)));
	}

	private void applyProfile(String profileName) {
		if (this.metadata.uiLayout == null || this.metadata.uiLayout.profiles().isEmpty()) {
			return;
		}
		Map<String, Object> values = this.metadata.uiLayout.profiles().get(profileName);
		if (values == null || values.isEmpty()) {
			return;
		}
		ShaderpackConfig config = this.session.config();
		boolean changed = false;
		for (Map.Entry<String, Object> entry : values.entrySet()) {
			String path = entry.getKey();
			ShaderpackOptionDecl option = this.allOptionsByPath.get(path);
			if (option == null) {
				continue;
			}
			Object converted = convertProfileValue(option, entry.getValue());
			if (converted == null) {
				continue;
			}
			Object current = config.getValue(option.path());
			if (current != null && current.equals(converted)) {
				continue;
			}
			config.setValue(option.path(), converted);
			changed = true;
			updateEntryValue(option);
		}
		if (!changed) {
			return;
		}
		this.session.markDirty();
		this.updateButtons();
		this.setStatus(Text.translatable("sulkan.shaderpack_settings.status.profile_applied", profileName), true);
	}

	private Object convertProfileValue(ShaderpackOptionDecl option, Object raw) {
		if (option == null || option.type() == null || raw == null) {
			return null;
		}
		String type = option.type().toLowerCase(Locale.ROOT);
		return switch (type) {
			case "bool" -> parseBooleanValue(raw);
			case "enum" -> convertEnumProfileValue(option, raw);
			case "int" -> convertIntegerProfileValue(option, raw);
			case "float" -> convertFloatProfileValue(option, raw);
			case "string" -> raw.toString();
			default -> null;
		};
	}

	private Object convertEnumProfileValue(ShaderpackOptionDecl option, Object raw) {
		String value = raw.toString();
		if (option.values() == null || option.values().isEmpty() || option.values().contains(value)) {
			return value;
		}
		return null;
	}

	private Object convertIntegerProfileValue(ShaderpackOptionDecl option, Object raw) {
		long value;
		if (raw instanceof Number number) {
			value = number.longValue();
		} else {
			try {
				value = Long.parseLong(raw.toString().trim());
			} catch (NumberFormatException e) {
				return null;
			}
		}
		if (option.min() != null && value < option.min().longValue()) {
			value = option.min().longValue();
		}
		if (option.max() != null && value > option.max().longValue()) {
			value = option.max().longValue();
		}
		return value;
	}

	private Object convertFloatProfileValue(ShaderpackOptionDecl option, Object raw) {
		double value;
		if (raw instanceof Number number) {
			value = number.doubleValue();
		} else {
			try {
				value = Double.parseDouble(raw.toString().trim());
			} catch (NumberFormatException e) {
				return null;
			}
		}
		if (!Double.isFinite(value)) {
			return null;
		}
		if (option.min() != null && value < option.min().doubleValue()) {
			value = option.min().doubleValue();
		}
		if (option.max() != null && value > option.max().doubleValue()) {
			value = option.max().doubleValue();
		}
		return value;
	}

	private Boolean parseBooleanValue(Object raw) {
		if (raw instanceof Boolean bool) {
			return bool;
		}
		if (raw instanceof Number number) {
			return number.doubleValue() != 0.0D;
		}
		String text = raw.toString().trim().toLowerCase(Locale.ROOT);
		return switch (text) {
			case "true", "on", "yes", "1" -> Boolean.TRUE;
			case "false", "off", "no", "0" -> Boolean.FALSE;
			default -> null;
		};
	}

	private static Map<String, ShaderpackOptionDecl> buildOptionPathMap(List<ShaderpackOptionDecl> options) {
		Map<String, ShaderpackOptionDecl> byPath = new HashMap<>();
		collectOptionsByPath(options, byPath);
		return Map.copyOf(byPath);
	}

	private static void collectOptionsByPath(List<ShaderpackOptionDecl> options, Map<String, ShaderpackOptionDecl> byPath) {
		if (options == null || options.isEmpty()) {
			return;
		}
		for (ShaderpackOptionDecl option : options) {
			if (option == null || option.path() == null || option.path().isBlank()) {
				continue;
			}
			byPath.put(option.path(), option);
			if ("page".equalsIgnoreCase(option.type())) {
				collectOptionsByPath(option.children(), byPath);
			}
		}
	}

	private boolean isInteractive(ShaderpackOptionDecl option) {
		String type = option.type();
		if (type == null) {
			return false;
		}
		boolean slider = this.metadata.uiLayout != null && this.metadata.uiLayout.isSlider(option.path());
		return switch (type.toLowerCase(Locale.ROOT)) {
			case "page", "bool", "enum" -> true;
			case "int", "float" -> slider || option.min() != null || option.max() != null;
			default -> false;
		};
	}

	private void onOptionClicked(ShaderpackOptionDecl option) {
		String type = option.type();
		if (type == null) {
			return;
		}
		if (type.equalsIgnoreCase("page")) {
			String title = this.pageTitle + " > " + resolveLabelString(option);
			this.client.setScreen(new SulkanShaderpackSettingsScreen(
				this,
				this.metadata,
				this.session,
				option.children(),
				title,
				option.path(),
				this.allOptionsByPath
			));
			return;
		}
		ShaderpackConfig config = session.config();
		Object current = config.getValue(option.path());
		Object next = nextValue(option, current);
		if (next != null && (current == null || !current.equals(next))) {
			config.setValue(option.path(), next);
			this.session.markDirty();
			updateEntryValue(option);
			updateButtons();
		}
	}

	private Object nextValue(ShaderpackOptionDecl option, Object current) {
		String type = option.type();
		if (type == null) {
			return null;
		}
		switch (type.toLowerCase(Locale.ROOT)) {
			case "bool" -> {
				boolean value = current instanceof Boolean b ? b : Boolean.TRUE.equals(option.defaultValue());
				return !value;
			}
			case "enum" -> {
				if (option.values().isEmpty()) {
					return current;
				}
				String currentValue = current instanceof String str ? str : (String) option.defaultValue();
				int index = option.values().indexOf(currentValue);
				int nextIndex = index < 0 ? 0 : (index + 1) % option.values().size();
				return option.values().get(nextIndex);
			}
			case "int" -> {
				long value = current instanceof Number number
					? number.longValue()
					: option.defaultValue() instanceof Number number ? number.longValue() : 0L;
				long step = option.step() == null ? 1L : Math.max(1L, option.step().longValue());
				long min = option.min() == null ? Long.MIN_VALUE : option.min().longValue();
				long max = option.max() == null ? Long.MAX_VALUE : option.max().longValue();
				long next = value + step;
				if (next > max) {
					next = min == Long.MIN_VALUE ? max : min;
				}
				if (next < min) {
					next = min;
				}
				return next;
			}
			case "float" -> {
				double value = current instanceof Number number
					? number.doubleValue()
					: option.defaultValue() instanceof Number number ? number.doubleValue() : 0.0D;
				double step = option.step() == null ? 0.05D : Math.max(0.0001D, option.step().doubleValue());
				double min = option.min() == null ? -Double.MAX_VALUE : option.min().doubleValue();
				double max = option.max() == null ? Double.MAX_VALUE : option.max().doubleValue();
				double next = value + step;
				if (next > max) {
					next = min <= -Double.MAX_VALUE ? max : min;
				}
				if (next < min) {
					next = min;
				}
				return next;
			}
			default -> {
				return null;
			}
		}
	}

	private void updateEntryValue(ShaderpackOptionDecl option) {
		SulkanOptionEntryWidget widget = this.widgetByPath.get(option.path());
		if (widget != null) {
			widget.setValueText(resolveValueText(option));
		}
	}

	private Text resolveLabel(ShaderpackOptionDecl option) {
		String label = resolveLabelString(option);
		return Text.literal(label);
	}

	private String resolveLabelString(ShaderpackOptionDecl option) {
		String label = null;
		if (option.labelKey() != null && this.metadata.translations.containsKey(option.labelKey())) {
			label = this.metadata.translations.get(option.labelKey());
		}
		if (label == null || label.isBlank()) {
			label = option.label();
		}
		if (label == null || label.isBlank()) {
			label = option.path();
		}
		return label;
	}

	private Text resolveDescription(ShaderpackOptionDecl option) {
		String description = null;
		if (option.descriptionKey() != null && this.metadata.translations.containsKey(option.descriptionKey())) {
			description = this.metadata.translations.get(option.descriptionKey());
		}
		if (description == null || description.isBlank()) {
			description = option.description();
		}
		if (description == null || description.isBlank()) {
			return null;
		}
		return Text.literal(description);
	}

	private Text resolveValueText(ShaderpackOptionDecl option) {
		String type = option.type();
		if (type == null) {
			return Text.literal("-");
		}
		if (type.equalsIgnoreCase("page")) {
			return Text.translatable("sulkan.shaderpack_settings.open");
		}
		Object value = this.session.config().getValue(option.path());
		if (type.equalsIgnoreCase("bool") && value instanceof Boolean bool) {
			return bool ? ScreenTexts.ON : ScreenTexts.OFF;
		}
		if (type.equalsIgnoreCase("enum") && value instanceof String stringValue) {
			return Text.literal(stringValue);
		}
		if (type.equalsIgnoreCase("int") && value instanceof Number number) {
			return Text.literal(Long.toString(number.longValue()));
		}
		if (type.equalsIgnoreCase("float") && value instanceof Number number) {
			return Text.literal(String.format(Locale.ROOT, "%.3f", number.doubleValue()));
		}
		if (value == null) {
			return Text.literal("-");
		}
		return Text.literal(String.valueOf(value));
	}

	private void applyChanges() {
		if (!this.session.isDirty()) {
			return;
		}
		this.session.config().save(this.metadata.sourcePath);
		ShaderpackManager.refreshActiveConfig();
		this.session.clearDirty();
		this.updateButtons();
		this.setStatus(Text.translatable("sulkan.shaderpack_settings.status.saved"), true);
		this.reloadResources();
	}

	private void reloadResources() {
		MinecraftClient client = this.client;
		if (client == null) {
			return;
		}
		this.setStatus(Text.translatable("sulkan.shaderpack_settings.status.reloading"), true);
		SulkanVulkanPipelineReloader.reloadResourcesAndPipelines(client).thenRun(() -> client.execute(() -> {
			if (this.client != null && this.client.currentScreen == this) {
				this.setStatus(Text.translatable("sulkan.shaderpack_settings.status.reloaded"), true);
			}
		}));
	}

	private void updateButtons() {
		if (this.applyButton != null) {
			this.applyButton.active = this.session.isDirty();
		}
	}

	private void setStatus(Text message, boolean success) {
		this.statusMessage = message;
		this.statusUntil = Util.getMeasuringTimeMs() + 4000;
		this.statusColor = success
			? ColorUtil.ARGB.pack(0.4f, 0.9f, 0.4f, 1.0f)
			: ColorUtil.ARGB.pack(0.9f, 0.4f, 0.4f, 1.0f);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		if (!this.initialized || this.optionList == null || this.applyButton == null || this.backButton == null) {
			return;
		}
		GuiRenderer.guiGraphics = context;
		VRenderSystem.enableBlend();
		GuiRenderer.drawCenteredString(this.textRenderer, this.title, this.width / 2, 12, -1);
		this.optionList.updateState(mouseX, mouseY);
		this.optionList.renderWidget(mouseX, mouseY);
		for (VButtonWidget profileButton : this.profileButtons) {
			profileButton.render(mouseX, mouseY);
		}
		this.applyButton.render(mouseX, mouseY);
		this.backButton.render(mouseX, mouseY);
		this.renderTooltip(mouseX, mouseY);
		if (this.statusMessage != null && Util.getMeasuringTimeMs() < this.statusUntil) {
			GuiRenderer.drawCenteredString(this.textRenderer, this.statusMessage, this.width / 2, this.buttonRowY - 12, this.statusColor);
		}
	}

	private void renderTooltip(int mouseX, int mouseY) {
		SulkanOptionEntryWidget hovered = this.optionList.getHoveredWidget(mouseX, mouseY);
		if (hovered == null) {
			return;
		}
		Text tooltip = hovered.getTooltip();
		if (tooltip == null) {
			return;
		}
		int maxWidth = Math.min(260, this.width - 20);
		List<OrderedText> lines = this.textRenderer.wrapLines(tooltip, maxWidth);
		int width = GuiRenderer.getMaxTextWidth(this.textRenderer, lines);
		int height = lines.size() * 10;
		int padding = 3;
		int x = Math.min(mouseX + 12, this.width - width - padding - 2);
		int y = Math.min(mouseY + 12, this.height - height - padding - 2);
		int background = ColorUtil.ARGB.pack(0.05f, 0.05f, 0.05f, 0.7f);
		GuiRenderer.fill(x - padding, y - padding, x + width + padding, y + height + padding, background);
		int border = ColorUtil.ARGB.pack(0.3f, 0.0f, 0.0f, 0.6f);
		GuiRenderer.renderBorder(x - padding, y - padding, x + width + padding, y + height + padding, 1, border);
		int yOffset = 0;
		for (OrderedText line : lines) {
			GuiRenderer.drawString(this.textRenderer, line, x, y + yOffset, -1);
			yOffset += 10;
		}
	}
}
