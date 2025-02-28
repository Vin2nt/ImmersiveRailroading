package cam72cam.immersiverailroading.registry;

import cam72cam.immersiverailroading.Config;
import cam72cam.immersiverailroading.ConfigSound;
import cam72cam.immersiverailroading.ImmersiveRailroading;
import cam72cam.immersiverailroading.entity.EntityBuildableRollingStock;
import cam72cam.immersiverailroading.entity.EntityCoupleableRollingStock.CouplerType;
import cam72cam.immersiverailroading.entity.EntityRollingStock;
import cam72cam.immersiverailroading.util.CAML;
import cam72cam.immersiverailroading.util.DataBlock;
import cam72cam.immersiverailroading.gui.overlay.GuiBuilder;
import cam72cam.immersiverailroading.gui.overlay.Readouts;
import cam72cam.immersiverailroading.library.*;
import cam72cam.immersiverailroading.model.StockModel;
import cam72cam.immersiverailroading.model.components.ModelComponent;
import cam72cam.immersiverailroading.util.JSON;
import cam72cam.immersiverailroading.util.RealBB;
import cam72cam.mod.entity.EntityRegistry;
import cam72cam.mod.math.Vec3d;
import cam72cam.mod.model.obj.OBJGroup;
import cam72cam.mod.model.obj.VertexBuffer;
import cam72cam.mod.resource.Identifier;
import cam72cam.mod.serialization.ResourceCache;
import cam72cam.mod.serialization.ResourceCache.GenericByteBuffer;
import cam72cam.mod.serialization.TagCompound;
import cam72cam.mod.serialization.TagField;
import cam72cam.mod.serialization.TagMapped;
import cam72cam.mod.text.TextUtil;
import cam72cam.mod.world.World;

import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@TagMapped(EntityRollingStockDefinition.TagMapper.class)
public abstract class EntityRollingStockDefinition {
    private static final Identifier DEFAULT_PARTICLE_TEXTURE = new Identifier(ImmersiveRailroading.MODID, "textures/light.png");

    public final String defID;
    private final Class<? extends EntityRollingStock> type;
    public final List<String> itemGroups;
    public Map<String, String> textureNames;
    public float dampeningAmount;
    public Gauge recommended_gauge;
    public Boolean shouldSit;
    public Identifier wheel_sound;
    public Identifier clackFront;
    public Identifier clackRear;
    public double internal_model_scale;
    public Identifier couple_sound;
    public Identifier sliding_sound;
    public Identifier flange_sound;
    public Identifier collision_sound;
    public double flange_min_yaw;
    double internal_inv_scale;
    private String name;
    private String modelerName;
    private String packName;
    private ValveGearConfig valveGear;
    public float darken;
    public Identifier modelLoc;
    protected StockModel<?, ?> model;
    private Vec3d passengerCenter;
    private float bogeyFront;
    private float bogeyRear;
    private float couplerOffsetFront;
    private float couplerOffsetRear;
    private float couplerSlackFront;
    private float couplerSlackRear;
    private boolean scalePitch;
    private double frontBounds;
    private double rearBounds;
    private double heightBounds;
    private double widthBounds;
    private double passengerCompartmentLength;
    private double passengerCompartmentWidth;
    private int weight;
    private int maxPassengers;
    private float interiorLightLevel;
    private boolean hasIndependentBrake;
    private boolean hasPressureBrake;
    private final Map<ModelComponentType, List<ModelComponent>> renderComponents;
    private final List<ItemComponentType> itemComponents;
    private final Function<EntityBuildableRollingStock, float[][]> heightmap;
    private final Map<String, LightDefinition> lights = new HashMap<>();
    protected final Map<String, ControlSoundsDefinition> controlSounds = new HashMap<>();
    public Identifier smokeParticleTexture;
    public Identifier steamParticleTexture;
    private boolean isLinearBrakeControl;
    private GuiBuilder overlay;
    private List<String> extraTooltipInfo;
    private boolean hasInternalLighting;
    private double swayMultiplier;
    private double tiltMultiplier;
    private float brakeCoefficient;
    public double rollingResistanceCoefficient;
    public double directFrictionCoefficient;

