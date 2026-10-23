package vn.com.coreacademy.knowledge.support;

import java.util.ArrayList;
import java.util.List;

import org.mockito.stubbing.Answer;

/** Stands in for bge-m3 in tests that mock the OllamaClient: one fixed 1024-dimension unit vector per input. */
public final class FakeEmbeddings {
    private FakeEmbeddings() {
    }

    public static float[] unit(int axis) {
        float[] vector = new float[1024];
        vector[axis] = 1f;
        return vector;
    }

    /** Answer for {@code embed(List<String>)}: every input gets {@link #unit(int) unit(0)}. */
    public static Answer<List<float[]>> everyInputOnAxisZero() {
        return invocation -> {
            List<String> inputs = invocation.getArgument(0);
            List<float[]> vectors = new ArrayList<>();
            for (int i = 0; i < inputs.size(); i++) {
                vectors.add(unit(0));
            }
            return vectors;
        };
    }

}
