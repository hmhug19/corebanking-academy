package vn.com.coreacademy.assistant.eval;

import java.util.List;
import java.util.Locale;

/** The numbers of the retrieval eval, kept free of the database so they are tested in the default run. */
public final class RetrievalScore {
    /** A retrieved chunk as the eval sees it: the fixture document it came from and its text. */
    public record Found(String doc, String content) {
    }

    /** What one group of cases scored. Rates are 0..1 over the cases they apply to; NaN when none apply. */
    public record Group(String name, int cases, double recallAt5, double recallAt20, double mrr, double correctEmpty,
                        int leaks) {
    }

    /** One case's outcome; {@code rank} is 1-based, 0 when the expected section was not found in the top list. */
    public record Outcome(RetrievalSet.Case input, int rank, boolean empty, boolean leaked) {
    }

    /** Position (1-based) of the first chunk of the expected document that contains the section's marker, else 0. */
    public static int rank(List<Found> ranked, RetrievalSet.Expect expect) {
        String marker = normalise(expect.marker());
        for (int i = 0; i < ranked.size(); i++) {
            Found found = ranked.get(i);
            if (found.doc().equals(expect.doc()) && normalise(found.content()).contains(marker)) {
                return i + 1;
            }
        }
        return 0;
    }

    public static boolean leaked(List<Found> ranked, String forbidden) {
        return forbidden != null && ranked.stream().anyMatch(found -> found.doc().equals(forbidden));
    }

    public static Group group(String name, List<Outcome> outcomes) {
        List<Outcome> mine = outcomes.stream().filter(outcome -> outcome.input().group().equals(name)).toList();
        List<Outcome> asked = mine.stream().filter(outcome -> outcome.input().expect() != null).toList();
        List<Outcome> empties = mine.stream().filter(outcome -> outcome.input().expect() == null
            && outcome.input().forbidden() == null).toList();
        return new Group(name, mine.size(),
            rate(asked.stream().filter(outcome -> outcome.rank() > 0 && outcome.rank() <= 5).count(), asked.size()),
            rate(asked.stream().filter(outcome -> outcome.rank() > 0).count(), asked.size()),
            asked.isEmpty() ? Double.NaN
                : asked.stream().mapToDouble(outcome -> outcome.rank() == 0 ? 0 : 1.0 / outcome.rank()).average()
                    .orElse(0),
            rate(empties.stream().filter(Outcome::empty).count(), empties.size()),
            (int) mine.stream().filter(Outcome::leaked).count());
    }

    public static String line(Group group) {
        return String.format(Locale.ROOT, "%-10s cases=%2d  recall@5=%s  recall@20=%s  MRR=%s  correct-empty=%s  leaks=%d",
            group.name(), group.cases(), pct(group.recallAt5()), pct(group.recallAt20()), num(group.mrr()),
            pct(group.correctEmpty()), group.leaks());
    }

    static String normalise(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static double rate(long hits, int total) {
        return total == 0 ? Double.NaN : (double) hits / total;
    }

    private static String pct(double value) {
        return Double.isNaN(value) ? "  n/a" : String.format(Locale.ROOT, "%4.0f%%", value * 100);
    }

    private static String num(double value) {
        return Double.isNaN(value) ? " n/a" : String.format(Locale.ROOT, "%.2f", value);
    }

    private RetrievalScore() {
    }
}