    public List<AnimationDefinition> animations;

    public static class SoundDefinition {
        public final Identifier start;
        public final Identifier main;
        public final boolean looping;
        public final Identifier stop;
        public final Float distance;
        public final float volume;

        public SoundDefinition(Identifier fallback) {
            // Simple
            start = null;
            main = fallback;
            looping = true;
            stop = null;
            distance = null;
            volume = 1;
        }

        public SoundDefinition(DataBlock obj) {
            start = obj.getValue("start").asIdentifier();
            main = obj.getValue("main").asIdentifier();
            looping = obj.getValue("looping").asBoolean(true);
            stop = obj.getValue("stop").asIdentifier();
            distance = obj.getValue("distance").asFloat();
            volume = obj.getValue("volume").asFloat(1.0f);
        }

        public static SoundDefinition getOrDefault(DataBlock block, String key, SoundDefinition fallback) {
            DataBlock found = block.getBlock(key);
            if (found != null) {
                return new SoundDefinition(found);
            }
            Identifier ident = block.getValue(key).asIdentifier();
            if (ident != null && ident.canLoad()) {
                return new SoundDefinition(ident);
            }
            return fallback;
        }
    }

    public static class AnimationDefinition {
        public enum AnimationMode {
            VALUE,
            PLAY_FORWARD,
            PLAY_REVERSE,
            PLAY_BOTH,
            LOOP,
            LOOP_SPEED
        }
        public final String control_group;
        public final AnimationMode mode;
        public final Readouts readout;
        public final Identifier animatrix;
        public final float offset;
        public final boolean invert;
        public final float frames_per_tick;
        public final SoundDefinition sound;

        public AnimationDefinition(DataBlock obj) {
            control_group = obj.getValue("control_group").asString();
            String readout = obj.getValue("readout").asString();
            this.readout = readout != null ? Readouts.valueOf(readout.toUpperCase(Locale.ROOT)) : null;
            if (control_group == null && readout == null) {
                throw new IllegalArgumentException("Must specify either a control group or a readout for an animation");
            }
            animatrix = obj.getValue("animatrix").asIdentifier();
            mode = AnimationMode.valueOf(obj.getValue("mode").asString().toUpperCase(Locale.ROOT));
            offset = obj.getValue("offset").asFloat(0f);
            invert = obj.getValue("invert").asBoolean(false);
            frames_per_tick = obj.getValue("frames_per_tick").asFloat(1f);
            sound = SoundDefinition.getOrDefault(obj, "sound", null);
        }

        public boolean valid() {
            return animatrix != null && (control_group != null || readout != null);
        }
    }

    public static class LightDefinition {
        public static final Identifier default_light_tex = new Identifier(ImmersiveRailroading.MODID, "textures/light.png");

        public final float blinkIntervalSeconds;
        public final float blinkOffsetSeconds;
        public final boolean blinkFullBright;
        public final String reverseColor;
        public final Identifier lightTex;
        public final boolean castsLight;

        private LightDefinition(DataBlock data) {
            blinkIntervalSeconds = data.getValue("blinkIntervalSeconds").asFloat(0f);
            blinkOffsetSeconds = data.getValue("blinkOffsetSeconds").asFloat(0f);
            blinkFullBright = data.getValue("blinkFullBright").asBoolean(true);
            reverseColor = data.getValue("reverseColor").asString();
            lightTex = data.getValue("texture").asIdentifier(default_light_tex);
            castsLight = data.getValue("castsLight").asBoolean(true);
        }
    }

    public static class ControlSoundsDefinition {
        public final Identifier engage;
        public final Identifier move;
        public final Float movePercent;
        public final Identifier disengage;

