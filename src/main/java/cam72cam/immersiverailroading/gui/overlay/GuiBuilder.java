package cam72cam.immersiverailroading.gui.overlay;

import cam72cam.immersiverailroading.entity.EntityCoupleableRollingStock;
import cam72cam.immersiverailroading.entity.EntityMoveableRollingStock;
import cam72cam.immersiverailroading.entity.EntityRollingStock;
import cam72cam.immersiverailroading.entity.Locomotive;
import cam72cam.immersiverailroading.library.GuiText;
import cam72cam.mod.event.ClientEvents;
import cam72cam.mod.gui.helpers.GUIHelpers;
import cam72cam.mod.math.Vec3d;
import cam72cam.mod.net.Packet;
import cam72cam.mod.render.opengl.*;
import cam72cam.mod.resource.Identifier;
import cam72cam.mod.serialization.TagField;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import util.Matrix4;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;

public class GuiBuilder {
    private final float x;
    private final float y;
    private final boolean centerx;
    private final boolean centery;

    private final Identifier image;
    private final int imageWidth;
    private final int imageHeight;

    private final String text;
    private final float textHeight;

    private final Readouts readout;
    private final String control;
    private final boolean invert;
    private final boolean hide;
    private final float tlx;
    private final float tly;
    private final float rotx;
    private final float roty;
    private final float rotdeg;
    private final float rotoff;
    private final Float scalex;
    private final Float scaley;

    private final Map<Float, Integer> colors = new HashMap<>();

    private final List<GuiBuilder> elements;

    protected GuiBuilder(JsonObject data) throws IOException {
        // common stuff
        this.x = data.has("x") ? data.get("x").getAsFloat() : 0;
        this.y = data.has("y") ? data.get("y").getAsFloat() : 0;
        if (data.has("centered")) {
            JsonObject centered = data.get("centered").getAsJsonObject();
            this.centerx = centered.has("x") && centered.get("x").getAsBoolean();
            this.centery = centered.has("y") && centered.get("y").getAsBoolean();
        } else {
            this.centerx = this.centery = false;
        }

        // Image stuff
        this.image = data.has("image") ? new Identifier(data.get("image").getAsString()) : null;
        if (image != null) {
            BufferedImage tmp = ImageIO.read(this.image.getResourceStream());
            imageWidth = tmp.getWidth();
            imageHeight = tmp.getHeight();
        } else {
            imageWidth = 0;
            imageHeight = 0;
        }

        // Text stuff
        if (data.has("text")) {
            JsonObject txt = data.get("text").getAsJsonObject();
            text = txt.get("value").getAsString();
            textHeight = txt.has("height") ? txt.get("height").getAsFloat() : 8;
        } else {
            text = null;
            textHeight = 0;
        }

        // Controls
        this.readout = data.has("readout") ? Readouts.valueOf(data.get("readout").getAsString().toUpperCase(Locale.ROOT)) : null;
        this.control = data.has("control") ? data.get("control").getAsString() : null;
        this.invert = data.has("invert") && data.get("invert").getAsBoolean();
        this.hide = data.has("hide") && data.get("hide").getAsBoolean();

        if (data.has("translate")) {
            JsonObject tl = data.get("translate").getAsJsonObject();
            this.tlx = tl.has("x") ? tl.get("x").getAsFloat() : 0;
            this.tly = tl.has("y") ? tl.get("y").getAsFloat() : 0;
        } else {
            tlx = tly = 0;
        }

        if (data.has("rotate")) {
            JsonObject rot = data.get("rotate").getAsJsonObject();
            this.rotx = rot.has("x") ? rot.get("x").getAsFloat() : 0;
            this.roty = rot.has("y") ? rot.get("y").getAsFloat() : 0;
            this.rotdeg = rot.has("degrees") ? rot.get("degrees").getAsFloat() : 360;
            this.rotoff = rot.has("offset") ? rot.get("offset").getAsFloat() : 0;
        } else {
            this.rotx = 0;
            this.roty = 0;
            this.rotdeg = 0;
            this.rotoff = 0;
        }

        if (data.has("scale")) {
            JsonObject scale = data.get("scale").getAsJsonObject();
            this.scalex = scale.has("x") ? scale.get("x").getAsFloat() : null;
            this.scaley = scale.has("y") ? scale.get("y").getAsFloat() : null;
        } else {
            this.scalex = null;
            this.scaley = null;
        }

        if (data.has("color")) {
            for (Map.Entry<String, JsonElement> entry : data.get("color").getAsJsonObject().entrySet()) {
                String hex = entry.getValue().getAsString();
                if (hex.length() == 8) {
                    hex = hex.replace("0x", "0xFF");
                    hex = hex.replace("0X", "0XFF");
                }
                colors.put(Float.parseFloat(entry.getKey()), (int)(long)Long.decode(hex));
            }
        }

        // Children
        if (data.has("elements")) {
            elements = new ArrayList<>();
            for (JsonElement element : data.get("elements").getAsJsonArray()) {
                elements.add(new GuiBuilder(element.getAsJsonObject()));
            }
        } else {
            elements = Collections.emptyList();
        }
    }

