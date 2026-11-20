package net.anubis.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import net.anubis.client.config.ModuleConfigStore;
import net.anubis.client.module.ClientModule;
import net.anubis.client.module.ModuleCategory;
import net.anubis.client.module.ModuleRegistry;
import net.anubis.client.module.setting.ActionSetting;
import net.anubis.client.module.setting.BooleanSetting;
import net.anubis.client.module.setting.ColorSetting;
import net.anubis.client.module.setting.KeySetting;
import net.anubis.client.module.setting.ModeSetting;
import net.anubis.client.module.setting.NumberSetting;
import net.anubis.client.module.setting.Setting;
import net.anubis.client.module.setting.StringSetting;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Touch-friendly ClickGUI built only from vanilla widgets. Used when ImGui natives are not
 * available (PojavLauncher / Android). It drives the same modules and settings as the ImGui GUI,
 * so every module keeps working and saved configs stay compatible.
 * Tap = select/toggle, drag = scroll, drag on a slider = change value.
 */
@Environment(EnvType.CLIENT)
public final class MobileClickGuiScreen extends Screen {
    private static final int TOP = 22;
    private static final int GAP = 4;
    private static final int RH = 20;
    private static final int UNBOUND = -1;
    private static final int COL_BG = 0xFF0E1216;
    private static final int COL_PANEL = 0xF01A1F26;
    private static final int COL_ROW = 0xFF232A33;
    private static final int COL_SEL = 0xFF30404F;
    private static final int COL_ACCENT = 0xFF39C6B0;
    private static final int COL_ON = 0xFF3DDC84;
    private static final int COL_OFF = 0xFF7A8290;
    private static final int COL_TEXT = 0xFFFFFFFF;
    private static final int COL_DIM = 0xFFA0A8B3;
    private static final int[] PALETTE = {
        0xFFFFFF, 0xFF4040, 0xFF8A00, 0xFFD400, 0x3DDC84, 0x39C6B0, 0x3FA9FF, 0x6C63FF, 0xC85BFF, 0xFF5BB5, 0x9AA3AF, 0x000000
    };

    private record Hit(int x0, int y0, int x1, int y1, Runnable tap, NumberSetting slider, int sx, int sw, boolean overlay) {
        boolean contains(double x, double y) {
            return x >= this.x0 && x < this.x1 && y >= this.y0 && y < this.y1;
        }

        boolean sameRect(Hit o) {
            return o != null && this.x0 == o.x0 && this.y0 == o.y0 && this.x1 == o.x1 && this.y1 == o.y1;
        }
    }

    private final List<Hit> hits = new ArrayList<>();
    private int cat;
    private ClientModule selected;
    private float scrollCats;
    private float scrollMods;
    private float scrollSets;
    private int contentCats;
    private int contentMods;
    private int contentSets;
    private int catX;
    private int catW;
    private int modX;
    private int modW;
    private int setX;
    private int setW;
    private int panelTop;
    private int panelBottom;
    private int cursorY;

    private int pressedPanel = -1;
    private double pressX;
    private double pressY;
    private double lastY;
    private boolean moved;
    private Hit pressedHit;
    private NumberSetting dragNumber;
    private int dragSx;
    private int dragSw;

    private EditBox editBox;
    private StringSetting editing;
    private IntConsumer listening;
    private Object listeningTarget;

    public MobileClickGuiScreen() {
        super(Component.literal("ANUBIS"));
    }

