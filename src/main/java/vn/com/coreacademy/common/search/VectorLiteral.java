package vn.com.coreacademy.common.search;

import java.util.StringJoiner;

/** Codec pgvector dùng chung; từ chối NaN/Infinity hoặc dimension sai trước khi tạo parameter. */
public final class VectorLiteral {
    public static String encode(float[] vector) {
        if (vector == null || vector.length != 1024) {
            throw new IllegalArgumentException("Embedding dimension must be 1024");
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