    public static GuiBuilder parse(Identifier overlay) throws IOException {
        return new GuiBuilder(new JsonParser().parse(new InputStreamReader(overlay.getResourceStream())).getAsJsonObject());
    }

    private void applyPosition(Matrix4 matrix, int maxx, int maxy) {
        matrix.translate(this.x, this.y, 0);
        if (centerx) {
            matrix.translate(maxx/2f, 0, 0);
        }
        if (centery) {
            matrix.translate(0, maxy/2f, 0);
        }

        Vec3d offset = matrix.apply(Vec3d.ZERO);
        if (offset.x < 0) {
            matrix.translate(maxx, 0, 0);
        }
        if (offset.y < 0) {
            matrix.translate(0, maxy, 0);
        }
    }

    private float getValue(EntityRollingStock stock) {
        float value = 0;
        if (readout != null) {
            value = readout.getValue(stock);
        } else if (control != null) {
            value = stock.getControlPosition(control);
        }

        if (invert) {
            value = 1 - value;
        }

        return value;
    }

    private void applyValue(Matrix4 matrix, float value, int maxx, int maxy) {
        if (tlx != 0 || tly != 0) {
            matrix.translate(tlx * value, tly * value, 0);
        }
        if (rotdeg != 0) {
            matrix.translate(rotx, roty, 0);
            matrix.rotate(Math.toRadians(rotdeg * value + rotoff), 0, 0, 1);
            matrix.translate(-rotx, -roty, 0);
        }
        if (scalex != null || scaley != null) {
            matrix.scale(scalex != null ? scalex * value : 1, scaley != null ? scaley * value : 1, 1);
        }

        Vec3d offset = matrix.apply(Vec3d.ZERO);
        if (offset.x < 0) {
            matrix.translate(maxx, 0, 0);
        }
        if (offset.y < 0) {
            matrix.translate(0, maxy, 0);
        }
    }

    public void render(RenderState state, EntityRollingStock stock) {
        render(stock, state.clone().color(1, 1, 1, 1), GUIHelpers.getScreenWidth(), GUIHelpers.getScreenHeight());
    }

