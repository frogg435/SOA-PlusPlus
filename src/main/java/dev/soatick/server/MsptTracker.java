package dev.soatick.server;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * 服务端 tick 耗时自测（MSPT = millis per tick）。
 * START_SERVER_TICK 到 END_SERVER_TICK 的墙钟差即整次服务端 tick 耗时，
 * 环形缓冲 100 个样本，/soa stats 输出均值与峰值。
 * 开销：每 tick 两次 System.nanoTime，可忽略。
 */
public final class MsptTracker {

        private MsptTracker() {}

        private static final int N = 100;
        private static final double[] NANOS = new double[N];
        private static int idx;
        private static boolean filled;
        private static long tickStart;

        public static void register() {
                ServerTickEvents.START_SERVER_TICK.register(server -> tickStart = System.nanoTime());
                ServerTickEvents.END_SERVER_TICK.register(server -> {
                        NANOS[idx] = System.nanoTime() - tickStart;
                        idx = (idx + 1) % N;
                        if (idx == 0) filled = true;
                });
        }

        /** 平均 MSPT（最近 100 tick） */
        public static double avgMs() {
                int n = filled ? N : idx;
                if (n == 0) return 0.0D;
                double sum = 0;
                for (int i = 0; i < n; i++) sum += NANOS[i];
                return sum / n / 1_000_000.0D;
        }

        /** 峰值 MSPT（最近 100 tick） */
        public static double maxMs() {
                int n = filled ? N : idx;
                double max = 0;
                for (int i = 0; i < n; i++) max = Math.max(max, NANOS[i]);
                return max / 1_000_000.0D;
        }

        /** 估算 TPS（以均值计，20 封顶） */
        public static double tps() {
                double avg = avgMs();
                return avg <= 0 ? 20.0D : Math.min(20.0D, 1000.0D / avg);
        }
}
