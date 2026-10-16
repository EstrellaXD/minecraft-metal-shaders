// Copyright 2026 mcopt-shade contributors. Apache-2.0.
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import mcopt.metal.shade.*;

/** Pack real game uniforms and exercise live settings and CPU exposure. Also runs against a previous jar. */
public class Fixtures {
    static final String KEY = "mcopt.shade.lighting";
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    static ShadeFrame frame(String name) {
        ShadeFrame f = new ShadeFrame();
        double[] d = new double[3]; ShadeSky.celestial(Math.toRadians(name.equals("night") ? 160 : name.equals("sunset") ? 90 : 25), d);
        f.sunDir.set((float)d[0], (float)d[1], (float)d[2]); f.moonDir.set(f.sunDir).negate();
        f.eyeSkyLight = name.equals("interior") ? 7 : name.equals("cave") ? 0 : 15;
        f.shadowsEnabled = true; f.width = 3840; f.height = 2160;
        f.rain = name.equals("rain") || name.equals("storm") ? 1 : 0; f.thunder = name.equals("storm") ? 1 : 0;
        f.nightVision = name.equals("nightvision") ? 1 : 0;
        f.minAmbient = .0125f + .25f * f.nightVision;
        f.underwater = name.equals("underwater"); f.fogType = f.underwater ? ShadeFrame.FOG_WATER : ShadeFrame.FOG_AIR;
        f.dimension = name.equals("nether") ? ShadeFrame.DIM_NETHER : name.equals("end") ? ShadeFrame.DIM_END : ShadeFrame.DIM_OVERWORLD;
        return f;
    }
    static byte[] pack(ShadeFrame f, ShadeLook l) {
        ByteBuffer b = ByteBuffer.allocateDirect(ShadeFrame.SIZE + ShadeLook.SIZE + 96).order(ByteOrder.LITTLE_ENDIAN);
        f.pack(b.slice(0, ShadeFrame.SIZE)); l.pack(b.slice(ShadeFrame.SIZE, ShadeLook.SIZE), f);
        int p = ShadeFrame.SIZE + ShadeLook.SIZE;
        b.putFloat(p+64, 2); b.putFloat(p+68, 4); b.position(p+72); l.putHdrCurve(b, 4); b.putFloat(p+92, 1);
        byte[] bytes = new byte[b.capacity()]; b.position(0); b.get(bytes); return bytes;
    }
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]); Files.createDirectories(out);
        Files.writeString(out.resolve("post.metal"), ShadeMsl.post());
        String[] names = {"outdoor", "interior", "cave", "night", "rain", "storm", "nightvision", "underwater", "nether", "end", "sunset", "oldlight"};
        for (String name : names) for (String mode : new String[]{"current", "natural"}) {
            System.setProperty(KEY, mode); System.setProperty("mcopt.shade.light2", name.equals("oldlight") ? "false" : "true");
            ShadeFrame f = frame(name); ShadeLook l = new ShadeLook(); f.updateAtmosphere(0, true); l.update(f, 0, 1f/60); l.snapExposure();
            Files.write(out.resolve(name + "-" + mode + ".bin"), pack(f, l));
        }
        System.setProperty("mcopt.shade.light2", "true");
        if (args.length > 1 && args[1].equals("baseline")) return;
        // Current is the default, and changing the same live option back restores the packed scene.
        ShadeOption option = ShadeOptions.all().stream().filter(o -> o.key.equals(KEY)).findFirst().orElseThrow();
        option.reset(); check(option.pos() == 0, "Current default"); option.set(1);
        ShadeSettings.save(out.resolve("settings"));
        check(Files.readString(out.resolve("settings/config/mcopt.properties")).contains(KEY + "=natural"), "Natural saved");
        option.set(0); ShadeSettings.save(out.resolve("settings"));
        check(!Files.readString(out.resolve("settings/config/mcopt.properties")).contains(KEY + "=natural"), "Current reset saved");
        for (String name : names) {
            byte[] a = Files.readAllBytes(out.resolve(name + "-current.bin"));
            byte[] b = Files.readAllBytes(out.resolve(name + "-natural.bin"));
            ByteBuffer n = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
            float weight = n.getFloat(ShadeFrame.SIZE + ShadeLook.USER3);
            if (Set.of("night", "nightvision", "underwater", "nether", "end", "sunset", "oldlight").contains(name))
                check(Arrays.equals(a, b), name + " must preserve Current exactly");
            else check(weight > 0 && weight <= 1, name + " daylight weight");
        }
        StringBuilder trace = new StringBuilder("mode,frame,eye_sky,cpu_exposure\n");
        for (String mode : new String[]{"current", "natural"}) {
            System.setProperty(KEY, mode); ShadeFrame f = frame("outdoor"); f.updateAtmosphere(0, true);
            float outdoor = f.exposure;
            for (int i = 0; i < 1800; i++) {
                f.eyeSkyLight = i < 120 || i >= 1020 ? 15 : 3;
                float previous = f.exposure; f.updateAtmosphere(1f/60, false);
                check(Float.isFinite(f.exposure) && f.exposure > 0, "finite adapting exposure");
                if (i > 120 && i < 1020) check(f.exposure >= previous - 1e-6, "monotonic room adaptation");
                if (i > 1020) check(f.exposure <= previous + 1e-6, "monotonic daylight adaptation");
                if (mode.equals("natural")) check(f.exposure / outdoor <= 2.829f, "reference interior lift <= 1.5 EV");
                trace.append(mode).append(',').append(i).append(',').append(f.eyeSkyLight).append(',').append(f.exposure).append('\n');
            }
            // Switching back in the same frame object must converge to the original reference.
            System.setProperty(KEY, "current"); f.updateAtmosphere(0, true); ShadeLook l = new ShadeLook(); l.update(f, 0, 1f/60); l.snapExposure();
            check(Arrays.equals(pack(f,l), Files.readAllBytes(out.resolve("outdoor-current.bin"))), "live switch restores original uniforms");
        }
        Files.writeString(out.resolve("adaptation.csv"), trace);
        System.out.println("OK live setting/save/reset, default, reference cap, adaptation, weather/night/dimension/vision guards and return to Current");
    }
}
