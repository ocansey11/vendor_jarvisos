package androidx.work;
import java.util.concurrent.TimeUnit;
/** Stub. */
public class PeriodicWorkRequest {
    private PeriodicWorkRequest() {}
    public static class Builder {
        public Builder(Class<? extends Worker> cls, long interval, TimeUnit unit) {}
        public Builder setConstraints(Constraints c) { return this; }
        public PeriodicWorkRequest build() { return new PeriodicWorkRequest(); }
    }
}