        protected ControlSoundsDefinition(Identifier engage, Identifier move, Float movePercent, Identifier disengage) {
            this.engage = engage;
            this.move = move;
            this.movePercent = movePercent;
            this.disengage = disengage;
        }

        private ControlSoundsDefinition(DataBlock data) {
            engage = data.getValue("engage").asIdentifier();
            move = data.getValue("move").asIdentifier();
            movePercent = data.getValue("movePercent").asFloat();
            disengage = data.getValue("disengage").asIdentifier();
        }
    }

    public EntityRollingStockDefinition(Class<? extends EntityRollingStock> type, String defID, DataBlock data) throws Exception {
        this.type = type;
        this.defID = defID;


        loadData(data);

        this.model = createModel();
        this.itemGroups = model.groups.keySet().stream().filter(x -> !ModelComponentType.shouldRender(x)).collect(Collectors.toList());

        this.renderComponents = new HashMap<>();
        for (ModelComponent component : model.allComponents) {
            renderComponents.computeIfAbsent(component.type, v -> new ArrayList<>())
                    .add(0, component);
        }

        itemComponents = model.allComponents.stream()
                .map(component -> component.type)
                .map(ItemComponentType::from)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        frontBounds = -model.minOfGroup(model.groups()).x;
        rearBounds = model.maxOfGroup(model.groups()).x;
        widthBounds = model.widthOfGroups(model.groups());

        // Bad hack for height bounds
        ArrayList<String> heightGroups = new ArrayList<>();
        for (String group : model.groups()) {
            boolean ignore = false;
            for (ModelComponentType rct : ModelComponentType.values()) {
                if (rct.collisionsEnabled) {
                    continue;
                }
                for (int i = 0; i < 10; i++) {
                    if (Pattern.matches(rct.regex.replace("#ID#", "" + i), group)) {
                        ignore = true;
                        break;
                    }
                }
                if (ignore) {
                    break;
                }
            }
            if (!ignore) {
                heightGroups.add(group);
            }
        }
        heightBounds = model.heightOfGroups(heightGroups);

        this.heightmap = initHeightmap();
    }

    public final EntityRollingStock spawn(World world, Vec3d pos, float yaw, Gauge gauge, String texture) {
        EntityRollingStock stock = (EntityRollingStock) EntityRegistry.create(world, type);
        stock.setPosition(pos);
        stock.setRotationYaw(yaw);
        // Override prev
        stock.setRotationYaw(yaw);
        stock.setup(defID, gauge, texture);

        return stock;
    }

    public boolean shouldScalePitch() {
        return ConfigSound.scaleSoundToGauge && scalePitch;
    }

