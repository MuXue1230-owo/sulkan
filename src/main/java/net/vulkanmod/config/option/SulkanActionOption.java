package net.vulkanmod.config.option;

import java.util.function.Supplier;
import net.minecraft.text.Text;
import net.vulkanmod.config.gui.widget.OptionWidget;
import net.vulkanmod.config.gui.widget.SulkanActionOptionWidget;

public final class SulkanActionOption extends Option<Boolean> {
	private final Text actionLabel;
	private final Runnable action;

	public SulkanActionOption(Text name, Text actionLabel, Runnable action, Supplier<Boolean> activeSupplier) {
		super(name, value -> { }, () -> Boolean.FALSE);
		this.actionLabel = actionLabel;
		this.action = action;
		this.setActivationFn(activeSupplier);
	}

	public SulkanActionOption(Text name, Text actionLabel, Runnable action) {
		this(name, actionLabel, action, () -> true);
	}

	@Override
	OptionWidget<?> createWidget() {
		return new SulkanActionOptionWidget(this, this.name);
	}

	@Override
	public boolean isChanged() {
		return false;
	}

	@Override
	public void apply() {
	}

	public Text getActionLabel() {
		return actionLabel;
	}

	public void press() {
		action.run();
	}

	@Override
	public Text getDisplayedValue() {
		return actionLabel;
	}
}
