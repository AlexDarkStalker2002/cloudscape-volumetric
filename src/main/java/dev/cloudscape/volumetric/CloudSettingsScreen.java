package dev.cloudscape.volumetric;

import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gui.screen.ShaderPackScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** A detached draft survives tab changes. Only Apply changes runtime or disk. */
public final class CloudSettingsScreen extends Screen {
    private final Screen parent;
    private CloudConfig draft;
    private int page, slot;
    private int left, columnWidth;
    private Component message;
    private boolean failed;
    private Button qualityButton;
    private String errorDetail;

    public CloudSettingsScreen(Screen parent) {
        super(text("title"));
        this.parent = parent;
        draft = UnboundAdapter.settings().copy();
        String notice = CloudSettings.notice();
        message = text(notice.isEmpty() ? "reload" : notice);
    }

    public static Component text(String key, Object... args) {
        return Component.translatable("cloudscape." + key, args);
    }

    @Override protected void init() {
        int total = Math.min(width - 24, 440);
        left = (width - total) / 2;
        columnWidth = (total - 8) / 2;
        slot = 0;
        qualityButton = null;
        for (int index = 0; index < 4; index++) {
            final int target = index;
            var tab = addRenderableWidget(Button.builder(text("page." + index), b -> { page = target; rebuildWidgets(); })
                .bounds(left + index * (total + 6) / 4, 30, (total - 18) / 4, 20).build());
            tab.active = page != index;
        }
        if (page == 0) {
            button("enabled", draft.enabled ? text("on") : text("off"), () -> draft.enabled = !draft.enabled);
            slider("coverage", .1, .95, .01, () -> draft.coverage, v -> draft.coverage = (float)v);
            slider("density", .15, 3, .05, () -> draft.density, v -> draft.density = (float)v);
            slider("windSpeed", 0, 4, .05, () -> draft.windSpeed, v -> draft.windSpeed = (float)v);
            addRenderableWidget(Button.builder(text("style"), b -> minecraft.gui.setScreen(new ShaderPackScreen(this)))
                .bounds(x(), y(), columnWidth, 20).tooltip(Tooltip.create(text("style.tip"))).build()); slot++;
            slider("reimaginedMorphSpeed", 0, 4, .05, () -> draft.reimaginedMorphSpeed, v -> draft.reimaginedMorphSpeed = (float)v);
            slider("reimaginedTransmission", .3, 2, .05, () -> draft.reimaginedTransmission, v -> draft.reimaginedTransmission = (float)v);
            slider("reimaginedLightContrast", 0, 1, .05, () -> draft.reimaginedLightContrast, v -> draft.reimaginedLightContrast = (float)v);
        } else if (page == 1) {
            button("quality", text("quality." + CloudQuality.of(draft).name().toLowerCase(Locale.ROOT)),
                () -> CloudQuality.of(draft).next().set(draft));
            qualityButton = (Button)children().get(children().size() - 1);
            slider("viewSteps", 24, 192, 1, () -> draft.viewSteps, v -> draft.viewSteps = (int)v);
            slider("reflectionSteps", 8, 64, 1, () -> draft.reflectionSteps, v -> draft.reflectionSteps = (int)v);
            slider("lightSteps", 2, 8, 1, () -> draft.lightSteps, v -> draft.lightSteps = (int)v);
            slider("maxDistance", 1500, 10000, 100, () -> draft.maxDistance, v -> draft.maxDistance = (float)v);
            button("reimaginedDensityCache", draft.reimaginedDensityCache ? text("on") : text("off"),
                () -> draft.reimaginedDensityCache = !draft.reimaginedDensityCache);
            button("densityAtlasSize", text("atlas.value",draft.densityAtlasSize,(long)draft.densityAtlasSize*draft.densityAtlasSize*64/1048576), () -> {
                int[] sizes = {256,512,768,1024,1536,4096};
                for (int i = 0; i < sizes.length; i++) if (draft.densityAtlasSize == sizes[i]) {
                    draft.densityAtlasSize = sizes[(i + 1) % sizes.length]; break;
                }
            });
        } else if (page == 3) {
            button("shapeMode", text("shapeMode." + draft.shapeMode), () -> draft.shapeMode = 1 - draft.shapeMode);
            button("weatherVariability", draft.weatherVariability ? text("on") : text("off"),
                () -> draft.weatherVariability = !draft.weatherVariability);
            slider("transitionSeconds", 10, 600, 10, () -> draft.transitionSeconds, v -> draft.transitionSeconds = (int)v);
        } else {
            slider("reimaginedThickness", 24, 128, 1, () -> draft.reimaginedThickness, v -> draft.reimaginedThickness = (float)v);
            slider("thickness", 60, 360, 1, () -> draft.thickness, v -> draft.thickness = (float)v);
            slider("reimaginedEdgeSoftness", .5, 3, .05, () -> draft.reimaginedEdgeSoftness, v -> draft.reimaginedEdgeSoftness = (float)v);
            button("reimaginedClusterSize", Component.literal(Integer.toString(draft.reimaginedClusterSize)),
                () -> draft.reimaginedClusterSize = draft.reimaginedClusterSize == 128 ? 256 : draft.reimaginedClusterSize == 256 ? 512 : 128);
            slider("reimaginedTowerStrength", 0, 1, .05, () -> draft.reimaginedTowerStrength, v -> draft.reimaginedTowerStrength = (float)v);
            button("reimaginedVoxelSize", Component.literal(Integer.toString(draft.reimaginedVoxelSize)),
                () -> draft.reimaginedVoxelSize = draft.reimaginedVoxelSize == 4 ? 8 : draft.reimaginedVoxelSize == 8 ? 16 : 4);
            slider("baseOffset", -128, 512, 1, () -> draft.baseOffset, v -> draft.baseOffset = (float)v);
            slider("detail", 0, .5, .01, () -> draft.detail, v -> draft.detail = (float)v);
        }
        int footer = height - 27, third = (total - 12) / 3;
        addRenderableWidget(Button.builder(text("apply"), b -> apply()).bounds(left, footer, third, 20)
            .tooltip(Tooltip.create(errorDetail == null ? text("reload") : Component.literal(errorDetail))).build());
        addRenderableWidget(Button.builder(text("cancel"), b -> onClose()).bounds(left + third + 6, footer, third, 20).build());
        addRenderableWidget(Button.builder(text("reset"), b -> { draft = new CloudConfig(); message = text("reset.notice"); failed = false; errorDetail = null; rebuildWidgets(); })
            .bounds(left + 2 * (third + 6), footer, third, 20).tooltip(Tooltip.create(text("reset.tip"))).build());
    }

