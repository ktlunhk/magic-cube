package com.rubikscube;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Per-size solve history (last 100 solves), best time / best move count, and averages. */
public class Stats {

    private static final int MAX_HISTORY = 100;

    private final SharedPreferences prefs;

    public Stats(Context ctx) {
        prefs = ctx.getSharedPreferences("stats", Context.MODE_PRIVATE);
    }

    /** Each entry is {timeMs, moves}. Oldest first. */
    public List<long[]> history(int size) {
        List<long[]> out = new ArrayList<long[]>();
        String s = prefs.getString("hist_" + size, "");
        if (s.length() == 0) return out;
        for (String part : s.split(";")) {
            String[] p = part.split(":");
            if (p.length != 2) continue;
            try {
                out.add(new long[]{Long.parseLong(p[0]), Long.parseLong(p[1])});
            } catch (NumberFormatException ignored) {
                // skip corrupt entry
            }
        }
        return out;
    }

    public long bestTime(int size) {
        return prefs.getLong("bestTime_" + size, -1);
    }

    public int bestMoves(int size) {
        return prefs.getInt("bestMoves_" + size, -1);
    }

    /** Records a solve; returns bit 1 = new best time, bit 2 = new best moves. */
    public int add(int size, long timeMs, int moves) {
        List<long[]> h = history(size);
        h.add(new long[]{timeMs, moves});
        while (h.size() > MAX_HISTORY) h.remove(0);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < h.size(); i++) {
            if (i > 0) sb.append(';');
            sb.append(h.get(i)[0]).append(':').append(h.get(i)[1]);
        }
        SharedPreferences.Editor e = prefs.edit();
        e.putString("hist_" + size, sb.toString());
        int result = 0;
        long bt = bestTime(size);
        if (bt < 0 || timeMs < bt) {
            e.putLong("bestTime_" + size, timeMs);
            result |= 1;
        }
        int bm = bestMoves(size);
        if (bm < 0 || moves < bm) {
            e.putInt("bestMoves_" + size, moves);
            result |= 2;
        }
        e.apply();
        return result;
    }

    public void clear(int size) {
        prefs.edit().remove("hist_" + size).remove("bestTime_" + size)
                .remove("bestMoves_" + size).apply();
    }

    /**
     * Average of the last k solves; the fastest and slowest are dropped (k >= 5), as in
     * speedcubing. Returns -1 if fewer than k solves exist.
     */
    public long averageOf(int size, int k) {
        List<long[]> h = history(size);
        if (h.size() < k) return -1;
        long sum = 0;
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (int i = h.size() - k; i < h.size(); i++) {
            long t = h.get(i)[0];
            sum += t;
            if (t < min) min = t;
            if (t > max) max = t;
        }
        return (sum - min - max) / (k - 2);
    }

    public long mean(int size) {
        List<long[]> h = history(size);
        if (h.isEmpty()) return -1;
        long sum = 0;
        for (long[] e : h) sum += e[0];
        return sum / h.size();
    }

    public static String formatTime(long ms) {
        if (ms < 0) return "-";
        long tenths = ms / 100;
        long sec = tenths / 10;
        long min = sec / 60;
        return String.format(Locale.US, "%d:%02d.%d", min, sec % 60, tenths % 10);
    }

    public String summary(int size) {
        List<long[]> h = history(size);
        StringBuilder sb = new StringBuilder();
        if (size == -5) sb.append("Pentagon Megaminx - 1 row\n\n");
        else if (size == -25) sb.append("Pentagon Megaminx - 2 rows\n\n");
        else if (size == -35) sb.append("Pentagon Megaminx - 3 rows\n\n");
        else sb.append(size).append("x").append(size).append("x").append(size).append("\n\n");
        sb.append("Solves: ").append(h.size()).append("\n");
        sb.append("Best time: ").append(formatTime(bestTime(size))).append("\n");
        int bm = bestMoves(size);
        sb.append("Fewest moves: ").append(bm < 0 ? "-" : String.valueOf(bm)).append("\n");
        sb.append("Mean: ").append(formatTime(mean(size))).append("\n");
        sb.append("Ao5: ").append(formatTime(averageOf(size, 5))).append("\n");
        sb.append("Ao12: ").append(formatTime(averageOf(size, 12))).append("\n");
        if (!h.isEmpty()) {
            sb.append("\nRecent:\n");
            int from = Math.max(0, h.size() - 8);
            for (int i = h.size() - 1; i >= from; i--) {
                sb.append(formatTime(h.get(i)[0])).append("  (").append(h.get(i)[1])
                        .append(" moves)\n");
            }
        }
        return sb.toString();
    }
}
