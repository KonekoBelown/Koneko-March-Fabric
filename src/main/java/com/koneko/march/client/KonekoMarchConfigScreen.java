package com.koneko.march.client;

import com.koneko.march.KonekoMarchConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public final class KonekoMarchConfigScreen extends Screen {
    private static final int ENTRIES_PER_PAGE = 10;

    private final Screen parent;
    private final List<ButtonWidget> rowButtons = new ArrayList<>();
    private TextFieldWidget searchBox;
    private int page;
    private String lastSearch = "";
    private Text status = Text.empty();

    public KonekoMarchConfigScreen(Screen parent) {
        super(Text.translatable("screen.koneko.march.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.rowButtons.clear();
        int center = this.width / 2;
        this.searchBox = new TextFieldWidget(this.textRenderer, center - 150, 42, 300, 20, Text.translatable("screen.koneko.march.config.search"));
        this.searchBox.setMaxLength(128);
        this.searchBox.setPlaceholder(Text.translatable("screen.koneko.march.config.search_placeholder"));
        this.addDrawableChild(this.searchBox);
        this.setInitialFocus(this.searchBox);

        this.addDrawableChild(ButtonWidget.builder(Text.translatable("screen.koneko.march.config.reset"), button -> resetEntries())
                .dimensions(center - 150, 68, 70, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.translatable("screen.koneko.march.config.clear"), button -> clearEntries())
                .dimensions(center - 75, 68, 70, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.translatable("screen.koneko.march.config.enable_visible"), button -> enableVisibleEntries())
                .dimensions(center, 68, 70, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), button -> close())
                .dimensions(center + 75, 68, 75, 20).build());

        int rowX = center - 150;
        int rowY = 104;
        for (int i = 0; i < ENTRIES_PER_PAGE; i++) {
            final int row = i;
            ButtonWidget button = ButtonWidget.builder(Text.empty(), ignored -> toggleEntry(row))
                    .dimensions(rowX, rowY + i * 22, 300, 20)
                    .build();
            this.rowButtons.add(button);
            this.addDrawableChild(button);
        }

        this.addDrawableChild(ButtonWidget.builder(Text.literal("<"), button -> {
            if (this.page > 0) {
                this.page--;
                updateRowButtons();
            }
        }).dimensions(center - 150, this.height - 32, 45, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal(">"), button -> {
            int maxPage = getMaxPage(getVisibleEntries().size());
            if (this.page < maxPage) {
                this.page++;
                updateRowButtons();
            }
        }).dimensions(center + 105, this.height - 32, 45, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        String search = getSearchText();
        if (!search.equals(this.lastSearch)) {
            this.lastSearch = search;
            this.page = 0;
        }
        updateRowButtons();
        super.render(context, mouseX, mouseY, delta);

        int center = this.width / 2;
        List<String> visible = getVisibleEntries();
        List<String> enabled = KonekoMarchConfig.getAutoTargetEntityIds();
        int maxPage = getMaxPage(visible.size());
        if (this.page > maxPage) {
            this.page = maxPage;
        }

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, center, 14, 0xFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("screen.koneko.march.config.help"), center, 28, 0xA0A0A0);
        context.drawTextWithShadow(this.textRenderer, Text.translatable("screen.koneko.march.config.current", enabled.size(), visible.size()), center - 150, 91, 0xD0D0D0);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal((this.page + 1) + " / " + (maxPage + 1)), center, this.height - 27, 0xA0A0A0);
        context.drawCenteredTextWithShadow(this.textRenderer, this.status, center, this.height - 50, 0xE0E0E0);
    }

    private void updateRowButtons() {
        List<String> visible = getVisibleEntries();
        if (visible.isEmpty()) {
            this.page = 0;
        } else {
            this.page = Math.max(0, Math.min(this.page, getMaxPage(visible.size())));
        }
        Set<String> enabled = new LinkedHashSet<>(KonekoMarchConfig.getAutoTargetEntityIds());
        int start = this.page * ENTRIES_PER_PAGE;
        for (int i = 0; i < this.rowButtons.size(); i++) {
            ButtonWidget button = this.rowButtons.get(i);
            int index = start + i;
            boolean hasEntry = index < visible.size();
            button.visible = hasEntry;
            button.active = hasEntry;
            if (!hasEntry) {
                button.setMessage(Text.empty());
                continue;
            }
            String id = visible.get(index);
            boolean on = enabled.contains(id);
            button.setMessage(Text.literal((on ? "✓ " : "□ ") + id));
        }
    }

    private void toggleEntry(int row) {
        List<String> visible = getVisibleEntries();
        int index = this.page * ENTRIES_PER_PAGE + row;
        if (index < 0 || index >= visible.size()) {
            return;
        }
        String id = visible.get(index);
        List<String> enabled = new ArrayList<>(KonekoMarchConfig.getAutoTargetEntityIds());
        if (enabled.remove(id)) {
            KonekoMarchConfig.setAutoTargetEntityIds(enabled);
            this.status = Text.translatable("screen.koneko.march.config.disabled", id);
        } else if (Identifier.tryParse(id) != null || id.equals("*") || id.equals("*:*") || id.endsWith(":*")) {
            enabled.add(id);
            KonekoMarchConfig.setAutoTargetEntityIds(enabled);
            this.status = Text.translatable("screen.koneko.march.config.enabled", id);
        } else {
            this.status = Text.translatable("screen.koneko.march.config.invalid");
        }
        KonekoMarchClientConfigSync.sendToServer();
        updateRowButtons();
    }

    private void resetEntries() {
        KonekoMarchConfig.resetAutoTargetEntityIds();
        KonekoMarchClientConfigSync.sendToServer();
        this.page = 0;
        this.status = Text.translatable("screen.koneko.march.config.reset_done");
        updateRowButtons();
    }

    private void clearEntries() {
        KonekoMarchConfig.setAutoTargetEntityIds(List.of());
        KonekoMarchClientConfigSync.sendToServer();
        this.page = 0;
        this.status = Text.translatable("screen.koneko.march.config.clear_done");
        updateRowButtons();
    }

    private void enableVisibleEntries() {
        List<String> visible = getVisibleEntries();
        List<String> enabled = new ArrayList<>(KonekoMarchConfig.getAutoTargetEntityIds());
        for (String id : visible) {
            if (!enabled.contains(id) && (Identifier.tryParse(id) != null || id.equals("*") || id.equals("*:*") || id.endsWith(":*"))) {
                enabled.add(id);
            }
        }
        KonekoMarchConfig.setAutoTargetEntityIds(enabled);
        KonekoMarchClientConfigSync.sendToServer();
        this.status = Text.translatable("screen.koneko.march.config.enabled_visible", visible.size());
        updateRowButtons();
    }

    private List<String> getVisibleEntries() {
        String filter = getSearchText();
        List<String> result = new ArrayList<>();
        for (String id : getCandidateEntityIds()) {
            if (filter.isEmpty() || id.contains(filter)) {
                result.add(id);
            }
        }
        return result;
    }

    private List<String> getCandidateEntityIds() {
        Set<String> defaults = new LinkedHashSet<>(KonekoMarchConfig.defaultAutoTargetEntityIds());
        Set<String> configured = new LinkedHashSet<>(KonekoMarchConfig.getAutoTargetEntityIds());
        TreeSet<String> candidates = new TreeSet<>();
        for (EntityType<?> type : Registries.ENTITY_TYPE) {
            Identifier id = Registries.ENTITY_TYPE.getId(type);
            if (id == null) {
                continue;
            }
            String value = id.toString();
            if (type.getSpawnGroup() == SpawnGroup.MONSTER || defaults.contains(value) || configured.contains(value)) {
                candidates.add(value);
            }
        }
        candidates.addAll(defaults);
        candidates.addAll(configured);
        return new ArrayList<>(candidates);
    }

    private String getSearchText() {
        return this.searchBox == null ? "" : this.searchBox.getText().trim().toLowerCase();
    }

    private int getMaxPage(int size) {
        return Math.max(0, (size - 1) / ENTRIES_PER_PAGE);
    }

    @Override
    public void close() {
        KonekoMarchConfig.save();
        KonekoMarchClientConfigSync.sendToServer();
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }
}