    public void loadData(DataBlock data) throws Exception {
        name = data.getValue("name").asString();
        modelerName = data.getValue("modeler").asString("N/A");
        packName = data.getValue("pack").asString("N/A");
        darken = data.getValue("darken_model").asFloat(0f);
        internal_model_scale = 1;
        internal_inv_scale = 1;
        // TODO Gauge.from(Gauge.STANDARD).value() what happens when != Gauge.STANDARD
        this.recommended_gauge = Gauge.from(Gauge.STANDARD);
        Double model_gauge_m = data.getValue("model_gauge_m").asDouble();
        if (model_gauge_m != null) {
            this.recommended_gauge = Gauge.from(model_gauge_m);
            internal_model_scale = Gauge.STANDARD / model_gauge_m;
        }
        Double recommended_gauge_m = data.getValue("recommended_gauge_m").asDouble();
        if (recommended_gauge_m != null) {
            this.recommended_gauge = Gauge.from(recommended_gauge_m);
        }
        if (this.recommended_gauge != Gauge.from(Gauge.STANDARD)) {
            internal_inv_scale = Gauge.STANDARD / recommended_gauge.value();
        }

        textureNames = new LinkedHashMap<>();
        textureNames.put("", "Default");
        DataBlock tex_variants = data.getBlock("tex_variants");
        if (tex_variants != null) {
            tex_variants.getValueMap().forEach((key, value) -> textureNames.put(value.asString(), key));
        }

        try {
            List<DataBlock> alternates = new ArrayList<>();

            Identifier alt_textures = new Identifier(ImmersiveRailroading.MODID, defID.replace(".caml", ".json").replace(".json", "_variants.json"));
            List<InputStream> alts = alt_textures.getResourceStreamAll();
            for (InputStream input : alts) {
                alternates.add(JSON.parse(input));
            }

            alt_textures = new Identifier(alt_textures.getDomain(), alt_textures.getPath().replace(".json", ".caml"));
            alts = alt_textures.getResourceStreamAll();
            for (InputStream input : alts) {
                alternates.add(CAML.parse(input));
            }
            for (DataBlock alternate : alternates) {
                alternate.getValueMap().forEach((key, value) -> textureNames.put(value.asString(), key));
            }
        } catch (java.io.FileNotFoundException ex) {
            ImmersiveRailroading.catching(ex);
        }

        modelLoc = data.getValue("model").asIdentifier();

        DataBlock passenger = data.getBlock("passenger");
        passengerCenter = new Vec3d(0, passenger.getValue("center_y").asDouble() - 0.35, passenger.getValue("center_x").asDouble()).scale(internal_model_scale);
        passengerCompartmentLength = passenger.getValue("length").asDouble() * internal_model_scale;
        passengerCompartmentWidth = passenger.getValue("width").asDouble() * internal_model_scale;
        maxPassengers = passenger.getValue("slots").asInteger();
        shouldSit = passenger.getValue("should_sit").asBoolean();

        DataBlock pivot = data.getBlock("trucks"); // Legacy
        if (pivot == null) {
            pivot = data.getBlock("pivot");
        }
        bogeyFront = pivot.getValue("front").asFloat() * (float) internal_model_scale;
        bogeyRear = pivot.getValue("rear").asFloat() * (float) internal_model_scale;

        dampeningAmount = data.getValue("sound_dampening_percentage").asFloat(0.75f);
        if (dampeningAmount < 0 || dampeningAmount > 1) {
            dampeningAmount = 0.75f;
        }
        scalePitch = data.getValue("scale_pitch").asBoolean(true);

        couplerSlackFront = couplerSlackRear = 0.025f;

        DataBlock couplers = data.getBlock("couplers");
        if (couplers != null) {
            couplerOffsetFront = couplers.getValue("front_offset").asFloat(0f) * (float) internal_model_scale;
            couplerOffsetRear = couplers.getValue("rear_offset").asFloat(0f) * (float) internal_model_scale;
            couplerSlackFront = couplers.getValue("front_slack").asFloat(couplerSlackFront) * (float) internal_model_scale;
            couplerSlackRear = couplers.getValue("rear_slack").asFloat(couplerSlackRear) * (float) internal_model_scale;
        }

        DataBlock properties = data.getBlock("properties");
        weight = (int) Math.ceil(properties.getValue("weight_kg").asInteger() * internal_inv_scale);
        valveGear = ValveGearConfig.get(properties, "valve_gear");
        hasIndependentBrake = properties.getValue("independent_brake").asBoolean(independentBrakeDefault());
        hasPressureBrake = properties.getValue("pressure_brake").asBoolean(pressureBrakeDefault());
        // Locomotives default to linear brake control
        isLinearBrakeControl = properties.getValue("linear_brake_control").asBoolean(!(this instanceof LocomotiveDefinition));

        brakeCoefficient = PhysicalMaterials.STEEL.kineticFriction(PhysicalMaterials.CAST_IRON);
        try {
            brakeCoefficient = PhysicalMaterials.STEEL.kineticFriction(PhysicalMaterials.valueOf(properties.getValue("brake_shoe_material").asString("CAST_IRON")));
        } catch (Exception ex) {
            ImmersiveRailroading.warn("Invalid brake_shoe_material, possible values are: %s", Arrays.toString(PhysicalMaterials.values()));
        }
        brakeCoefficient = properties.getValue("brake_friction_coefficient").asFloat(brakeCoefficient);
        // https://en.wikipedia.org/wiki/Rolling_resistance#Rolling_resistance_coefficient_examples
        rollingResistanceCoefficient = properties.getValue("rolling_resistance_coefficient").asDouble(0.002);
        directFrictionCoefficient = properties.getValue("direct_friction_coefficient").asDouble(0);

        swayMultiplier = properties.getValue("swayMultiplier").asDouble(1);
        tiltMultiplier = properties.getValue("tiltMultiplier").asDouble(0);

        interiorLightLevel = properties.getValue("interior_light_level").asFloat(6 / 15f);
        hasInternalLighting = properties.getValue("internalLighting").asBoolean(this instanceof CarPassengerDefinition || this instanceof LocomotiveDefinition);

        DataBlock lights = data.getBlock("lights");
        if (lights != null) {
            lights.getBlockMap().forEach((key, block) -> this.lights.put(key, new LightDefinition(block)));
        }

        wheel_sound = new Identifier(ImmersiveRailroading.MODID, "sounds/default/track_wheels.ogg");
        clackFront = clackRear = new Identifier(ImmersiveRailroading.MODID, "sounds/default/clack.ogg");
        couple_sound = new Identifier(ImmersiveRailroading.MODID, "sounds/default/coupling.ogg");
        sliding_sound = new Identifier(ImmersiveRailroading.MODID, "sounds/default/sliding.ogg");
        flange_sound = new Identifier(ImmersiveRailroading.MODID, "sounds/default/flange.ogg");
        flange_min_yaw = 2.5;
        collision_sound = new Identifier(ImmersiveRailroading.MODID, "sounds/default/collision.ogg");

        DataBlock sounds = data.getBlock("sounds");
        if (sounds != null) {
            wheel_sound = sounds.getValue("wheels").asIdentifier(wheel_sound);
            clackFront = clackRear = sounds.getValue("clack").asIdentifier(clackFront);
            clackFront = sounds.getValue("clack_front").asIdentifier(clackFront);
            clackRear = sounds.getValue("clack_rear").asIdentifier(clackRear);
            couple_sound = sounds.getValue("couple").asIdentifier(couple_sound);
            sliding_sound = sounds.getValue("sliding").asIdentifier(sliding_sound);
            flange_sound = sounds.getValue("flange").asIdentifier(flange_sound);
            flange_min_yaw = sounds.getValue("flange_min_yaw").asDouble(flange_min_yaw);
            collision_sound = sounds.getValue("collision").asIdentifier(collision_sound);
            DataBlock controls = sounds.getBlock("controls");
            if (controls != null) {
                controls.getBlockMap().forEach((key, block) -> controlSounds.put(key, new ControlSoundsDefinition(block)));
            }
        }

        Identifier overlay = data.getValue("overlay").asIdentifier();
        this.overlay = overlay != null ? GuiBuilder.parse(overlay) : getDefaultOverlay(data);

        extraTooltipInfo = new ArrayList<>();
        List<DataBlock.Value> extra_tooltip_info = data.getValues("extra_tooltip_info");
        if (extra_tooltip_info != null) {
            extra_tooltip_info.forEach(value -> extraTooltipInfo.add(value.asString()));
        }

        smokeParticleTexture = steamParticleTexture = DEFAULT_PARTICLE_TEXTURE;
        DataBlock particles = data.getBlock("particles");
        if (particles != null) {
            DataBlock smoke = particles.getBlock("smoke");
            if (smoke != null) {
                smokeParticleTexture = new Identifier(smoke.getValue("texture").asString());
            }
            DataBlock steam = particles.getBlock("steam");
            if (steam != null) {
                steamParticleTexture = new Identifier(steam.getValue("texture").asString());
            }
        }

        this.animations = new ArrayList<>();
        List<DataBlock> aobjs = data.getBlocks("animations");
        if (aobjs == null) {
            aobjs = data.getBlocks("animation");
        }
        if (aobjs != null) {
            for (DataBlock entry : aobjs) {
                animations.add(new AnimationDefinition(entry));
            }
        }
    }