    public boolean capturesKeys() {
        return this.editBox != null || this.listening != null;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        super.removed();
        ModuleConfigStore.getInstance().saveIfChanged();
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0xC0000000);
    }

    private List<ModuleCategory> categories() {
        List<ModuleCategory> out = new ArrayList<>();
        for (ModuleCategory c : ModuleCategory.values()) {
            if (!ModuleRegistry.inCategory(c).isEmpty()) {
                out.add(c);
            }
        }

        return out;
    }

    private void layout() {
        this.catX = GAP;
        this.catW = Math.max(80, this.width / 6);
        int rest = this.width - this.catW - GAP * 4;
        this.modX = this.catX + this.catW + GAP;
        this.modW = (int)(rest * 0.40F);
        this.setX = this.modX + this.modW + GAP;
        this.setW = this.width - this.setX - GAP;
        this.panelTop = TOP + GAP;
        this.panelBottom = this.height - GAP - (this.editBox != null ? 40 : 0);
    }

    private String fit(String s, int max) {
        if (s == null) {
            return "";
        }

        if (this.font.width(s) <= max) {
            return s;
        }

        return this.font.plainSubstrByWidth(s, Math.max(0, max - this.font.width("..."))) + "...";
    }

    private void text(GuiGraphics g, String s, int x, int y, int color) {
        g.drawString(this.font, s, x, y, color);
    }

    private void addHit(int x0, int y0, int x1, int y1, Runnable tap, NumberSetting slider, int sx, int sw) {
        if (y1 <= this.panelTop || y0 >= this.panelBottom) {
            return;
        }

        this.hits.add(new Hit(x0, Math.max(y0, this.panelTop), x1, Math.min(y1, this.panelBottom), tap, slider, sx, sw, false));
    }

    private void button(GuiGraphics g, int x, int y, int w, int h, String label, int color, Runnable tap, boolean overlay) {
        g.fill(x, y, x + w, y + h, color);
        int tx = x + (w - this.font.width(label)) / 2;
        this.text(g, label, tx, y + (h - this.font.lineHeight) / 2 + 1, COL_TEXT);
        this.hits.add(new Hit(x, y, x + w, y + h, tap, null, 0, 0, overlay));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (this.selected != null && !ModuleRegistry.all().contains(this.selected)) {
            this.selected = null;
        }

        this.gfx = g;
        this.hits.clear();
        this.layout();
        g.fill(0, 0, this.width, TOP, COL_BG);
        this.text(g, "ANUBIS", 8, 7, COL_ACCENT);
        this.text(g, "tap: select/toggle  |  drag: scroll", 8 + this.font.width("ANUBIS  "), 7, COL_DIM);
        int cw = 52;
        this.button(g, this.width - cw - 6, 3, cw, 16, "Close", 0xFF5A2A2A, this::onClose, false);

        List<ModuleCategory> cats = this.categories();
        if (this.cat >= cats.size()) {
            this.cat = 0;
        }

        this.renderCategories(g, cats);
        this.renderModules(g, cats);
        this.renderSettings(g);
        if (this.editBox != null) {
            this.renderEditor(g, mouseX, mouseY, partialTick);
        }
    }

    private void panel(GuiGraphics g, int x, int w) {
        g.fill(x, this.panelTop, x + w, this.panelBottom, COL_PANEL);
    }

    private float clampScroll(float scroll, int content) {
        int view = this.panelBottom - this.panelTop - 4;
        return Math.max(0.0F, Math.min(scroll, Math.max(0, content - view)));
    }

    private void renderCategories(GuiGraphics g, List<ModuleCategory> cats) {
        this.panel(g, this.catX, this.catW);
        this.scrollCats = this.clampScroll(this.scrollCats, this.contentCats);
        g.enableScissor(this.catX, this.panelTop, this.catX + this.catW, this.panelBottom);
        int y = this.panelTop + 2 - (int)this.scrollCats;
        int start = y;
        for (int i = 0; i < cats.size(); i++) {
            final int index = i;
            boolean sel = i == this.cat;
            g.fill(this.catX + 3, y, this.catX + this.catW - 3, y + RH - 2, sel ? COL_SEL : COL_ROW);
            this.text(g, this.fit(cats.get(i).displayName(), this.catW - 14), this.catX + 7, y + 6, sel ? COL_ACCENT : COL_TEXT);
            this.addHit(this.catX + 3, y, this.catX + this.catW - 3, y + RH - 2, () -> {
                this.cat = index;
                this.selected = null;
                this.scrollMods = 0.0F;
                this.scrollSets = 0.0F;
                this.cancelEdit();
                this.listening = null;
                this.listeningTarget = null;
            }, null, 0, 0);
            y += RH;
        }

        this.contentCats = y - start + 4;
        g.disableScissor();
    }

    private void renderModules(GuiGraphics g, List<ModuleCategory> cats) {
        this.panel(g, this.modX, this.modW);
        if (cats.isEmpty()) {
            return;
        }

        List<ClientModule> mods = ModuleRegistry.inCategory(cats.get(this.cat));
        this.scrollMods = this.clampScroll(this.scrollMods, this.contentMods);
        g.enableScissor(this.modX, this.panelTop, this.modX + this.modW, this.panelBottom);
        int y = this.panelTop + 2 - (int)this.scrollMods;
        int start = y;
        int pillW = 32;
        for (ClientModule m : mods) {
            boolean sel = m == this.selected;
            int x0 = this.modX + 3;
            int x1 = this.modX + this.modW - 3;
            g.fill(x0, y, x1, y + RH - 2, sel ? COL_SEL : COL_ROW);
            this.text(g, this.fit(m.name(), this.modW - pillW - 18), x0 + 4, y + 6, m.isEnabled() ? COL_TEXT : COL_DIM);
            int px0 = x1 - pillW - 2;
            int py0 = y + 2;
            boolean action = m.isAction();
            boolean on = m.isEnabled();
            g.fill(px0, py0, px0 + pillW, py0 + RH - 6, action ? COL_ACCENT : on ? COL_ON : COL_OFF);
            String label = action ? "RUN" : on ? "ON" : "OFF";
            this.text(g, label, px0 + (pillW - this.font.width(label)) / 2, py0 + 4, 0xFF0B0F13);
            this.addHit(x0, y, px0 - 1, y + RH - 2, () -> {
                this.selected = m;
                this.scrollSets = 0.0F;
                this.cancelEdit();
                this.listening = null;
                this.listeningTarget = null;
            }, null, 0, 0);
            this.addHit(px0, y, x1, y + RH - 2, () -> {
                if (action) {
                    m.onClick();
                } else {
                    m.toggle();
                }
            }, null, 0, 0);
            y += RH;
        }

        this.contentMods = y - start + 4;
        g.disableScissor();
    }

    private void renderSettings(GuiGraphics g) {
        this.panel(g, this.setX, this.setW);
        if (this.selected == null) {
            this.text(g, "Select a module", this.setX + 8, this.panelTop + 8, COL_DIM);
            return;
        }

        ClientModule m = this.selected;
        this.scrollSets = this.clampScroll(this.scrollSets, this.contentSets);
        g.enableScissor(this.setX, this.panelTop, this.setX + this.setW, this.panelBottom);
        this.cursorY = this.panelTop + 2 - (int)this.scrollSets;
        int start = this.cursorY;
        int tx = this.setX + 6;
        this.text(g, this.fit(m.name(), this.setW - 12), tx, this.cursorY + 2, COL_ACCENT);
        this.cursorY += 14;
        String desc = m.description();
        if (desc != null && !desc.isEmpty()) {
            List<FormattedCharSequence> lines = this.font.split(Component.literal(desc), this.setW - 12);
            for (FormattedCharSequence line : lines) {
                g.drawString(this.font, line, tx, this.cursorY, COL_DIM);
                this.cursorY += this.font.lineHeight + 1;
            }
        }

        this.cursorY += 4;
        this.row("Keybind", this.listeningTarget == m ? "Press a key..." : keyName(m.keybind()), COL_ACCENT, () -> {
            this.listeningTarget = m;
            this.listening = m::setKeybind;
        });
        this.row("Hold mode", m.isHoldMode() ? "ON" : "OFF", m.isHoldMode() ? COL_ON : COL_OFF, () -> m.setHoldMode(!m.isHoldMode()));
        for (Setting s : m.settings()) {
            if (s.isVisible() && !s.isSecret()) {
                this.settingRow(s);
            }
        }

        this.contentSets = this.cursorY - start + 6;
        g.disableScissor();
    }

    private GuiGraphics gfx;

    private void row(String label, String value, int valueColor, Runnable tap) {
        int x0 = this.setX + 3;
        int x1 = this.setX + this.setW - 3;
        int y = this.cursorY;
        GuiGraphics g = this.gfx;
        g.fill(x0, y, x1, y + RH - 2, COL_ROW);
        int vw = this.font.width(value);
        int maxValue = (int)((x1 - x0) * 0.5F);
        String shownValue = vw > maxValue ? this.fit(value, maxValue) : value;
        int shownW = this.font.width(shownValue);
        this.text(g, this.fit(label, x1 - x0 - shownW - 16), x0 + 4, y + 6, COL_TEXT);
        this.text(g, shownValue, x1 - 4 - shownW, y + 6, valueColor);
        if (tap != null) {
            this.addHit(x0, y, x1, y + RH - 2, tap, null, 0, 0);
        }

        this.cursorY += RH;
    }

    private void settingRow(Setting s) {
        String name = s.name();
        if (s instanceof BooleanSetting b) {
            this.row(name, b.get() ? "ON" : "OFF", b.get() ? COL_ON : COL_OFF, () -> b.set(!b.get()));
        } else if (s instanceof ModeSetting mode) {
            this.row(name, mode.get(), COL_ACCENT, mode::next);
        } else if (s instanceof NumberSetting n) {
            this.numberRow(n);
        } else if (s instanceof KeySetting k) {
            this.row(name, this.listeningTarget == k ? "Press a key..." : keyName(k.value()), COL_ACCENT, () -> {
                this.listeningTarget = k;
                this.listening = k::setValue;
            });
        } else if (s instanceof ColorSetting c) {
            this.colorRow(c);
        } else if (s instanceof StringSetting str) {
            String value = str.get();
            this.row(name, value == null || value.isEmpty() ? "(empty)" : value, COL_DIM, () -> this.startEdit(str));
        } else if (s instanceof ActionSetting a) {
            this.row(name, "RUN", COL_ACCENT, a::run);
        } else {
            this.row(name, "(PC only)", COL_DIM, null);
        }
    }

    private void numberRow(NumberSetting n) {
        int x0 = this.setX + 3;
        int x1 = this.setX + this.setW - 3;
        int y = this.cursorY;
        int h = RH + 8;
        GuiGraphics g = this.gfx;
        g.fill(x0, y, x1, y + h - 2, COL_ROW);
        String value = n.formatted();
        int vw = this.font.width(value);
        this.text(g, this.fit(n.name(), x1 - x0 - vw - 16), x0 + 4, y + 5, COL_TEXT);
        this.text(g, value, x1 - 4 - vw, y + 5, COL_ACCENT);
        int sx = x0 + 6;
        int sw = x1 - x0 - 12;
        int ty = y + RH - 4;
        g.fill(sx, ty, sx + sw, ty + 4, 0xFF0B0F13);
        float range = n.max() - n.min();
        float t = range <= 0.0F ? 0.0F : Math.max(0.0F, Math.min(1.0F, (n.get() - n.min()) / range));
        int fill = (int)(sw * t);
        g.fill(sx, ty, sx + fill, ty + 4, COL_ACCENT);
        g.fill(sx + fill - 2, ty - 3, sx + fill + 2, ty + 7, COL_TEXT);
        this.addHit(x0, ty - 7, x1, ty + 11, null, n, sx, sw);
        this.cursorY += h;
    }

    private void colorRow(ColorSetting c) {
        int rgb = c.argb() & 0xFFFFFF;
        this.row(c.name(), "#" + String.format("%06X", rgb), COL_DIM, () -> {
            int next = 0;
            for (int i = 0; i < PALETTE.length; i++) {
                if (PALETTE[i] == (c.argb() & 0xFFFFFF)) {
                    next = (i + 1) % PALETTE.length;
                    break;
                }
            }

            int alpha = c.argb() & 0xFF000000;
            if (alpha == 0) {
                alpha = 0xFF000000;
            }

            c.setArgb(alpha | PALETTE[next]);
        });
        int x1 = this.setX + this.setW - 3;
        int y = this.cursorY - RH;
        int vw = this.font.width("#" + String.format("%06X", rgb));
        this.gfx.fill(x1 - vw - 24, y + 3, x1 - vw - 8, y + RH - 5, 0xFF000000 | rgb);
    }

    private static String keyName(int key) {
        if (key < 0) {
            return "None";
        }

        if (key >= 1000) {
            return "Mouse " + (key - 1000 + 1);
        }

        try {
            return InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName().getString();
        } catch (RuntimeException e) {
            return "Key " + key;
        }
    }

    private void startEdit(StringSetting s) {
        this.cancelEdit();
        this.editing = s;
        this.editBox = new EditBox(this.font, 8, this.height - 30, Math.max(60, this.width - 16 - 140), 20, Component.literal(s.name()));
        this.editBox.setMaxLength(512);
        this.editBox.setValue(s.get() == null ? "" : s.get());
        this.editBox.setFocused(true);
    }

    private void cancelEdit() {
        this.editBox = null;
        this.editing = null;
    }

    private void commitEdit() {
        if (this.editBox != null && this.editing != null) {
            this.editing.set(this.editBox.getValue());
        }

        this.cancelEdit();
    }

    private void renderEditor(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, this.height - 40, this.width, this.height, COL_BG);
        if (this.editing != null) {
            this.text(g, this.fit(this.editing.name(), this.width - 16), 8, this.height - 39, COL_ACCENT);
        }

        this.editBox.setX(8);
        this.editBox.setY(this.height - 28);
        this.editBox.render(g, mouseX, mouseY, partialTick);
        int bx = this.width - 130;
        this.button(g, bx, this.height - 28, 60, 20, "OK", 0xFF1F6B45, this::commitEdit, true);
        this.button(g, bx + 66, this.height - 28, 60, 20, "Cancel", 0xFF5A2A2A, this::cancelEdit, true);
    }

    private Hit hitAt(double x, double y) {
        for (int i = this.hits.size() - 1; i >= 0; i--) {
            Hit h = this.hits.get(i);
            if (this.editBox != null && !h.overlay()) {
                continue;
            }

            if (h.contains(x, y)) {
                return h;
            }
        }

        return null;
    }

    private int panelAt(double x) {
        if (x >= this.catX && x < this.catX + this.catW) {
            return 0;
        }

        if (x >= this.modX && x < this.modX + this.modW) {
            return 1;
        }

        return x >= this.setX && x < this.setX + this.setW ? 2 : -1;
    }

    private void scrollBy(int panel, float delta) {
        switch (panel) {
            case 0 -> this.scrollCats += delta;
            case 1 -> this.scrollMods += delta;
            case 2 -> this.scrollSets += delta;
            default -> {
            }
        }
    }

    private void applySlider(double mx) {
        if (this.dragNumber == null || this.dragSw <= 0) {
            return;
        }

        float t = (float)Math.max(0.0, Math.min(1.0, (mx - this.dragSx) / this.dragSw));
        this.dragNumber.set(this.dragNumber.min() + t * (this.dragNumber.max() - this.dragNumber.min()));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        Hit h = this.hitAt(mx, my);
        this.pressX = mx;
        this.pressY = my;
        this.lastY = my;
        this.moved = false;
        this.pressedHit = h;
        this.dragNumber = null;
        this.pressedPanel = this.editBox != null ? -1 : this.panelAt(mx);
        if (h != null && h.slider() != null) {
            this.dragNumber = h.slider();
            this.dragSx = h.sx();
            this.dragSw = h.sw();
            this.applySlider(mx);
        }

        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        double mx = event.x();
        double my = event.y();
        if (this.dragNumber != null) {
            this.applySlider(mx);
            return true;
        }

        if (Math.abs(mx - this.pressX) > 5.0 || Math.abs(my - this.pressY) > 5.0) {
            this.moved = true;
        }

        if (this.moved && this.pressedPanel >= 0) {
            this.scrollBy(this.pressedPanel, (float)(this.lastY - my));
        }

        this.lastY = my;
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        boolean slider = this.dragNumber != null;
        this.dragNumber = null;
        if (!slider && !this.moved && this.pressedHit != null && this.pressedHit.tap() != null) {
            Hit now = this.hitAt(event.x(), event.y());
            if (this.pressedHit.sameRect(now)) {
                now.tap().run();
            }
        }

        this.pressedHit = null;
        this.pressedPanel = -1;
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int panel = this.panelAt(mouseX);
        if (panel >= 0) {
            this.scrollBy(panel, (float)(-scrollY * 20.0));
        }

        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (this.listening != null) {
            if (key == 259 || key == 261) {
                this.listening.accept(UNBOUND);
            } else if (key != 256) {
                this.listening.accept(key);
            }

            this.listening = null;
            this.listeningTarget = null;
            return true;
        }

        if (this.editBox != null) {
            if (key == 256) {
                this.cancelEdit();
                return true;
            }

            if (key == 257 || key == 335) {
                this.commitEdit();
                return true;
            }

            return this.editBox.keyPressed(event);
        }

        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return this.editBox != null ? this.editBox.charTyped(event) : super.charTyped(event);
    }
}