    private void render(EntityRollingStock stock, RenderState state, int maxx, int maxy) {
        float value = getValue(stock);
        if (hide && value != 1) {
            return;
        }

        state = state.clone(); // TODO mem opt?
        applyPosition(state.model_view(), maxx, maxy);
        applyValue(state.model_view(), value, maxx, maxy);

        Float colorKey = null;
        for (float key : colors.keySet()) {
            if (key <= value && (colorKey == null || key > colorKey)) {
                colorKey = key;
            }
        }

        int col = colors.getOrDefault(colorKey, 0xFFFFFFFF);
        if (colorKey != null) {
            state.color((col >> 16 & 255) / 255.0f, (col >> 8 & 255) / 255.0f, (col & 255) / 255.0f, (col >> 24 & 255) / 255.0f);
        }

        if (image != null) {
            DirectDraw draw = new DirectDraw();
            draw.vertex(0, 0, 0).uv(0, 0);
            draw.vertex(0, imageHeight, 0).uv(0, 1);
            draw.vertex(imageWidth, imageHeight, 0).uv(1, 1);
            draw.vertex(imageWidth, 0, 0).uv(1, 0);
            draw.draw(state.clone()
                    .texture(Texture.wrap(image))
                    .alpha_test(false)
                    .blend(new BlendMode(BlendMode.GL_SRC_ALPHA, BlendMode.GL_ONE_MINUS_SRC_ALPHA))
            );
        }
        if (text != null) {
            String out = text;
            for (Stat stat : Stat.values()) {
                if (out.contains(stat.toString())) {
                    out = out.replace(stat.toString(), stat.getValue(stock));
                }
            }
            for (GuiText label : new GuiText[]{GuiText.LABEL_THROTTLE, GuiText.LABEL_REVERSER, GuiText.LABEL_BRAKE}) {
                out = out.replace(label.getValue(), label.toString());
            }
            // Text is 8px tall
            float scale = textHeight / 8f;
            Matrix4 mat = state.model_view().copy();
            mat.scale(scale, scale, scale);
            GUIHelpers.drawCenteredString(out, 0, 0, col, mat);
        }
        for (GuiBuilder element : elements) {
            element.render(stock, state, maxx, maxy);
        }
    }

    private GuiBuilder find(EntityRollingStock stock, Matrix4 matrix, int maxx, int maxy, int x, int y) {
        float value = getValue(stock);
        if (hide && value != 1) {
            return null;
        }
        matrix = matrix.copy(); // TODO mem opt?
        applyPosition(matrix, maxx, maxy);
        applyValue(matrix, value, maxx, maxy);
        for (GuiBuilder element : elements) {
            GuiBuilder found = element.find(stock, matrix, maxx, maxy, x, y);
            if (found != null) {
                return found;
            }
        }

        if (image != null) {
            if (control == null) {
                if (readout == null) {
                    return null;
                }
                switch (readout) {
                    case THROTTLE:
                    case REVERSER:
                    case TRAIN_BRAKE:
                    case INDEPENDENT_BRAKE:
                    case COUPLER_FRONT:
                    case COUPLER_REAR:
                        break;
                    default:
                        return null;
                }
            }
            int border = 2;
            Vec3d cornerA = matrix.apply(new Vec3d(-border, -border, 0));
            Vec3d cornerB = matrix.apply(new Vec3d(imageWidth + border, imageHeight + border, 0));
            if (x >= cornerA.x && x <= cornerB.x || x >= cornerB.x && x <= cornerA.x) {
                if (y >= cornerA.y && y <= cornerB.y || y >= cornerB.y && y <= cornerA.y) {
                    return this;
                }
            }
        }
        return null;
    }

    private boolean hasMovement() {
        return tlx != 0 || tly != 0 || rotdeg != 0 || scalex != null || scaley != null;
    }

    private void onMouseMove(EntityRollingStock stock, Matrix4 matrix, GuiBuilder target, int maxx, int maxy, int x, int y) {
        float value = getValue(stock);
        matrix = matrix.copy(); // TODO mem opt?
        applyPosition(matrix, maxx, maxy);
        Matrix4 preApply = matrix.copy();
        applyValue(matrix, value, maxx, maxy);

        if (target == this) {
            // 0 0 imageHeight imageWidth

            float closestValue = value;
            double closestDelta = 999999;

            for (float checkValue = 0; checkValue <= 1; checkValue += 0.01) {
                Matrix4 temp = preApply.copy();
                if (tlx != 0 || tly != 0) {
                    temp.translate(tlx * checkValue, tly * checkValue, 0);
                }
                if (rotdeg != 0) {
                    temp.translate(rotx, roty, 0);
                    temp.rotate(Math.toRadians(rotdeg * checkValue + rotoff), 0, 0, 1);
                    temp.translate(-rotx, -roty, 0);
                }
                if (scalex != null || scaley != null) {
                    temp.scale(scalex != null ? scalex * checkValue : 1, scaley != null ? scaley * checkValue : 1, 1);
                }

                Vec3d checkMiddle = temp.apply(new Vec3d(1, 1, 0));
                double delta = checkMiddle.distanceTo(new Vec3d(x, y, 0));
                if (delta < closestDelta) {
                    closestDelta = delta;
                    closestValue = checkValue;
                }
            }

            if (closestValue != value) {
                new ControlChangePacket(stock, readout, control, closestValue).sendToServer();
            }
        } else {
            for (GuiBuilder element : elements) {
                element.onMouseMove(stock, matrix, target, maxx, maxy, x, y);
            }
        }
    }