    public List<ModelComponent> getComponents(ModelComponentType name) {
        if (!renderComponents.containsKey(name)) {
            return null;
        }
        return renderComponents.get(name);
    }

    public Vec3d correctPassengerBounds(Gauge gauge, Vec3d pos, boolean shouldSit) {
        double gs = gauge.scale();
        Vec3d passengerCenter = this.passengerCenter.scale(gs);
        pos = pos.subtract(passengerCenter);
        if (pos.z > this.passengerCompartmentLength * gs) {
            pos = new Vec3d(pos.x, pos.y, this.passengerCompartmentLength * gs);
        }

        if (pos.z < -this.passengerCompartmentLength * gs) {
            pos = new Vec3d(pos.x, pos.y, -this.passengerCompartmentLength * gs);
        }

        if (Math.abs(pos.x) > this.passengerCompartmentWidth / 2 * gs) {
            pos = new Vec3d(Math.copySign(this.passengerCompartmentWidth / 2 * gs, pos.x), pos.y, pos.z);
        }

        pos = new Vec3d(pos.x, passengerCenter.y - (shouldSit ? 0.75 : 0), pos.z + passengerCenter.z);

        return pos;
    }

    public boolean isAtFront(Gauge gauge, Vec3d pos) {
        pos = pos.subtract(passengerCenter.scale(gauge.scale()));
        return pos.z >= this.passengerCompartmentLength * gauge.scale();
    }

