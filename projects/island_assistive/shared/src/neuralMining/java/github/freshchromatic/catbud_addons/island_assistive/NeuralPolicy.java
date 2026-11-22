package github.freshchromatic.catbud_addons.island_assistive;

import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import ai.onnxruntime.ValueInfo;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class NeuralPolicy implements AutoCloseable {

    record Architecture(int features, int hidden, int mixtures) {}

    record Definition(Architecture architecture, @SerializedName("observation_version") int observationVersion,
        @SerializedName("action_scale") double actionScale, @SerializedName("step_seconds") double stepSeconds) {

        Definition {
            Objects.requireNonNull(architecture);
            if (observationVersion != 1 && observationVersion != 2 || architecture
                .features() != (observationVersion == 1 ? 77 : 119) || architecture.hidden() <= 0 || architecture
                    .mixtures() <= 0 || actionScale != 180 || stepSeconds != .02) {
                throw new IllegalArgumentException("Unsupported neural policy definition");
            }
        }
    }

    record Distribution(float moveLogit, float[] mixtureLogits, float[][] means, float[][] scales) {}

    private final OrtEnvironment environment;
    private final OrtSession     session;
    private final Definition     definition;

    private float[] hidden;

    NeuralPolicy(Path model, Path metadata) throws IOException, OrtException {
        definition = Objects.requireNonNull(new Gson().fromJson(Files.readString(metadata), Definition.class));
        environment = OrtEnvironment.getEnvironment();
        try (var options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(1);
            options.setInterOpNumThreads(1);
            session = environment.createSession(model.toString(), options);
        }
        try {
            validateModel();
        } catch (IllegalArgumentException | OrtException error) {
            session.close();
            throw error;
        }
        hidden = new float[definition.architecture().hidden()];
    }

    Definition definition() {
        return definition;
    }

    void reset() {
        Arrays.fill(hidden, 0);
    }

    float[] hiddenState() {
        return hidden.clone();
    }

    Distribution infer(float[] features) throws OrtException {
        if (features.length != definition.architecture().features()) {
            throw new IllegalArgumentException("Neural policy feature count differs");
        }
        for (float feature : features) {
            if (!Float.isFinite(feature)) {
                throw new IllegalArgumentException("Non-finite neural policy feature");
            }
        }
        try (var observations = OnnxTensor.createTensor(environment, FloatBuffer.wrap(features), new long[] { 1, 1,
            features.length });
            var memory = OnnxTensor.createTensor(environment, FloatBuffer.wrap(hidden), new long[] { 1, 1,
                hidden.length });
            var result = session.run(Map.of("observations", observations, "hidden", memory))) {
            float move = ((float[][]) result.get("move_logit").orElseThrow().getValue())[0][0];
            var logits = ((float[][][]) result.get("mixture_logits").orElseThrow().getValue())[0][0];
            var means = ((float[][][][]) result.get("means").orElseThrow().getValue())[0][0];
            var scales = ((float[][][][]) result.get("scales").orElseThrow().getValue())[0][0];
            var nextHidden = ((float[][][]) result.get("next_hidden").orElseThrow().getValue())[0][0];
            validateFinite(move);
            for (int index = 0; index < logits.length; index++) {
                validateFinite(logits[index]);
                for (int axis = 0; axis < 2; axis++) {
                    validateFinite(means[index][axis]);
                    validateFinite(scales[index][axis]);
                    if (scales[index][axis] <= 0) {
                        throw new IllegalArgumentException("Non-positive neural policy scale");
                    }
                }
            }
            for (float value : nextHidden) {
                validateFinite(value);
            }
            hidden = nextHidden.clone();
            return new Distribution(move, logits, means, scales);
        }
    }

    private void validateModel() throws OrtException {
        if (!session.getInputNames().equals(Set.of("observations", "hidden")) || !session.getOutputNames().equals(Set
            .of("move_logit", "mixture_logits", "means", "scales", "next_hidden"))) {
            throw new IllegalArgumentException("Neural model tensor names differ");
        }
        var input = session.getInputInfo();
        var output = session.getOutputInfo();
        requireTensor(input.get("observations").getInfo(), 1, 1, definition.architecture().features());
        requireTensor(input.get("hidden").getInfo(), 1, 1, definition.architecture().hidden());
        requireTensor(output.get("move_logit").getInfo(), 1, 1);
        requireTensor(output.get("mixture_logits").getInfo(), 1, 1, definition.architecture().mixtures());
        requireTensor(output.get("means").getInfo(), 1, 1, definition.architecture().mixtures(), 2);
        requireTensor(output.get("scales").getInfo(), 1, 1, definition.architecture().mixtures(), 2);
        requireTensor(output.get("next_hidden").getInfo(), 1, 1, definition.architecture().hidden());
    }

    private static void requireTensor(ValueInfo value, long... shape) {
        if (!(value instanceof TensorInfo info) || info.type != OnnxJavaType.FLOAT || !Arrays.equals(info.getShape(),
            shape)) {
            throw new IllegalArgumentException("Neural model tensor type or dimensions differ");
        }
    }

    private static void validateFinite(float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("Non-finite neural model output");
        }
    }

    @Override
    public void close() throws OrtException {
        session.close();
    }
}
