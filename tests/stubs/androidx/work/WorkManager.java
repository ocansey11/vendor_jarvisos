package androidx.work;
import android.content.Context;
/** Stub. */
public class WorkManager {
    public static WorkManager getInstance(Context context) { return new WorkManager(); }
    public void enqueueUniquePeriodicWork(String name, ExistingPeriodicWorkPolicy policy,
            PeriodicWorkRequest request) {}
}