    public boolean isAtRear(Gauge gauge, Vec3d pos) {
        pos = pos.subtract(passengerCenter.scale(gauge.scale()));
        return pos.z <= -this.passengerCompartmentLength * gauge.scale();
    }

    public List<ItemComponentType> getItemComponents() {
        return itemComponents;
    }

    public float getBogeyFront(Gauge gauge) {
        return (float) gauge.scale() * this.bogeyFront;
    }

    public float getBogeyRear(Gauge gauge) {
        return (float) gauge.scale() * this.bogeyRear;
    }

    public double getCouplerPosition(CouplerType coupler, Gauge gauge) {
        switch (coupler) {
            default:
            case FRONT:
                return gauge.scale() * (this.frontBounds + couplerOffsetFront);
            case BACK:
                return gauge.scale() * (this.rearBounds + couplerOffsetRear);
        }
    }

    public double getCouplerSlack(CouplerType coupler, Gauge gauge) {
        if (!Config.ImmersionConfig.slackEnabled) {
            return 0;
        }
        switch (coupler) {
            default:
            case FRONT:
                return gauge.scale() * (this.couplerSlackFront);
            case BACK:
                return gauge.scale() * (this.couplerSlackRear);
        }
    }


    public boolean hasIndependentBrake() {
        return hasIndependentBrake;
    }

    public boolean hasPressureBrake() {
        return hasPressureBrake;
    }

    private static class HeightMapData {
        final int xRes;
        final int zRes;
        final List<ModelComponent> components;
        final float[] data;

