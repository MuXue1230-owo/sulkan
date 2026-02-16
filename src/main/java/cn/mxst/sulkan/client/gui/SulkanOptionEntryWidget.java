package cn.mxst.sulkan.client.gui;

import java.util.Objects;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.Text;
import net.vulkanmod.config.gui.render.GuiRenderer;
import net.vulkanmod.config.gui.widget.VAbstractWidget;
import net.vulkanmod.vulkan.util.ColorUtil;

public final class SulkanOptionEntryWidget extends VAbstractWidget {
	private final String id;
	private final Text label;
	private final Runnable onClick;
	private Text valueText;
	private Text tooltip;

	public SulkanOptionEntryWidget(String id, Text label, Text valueText, Runnable onClick) {
		this.id = id;
		this.label = label;
		this.valueText = valueText;
		this.onClick = onClick;
	}

	public String getId() {
		return id;
	}

	public Text getTooltip() {
		return tooltip;
	}

	public void setTooltip(Text tooltip) {
		this.tooltip = tooltip;
	}

	public void setValueText(Text valueText) {
		if (!Objects.equals(this.valueText, valueText)) {
			this.valueText = valueText;
		}
	}

	@Override
	public void renderWidget(double mouseX, double mouseY) {
		TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
		int background = ColorUtil.ARGB.pack(0.0f, 0.0f, 0.0f, this.active ? 0.45f : 0.3f);
		GuiRenderer.fill(this.x, this.y, this.x + this.width, this.y + this.height, background);
		if (this.active) {
			this.renderHovering(0, 0);
		}
		int color = this.active ? -1 : -6250336;
		int textY = this.y + (this.height - 8) / 2;
		GuiRenderer.drawString(textRenderer, this.label.asOrderedText(), this.x + 8, textY, color);
		if (this.valueText != null) {
			int valueWidth = textRenderer.getWidth(this.valueText);
			int valueX = this.x + this.width - valueWidth - 8;
			GuiRenderer.drawString(textRenderer, this.valueText.asOrderedText(), valueX, textY, color);
		}
	}

	@Override
	public void onClick(double mouseX, double mouseY) {
		if (this.active && this.onClick != null) {
			this.onClick.run();
		}
	}
}
