package cam72cam.immersiverailroading.model.animation;

import util.Matrix4;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;

public class Animatrix {
    private final Map<String, List<Matrix4>> map = new HashMap<>();
    private final boolean looping;
    private final int frameCount;

    public Animatrix(InputStream in, boolean looping, double internal_model_scale) throws IOException {
        this.looping = looping;

        Matrix4 scale = new Matrix4();
        scale.m33 = 1/internal_model_scale;
        Matrix4 inv = scale.copy();
        inv.m33 = internal_model_scale;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
            List<String> names = new ArrayList<>();
            List<Matrix4> frames = new ArrayList<>();

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("O ")) {
                    for (String name : names) {
                        map.put(name, frames);
                    }

                    names = new ArrayList<>();
                    frames = new ArrayList<>();
                    names.add(line.substring(2));
                } else if (line.startsWith("A ")) {
                    names.add(line.substring(2));
                } else if (line.startsWith("M ")) {
                    String[] mm = line.substring(2).split(",");
                    frames.add(new Matrix4(
                            Double.parseDouble(mm[0]),
                            Double.parseDouble(mm[1]),
                            Double.parseDouble(mm[2]),
                            Double.parseDouble(mm[3]),
                            Double.parseDouble(mm[4]),
                            Double.parseDouble(mm[5]),
                            Double.parseDouble(mm[6]),
                            Double.parseDouble(mm[7]),
                            Double.parseDouble(mm[8]),
                            Double.parseDouble(mm[9]),
                            Double.parseDouble(mm[10]),
                            Double.parseDouble(mm[11]),
                            Double.parseDouble(mm[12]),
                            Double.parseDouble(mm[13]),
                            Double.parseDouble(mm[14]),
                            Double.parseDouble(mm[15])
                    ).multiply(inv).leftMultiply(scale));
                } else {
                    throw new RuntimeException("Invalid line '" + line + "'");
                }
            }

            if (!frames.isEmpty()) {
                for (String name : names) {
                    map.put(name, frames);
                }
            }
        }
        this.frameCount = map.values().stream().mapToInt(List::size).max().getAsInt();
    }

    public Set<String> groups() {
        return map.keySet();
    }

    public Matrix4 getMatrix(String group, float percent) {
        //inv.invert();
        //scale.scale(internal_model_scale, internal_model_scale, internal_model_scale);
        for (Map.Entry<String, List<Matrix4>> x : map.entrySet()) {
            if (group.equals(x.getKey())) {
                List<Matrix4> frames = x.getValue();
                if (!looping) {
                    if (percent >= 1) {
                        return frames.get(frames.size()-1);
                    }
                    if (percent <= 0){
                        return frames.get(0);
                    }
                }

                percent = (percent % 1 + 1) % 1;
                double frame = (frames.size()) * percent;
                Matrix4 ms = frames.get((int) Math.floor(frame) % (frames.size()));
                Matrix4 me = frames.get((int) Math.ceil(frame) % (frames.size()));
                float lerp = (float) (frame - Math.floor(frame));

                return ms.slerp(me, lerp);
            }
        }
        return null;
    }

    public int frameCount() {
        return frameCount;
    }
}
