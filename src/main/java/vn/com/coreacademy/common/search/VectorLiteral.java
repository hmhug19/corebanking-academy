package vn.com.coreacademy.common.search;

import java.util.StringJoiner;

/** Codec pgvector dùng chung; từ chối NaN/Infinity hoặc dimension sai trước khi tạo parameter. */
public final class VectorLiteral {
    public static String encode(float[] vector) {
        return encode(vector, 1024);
    }

    /** As {@link #encode(float[])} for a column whose dimension comes from configuration. */
    public static String encode(float[] vector, int dimension) {
        if (vector == null || vector.length != dimension) {
            throw new IllegalArgumentException("Embedding dimension must be " + dimension);
        }
        StringJoiner result = new StringJoiner(",", "[", "]");
        boolean nonzero = false;
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("Embedding values must be finite");
            }
            result.add(Float.toString(value));
            nonzero |= value != 0;
        }
        if (!nonzero) {
            throw new IllegalArgumentException("Cosine embedding must be nonzero");
        }
        return result.toString();
    }

    private VectorLiteral() {
    }
}