        HeightMapData(EntityRollingStockDefinition def) {
            ImmersiveRailroading.info("Generating heightmap %s", def.defID);

            double ratio = 8;
            int precision = (int) Math.ceil(def.heightBounds * 4);

            xRes = (int) Math.ceil((def.frontBounds + def.rearBounds) * ratio);
            zRes = (int) Math.ceil(def.widthBounds * ratio);
            components = def.renderComponents.values().stream()
                    .flatMap(Collection::stream)
                    .filter(rc -> rc.type.collisionsEnabled)
                    .collect(Collectors.toList());
            data = new float[components.size() * xRes * zRes];

            VertexBuffer vb = def.model.vbo.buffer.get();

            for (int i = 0; i < components.size(); i++) {
                ModelComponent rc = components.get(i);
                int idx = i * xRes * zRes;
                for (String group : rc.modelIDs) {
                    OBJGroup faces = def.model.groups.get(group);

                    for (int face = faces.faceStart; face <= faces.faceStop; face++) {
                        Path2D path = new Path2D.Float();
                        float fheight = 0;
                        boolean first = true;
                        for (int point = 0; point < vb.vertsPerFace; point++) {
                            int vertex = face * vb.vertsPerFace * vb.stride + point * vb.stride;
                            float vertX = vb.data[vertex + 0];
                            float vertY = vb.data[vertex + 1];
                            float vertZ = vb.data[vertex + 2];
                            vertX += def.frontBounds;
                            vertZ += def.widthBounds / 2;
                            if (first) {
                                path.moveTo(vertX * ratio, vertZ * ratio);
                                first = false;
                            } else {
                                path.lineTo(vertX * ratio, vertZ * ratio);
                            }
                            fheight += vertY / vb.vertsPerFace;
                        }
                        Rectangle2D bounds = path.getBounds2D();
                        if (bounds.getWidth() * bounds.getHeight() < 1) {
                            continue;
                        }
                        for (int x = 0; x < xRes; x++) {
                            for (int z = 0; z < zRes; z++) {
                                float relX = ((xRes - 1) - x);
                                float relZ = z;
                                if (bounds.contains(relX, relZ) && path.contains(relX, relZ)) {
                                    float relHeight = fheight / (float) def.heightBounds;
                                    relHeight = ((int) Math.ceil(relHeight * precision)) / (float) precision;
                                    data[idx + x * zRes + z] = Math.max(data[idx + x * zRes + z], relHeight);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private Function<EntityBuildableRollingStock, float[][]> initHeightmap() {
        String key = String.format(
                "%s-%s-%s-%s-%s-%s",
                model.hash, frontBounds, rearBounds, widthBounds, heightBounds, renderComponents.size());
        try {
            ResourceCache<HeightMapData> cache = new ResourceCache<>(
                    new Identifier(modelLoc.getDomain(), modelLoc.getPath() + "_heightmap_" + key.hashCode()),
                    provider -> new HeightMapData(this)
            );
            Supplier<GenericByteBuffer> data = cache.getResource("data.bin", builder -> new GenericByteBuffer(builder.data));
            Supplier<GenericByteBuffer> meta = cache.getResource("meta.nbt", builder -> {
                try {
                    return new GenericByteBuffer(new TagCompound()
                            .setInteger("xRes", builder.xRes)
                            .setInteger("zRes", builder.zRes)
                            .setList("components", builder.components, v -> new TagCompound().setString("key", v.key))
                            .toBytes()
                    );
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            cache.close();

            return (stock) -> {
                try {
                    float[] raw = data.get().floats();
                    TagCompound tc = new TagCompound(meta.get().bytes());

                    int xRes = tc.getInteger("xRes");
                    int zRes = tc.getInteger("zRes");
                    List<String> componentKeys = tc.getList("components", v -> v.getString("key"));

                    float[][] heightMap = new float[xRes][zRes];

                    List<ModelComponentType> availComponents = new ArrayList<>();
                    for (ItemComponentType item : stock.getItemComponents()) {
                        availComponents.addAll(item.render);
                    }

                    for (List<ModelComponent> rcl : this.renderComponents.values()) {
                        for (ModelComponent rc : rcl) {
                            if (!rc.type.collisionsEnabled) {
                                continue;
                            }

                            if (availComponents.contains(rc.type)) {
                                availComponents.remove(rc.type);
                            } else if (rc.type == ModelComponentType.REMAINING && stock.isBuilt()) {
                                //pass
                            } else {
                                continue;
                            }

                            int idx = componentKeys.indexOf(rc.key) * xRes * zRes;
                            if (idx < 0) {
                                // Code changed, we should probably invalidate this cache key...
                                continue;
                            }
                            for (int x = 0; x < xRes; x++) {
                                for (int z = 0; z < zRes; z++) {
                                    heightMap[x][z] = Math.max(heightMap[x][z], raw[idx + x * zRes + z]);
                                }
                            }
                        }
                    }

                    return heightMap;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            };
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public float[][] createHeightMap(EntityBuildableRollingStock stock) {
        return heightmap.apply(stock);
    }

    public RealBB getBounds(float yaw, Gauge gauge) {
        return new RealBB(gauge.scale() * frontBounds, gauge.scale() * -rearBounds, gauge.scale() * widthBounds,
                gauge.scale() * heightBounds, yaw);
    }

    public String name() {
        String[] sp = this.defID.replaceAll(".json", "").split("/");
        String localStr = String.format("%s:entity.%s.%s", ImmersiveRailroading.MODID, sp[sp.length - 2], sp[sp.length - 1]);
        String transStr = TextUtil.translate(localStr);
        return !localStr.equals(transStr) ? transStr : name;
    }

    public List<String> getTooltip(Gauge gauge) {
        List<String> tips = new ArrayList<>();
        tips.add(GuiText.WEIGHT_TOOLTIP.toString(this.getWeight(gauge)));
        tips.add(GuiText.MODELER_TOOLTIP.toString(modelerName));
        tips.add(GuiText.PACK_TOOLTIP.toString(packName));
        return tips;
    }

    protected StockModel<?, ?> createModel() throws Exception {
        return new StockModel<>(this);
    }
    public StockModel<?, ?> getModel() {
        return this.model;
    }

    /**
     * @return Stock Weight in Kg
     */
    public int getWeight(Gauge gauge) {
        return (int) Math.ceil(gauge.scale() * this.weight);
    }

    public double getHeight(Gauge gauge) {
        return gauge.scale() * this.heightBounds;
    }

    public double getWidth(Gauge gauge) {
        return gauge.scale() * this.widthBounds;
    }

    public double getLength(Gauge gauge) {
        return gauge.scale() * this.frontBounds + this.rearBounds;
    }

    public int getMaxPassengers() {
        return this.maxPassengers;
    }

    public boolean acceptsPassengers() {
        return false;
    }

    public boolean acceptsLivestock() {
        return false;
    }

    static class TagMapper implements cam72cam.mod.serialization.TagMapper<EntityRollingStockDefinition> {
        @Override
        public TagAccessor<EntityRollingStockDefinition> apply(Class<EntityRollingStockDefinition> type, String fieldName, TagField tag) {
            return new TagAccessor<>(
                    (d, o) -> d.setString(fieldName, o == null ? null : o.defID),
                    d -> DefinitionManager.getDefinition(d.getString(fieldName))
            );
        }
    }

    public ValveGearConfig getValveGear() {
        return valveGear;
    }

    public LightDefinition getLight(String name) {
        return lights.get(name);
    }
    public ControlSoundsDefinition getControlSound(String name) {
        return controlSounds.get(name);
    }

    public float interiorLightLevel() {
        return interiorLightLevel;
    }


    protected boolean independentBrakeDefault() {
        return false;
    }

    protected boolean pressureBrakeDefault() {
        return true;
    }

    public boolean isLinearBrakeControl() {
        return isLinearBrakeControl;
    }

    protected GuiBuilder getDefaultOverlay(DataBlock data) throws IOException {
        return hasIndependentBrake() ? GuiBuilder.parse(new Identifier(ImmersiveRailroading.MODID, "gui/default/independent.caml")) : null;
    }

    public GuiBuilder getOverlay() {
        return overlay;
    }

    public List<String> getExtraTooltipInfo() {
        return extraTooltipInfo;
    }

    public boolean hasInternalLighting() {
        return hasInternalLighting;
    }

    public double getSwayMultiplier() {
        return swayMultiplier;
    }

    public double getTiltMultiplier() {
        return tiltMultiplier;
    }

    public double getBrakeShoeFriction() {
        return brakeCoefficient;
    }

}