    private int x() { return left + slot % 2 * (columnWidth + 8); }
    private int y() { return 58 + slot / 2 * 24; }
    private void button(String key, Component value, Runnable change) {
        addRenderableWidget(Button.builder(text(key).copy().append(": ").append(value), b -> { change.run(); rebuildWidgets(); })
            .bounds(x(), y(), columnWidth, 20).tooltip(Tooltip.create(text(key + ".tip"))).build()); slot++;
    }

    private void slider(String key, double min, double max, double step, DoubleSupplier getter, DoubleConsumer setter) {
        var slider = new AbstractSliderButton(x(), y(), columnWidth, 20, Component.empty(), (getter.getAsDouble() - min) / (max - min)) {
            @Override protected void updateMessage() {
                double current = getter.getAsDouble();
                setMessage(text(key).copy().append(": " + (step >= 1 ? String.format(Locale.ROOT,"%.0f",current) : String.format(Locale.ROOT,"%.2f",current))));
            }
            @Override protected void applyValue() {
                setter.accept(Math.clamp(min + Math.round(value * (max - min) / step) * step, min, max));
            }
        };
        double current = getter.getAsDouble();
        slider.setMessage(text(key).copy().append(": " + (step >= 1 ? String.format(Locale.ROOT,"%.0f",current) : String.format(Locale.ROOT,"%.2f",current))));
        slider.setTooltip(Tooltip.create(text(key + ".tip")));
        addRenderableWidget(slider); slot++;
    }

    private void apply() {
        try {
            CloudSettings.apply(draft);
            draft = UnboundAdapter.settings().copy();
            message = text("saved"); failed = false; errorDetail = null;
        } catch (Exception error) {
            UnboundAdapter.LOGGER.error("Cloudscape settings apply failed; previous settings restored", error);
            message = text(error.getSuppressed().length == 0 ? "apply.failed" : "apply.recoveryFailed");
            errorDetail = error.toString();
            failed = true;
        }
        rebuildWidgets();
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (qualityButton != null) qualityButton.setMessage(text("quality").copy().append(": ")
            .append(text("quality." + CloudQuality.of(draft).name().toLowerCase(Locale.ROOT))));
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, 10, 0xFFFFFFFF);
        Component status = Iris.isPackInUseQuick() ? text(UnboundAdapter.diagnostic()) : text("diagnostic.noPack");
        if (page == 1 && Iris.isPackInUseQuick() && UnboundAdapter.diagnostic().equals("diagnostic.active"))
            status = status.copy().append("\n").append(cacheStatus());
        graphics.textWithWordWrap(font, status, left, height - 84, columnWidth * 2 + 8, 0xFFB9DBFF);
        graphics.textWithWordWrap(font, message, left, height - 56, columnWidth * 2 + 8, failed ? 0xFFFF9D9D : 0xFFE0E0E0);
    }

    @Override public void onClose() { minecraft.gui.setScreen(parent); }

    private Component cacheStatus() {
        var pack = Iris.getCurrentPack();
        if (pack.isPresent() && UnboundAdapter.diagnostic().equals("diagnostic.active")) {
            var options = pack.get().getShaderPackOptions().getOptionValues();
            String selected = options.getStringValueOrDefault("CLOUD_STYLE_DEFINE");
            boolean reimagined = selected.equals("1") || selected.equals("-1") && options.getStringValueOrDefault("SHADER_STYLE").equals("1");
            if (!reimagined || options.getStringValueOrDefault("CLOUD_QUALITY").equals("0")) return text("atlas.unused");
        }
        String state=WorldDensityCache.diagnostic();int size=WorldDensityCache.effectiveSize();
        if(size>0 && (state.equals("atlas.ready") || state.equals("atlas.smaller")))
            return text(state.equals("atlas.ready")?"atlas.actual":"atlas.actualSmaller",size,(long)size*size*64/1048576);
        return text(state);
    }
}
