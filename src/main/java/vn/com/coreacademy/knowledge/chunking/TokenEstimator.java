package vn.com.coreacademy.knowledge.chunking;

/** No bge-m3 tokenizer in Java: three characters per token is a conservative guess for Vietnamese. */
public final class TokenEstimator {
    private static final int CHARACTERS_PER_TOKEN = 3;

    private TokenEstimator() {
    }

    public static int estimate(String text) {
        return (text.length() + CHARACTERS_PER_TOKEN - 1) / CHARACTERS_PER_TOKEN;
    }
}
