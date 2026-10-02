package android.os;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Single-threaded deterministic queue; tests advance it explicitly with tick. */
public class Handler {
    private static final List<Handler> INSTANCES = new ArrayList<>();
    private final List<ScheduledRunnable> queue = new ArrayList<>();

    public Handler(Looper looper) {
        INSTANCES.add(this);
    }

    public boolean post(Runnable runnable) {
        queue.add(new ScheduledRunnable(runnable, SystemClock.uptimeMillis()));
        return true;
    }

    public boolean postDelayed(Runnable runnable, long delayMillis) {
        queue.add(new ScheduledRunnable(runnable, SystemClock.uptimeMillis() + delayMillis));
        return true;
    }

    public void removeCallbacks(Runnable runnable) {
        queue.removeIf(item -> item.runnable == runnable);
    }

    public static void tick(long milliseconds) {
        SystemClock.advanceBy(milliseconds);
        for (Handler handler : new ArrayList<>(INSTANCES)) {
            handler.runReady();
        }
    }

    public static void reset() {
        INSTANCES.clear();
        SystemClock.reset();
    }

    private void runReady() {
        List<Runnable> ready = new ArrayList<>();
        Iterator<ScheduledRunnable> iterator = queue.iterator();
        while (iterator.hasNext()) {
            ScheduledRunnable item = iterator.next();
            if (item.runAt <= SystemClock.uptimeMillis()) {
                ready.add(item.runnable);
                iterator.remove();
            }
        }
        for (Runnable runnable : ready) {
            runnable.run();
        }
    }

    private static final class ScheduledRunnable {
        private final Runnable runnable;
        private final long runAt;

        private ScheduledRunnable(Runnable runnable, long runAt) {
            this.runnable = runnable;
            this.runAt = runAt;
        }
    }
}
