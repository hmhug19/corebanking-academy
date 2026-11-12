package vn.com.coreacademy.knowledge.ai;

import java.io.Closeable;

/**
 * Lets another thread stop a model call that is blocked reading Ollama's answer: {@link #cancel()} closes the
 * response being read, which ends the HTTP call, and the reader sees {@link #isCancelled()}.
 */
public final class Cancellation {
    public static final Cancellation NONE = new Cancellation();

    private volatile boolean cancelled;
    private volatile Closeable target;

    public boolean isCancelled() {
        return cancelled;
    }

    /** The response of the running call; closed at once when the turn was already cancelled. */
    public void attach(Closeable response) {
        target = response;
        if (cancelled) {
            closeQuietly(response);
        }
    }

    public void cancel() {
        cancelled = true;
        Closeable response = target;
        if (response != null) {
            closeQuietly(response);
        }
    }

    private static void closeQuietly(Closeable response) {
        try {
            response.close();
        } catch (Exception ignored) {
            // Closing is how the call is aborted; a failure to close leaves nothing more to do.
        }
    }
}
