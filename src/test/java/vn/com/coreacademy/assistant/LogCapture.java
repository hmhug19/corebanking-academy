package vn.com.coreacademy.assistant;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;

/**
 * Collects every log line (message and stack trace text) while open, at every level, so a test can prove that a
 * question or an answer never reaches the logs. Close it to put the logging configuration back.
 */
public final class LogCapture extends AbstractAppender implements AutoCloseable {
    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final LoggerContext context = (LoggerContext) LogManager.getContext(false);
    private final Level previousLevel;

    public LogCapture() {
        super("capture-" + UUID.randomUUID(), null, null, true, Property.EMPTY_ARRAY);
        start();
        Configuration configuration = context.getConfiguration();
        previousLevel = configuration.getRootLogger().getLevel();
        configuration.addAppender(this);
        configuration.getRootLogger().addAppender(this, Level.ALL, null);
        configuration.getRootLogger().setLevel(Level.ALL);
        context.updateLoggers();
    }

    @Override
    public void append(LogEvent event) {
        StringBuilder line = new StringBuilder(event.getMessage().getFormattedMessage());
        if (event.getThrown() != null) {
            line.append(' ').append(event.getThrown());
        }
        lines.add(line.toString());
    }

    public List<String> lines() {
        return List.copyOf(lines);
    }

    @Override
    public void close() {
        Configuration configuration = context.getConfiguration();
        configuration.getRootLogger().removeAppender(getName());
        configuration.getRootLogger().setLevel(previousLevel);
        stop();
        context.updateLoggers();
    }
}