    private static GuiBuilder target = null;
    public boolean click(ClientEvents.MouseGuiEvent event, EntityRollingStock stock) {
        switch (event.action) {
            case CLICK:
                target = find(stock, new Matrix4(), GUIHelpers.getScreenWidth(), GUIHelpers.getScreenHeight(), event.x, event.y);
                return target == null;
            case RELEASE:
                if (target != null) {
                    if (!target.hasMovement()) {
                        new ControlChangePacket(stock, target.readout, target.control, target.invert ? target.getValue(stock) : 1 - target.getValue(stock)).sendToServer();
                    }
                    target = null;
                    return false;
                }
                break;
            case MOVE:
                if (target != null && target.hasMovement()) {
                    onMouseMove(stock, new Matrix4(), target, GUIHelpers.getScreenWidth(), GUIHelpers.getScreenHeight(), event.x, event.y);
                    return false;
                }
                break;
        }
        return true;
    }

    public static class ControlChangePacket extends Packet {
        @TagField
        private UUID stockUUID;
        @TagField(typeHint = Readouts.class)
        private Readouts readout;
        @TagField
        private String controlGroup;
        @TagField
        private float value;

        public ControlChangePacket() {
            super(); // Reflection
        }

        public ControlChangePacket(EntityRollingStock stock, Readouts readout, String controlGroup, float value) {
            this.stockUUID = stock.getUUID();
            this.readout = readout;
            this.controlGroup = controlGroup;
            this.value = value;
        }

        @Override
        protected void handle() {
            EntityRollingStock stock = getWorld().getEntity(stockUUID, EntityRollingStock.class);
            if (stock != null) {
                // TODO permissions!
                if (controlGroup != null) {
                    switch (controlGroup) {
                        case "REVERSERFORWARD":
                            readout = Readouts.REVERSER;
                            value = 1;
                            break;
                        case "REVERSERNEUTRAL":
                            readout = Readouts.REVERSER;
                            value = 0.5f;
                            break;
                        case "REVERSERBACKWARD":
                            readout = Readouts.REVERSER;
                            value = 0;
                            break;
                        default:
                            stock.setControlPosition(controlGroup, value);
                            return;
                    }
                }
                if (readout != null) {
                    switch (readout) {
                        case THROTTLE:
                            if (stock instanceof Locomotive) {
                                ((Locomotive) stock).setThrottle(value);
                            }
                            break;
                        case REVERSER:
                            if (stock instanceof Locomotive) {
                                ((Locomotive) stock).setReverser(value * 2 - 1);
                            }
                            break;
                        case TRAIN_BRAKE:
                            if (stock instanceof Locomotive) {
                                ((Locomotive) stock).setTrainBrake(value);
                            }
                            break;
                        case INDEPENDENT_BRAKE:
                            if (stock instanceof EntityMoveableRollingStock) {
                                ((Locomotive) stock).setIndependentBrake(value);
                            }
                            break;
                        case COUPLER_FRONT:
                            if (stock instanceof EntityCoupleableRollingStock) {
                                ((EntityCoupleableRollingStock) stock).setCouplerEngaged(EntityCoupleableRollingStock.CouplerType.FRONT, value == 0);
                            }
                            break;
                        case COUPLER_REAR:
                            if (stock instanceof EntityCoupleableRollingStock) {
                                ((EntityCoupleableRollingStock) stock).setCouplerEngaged(EntityCoupleableRollingStock.CouplerType.BACK, value == 0);
                            }
                            break;
                    }
                }
            }
        }
    }
}
