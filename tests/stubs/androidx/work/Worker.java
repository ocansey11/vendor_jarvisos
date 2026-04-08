package androidx.work;
import android.content.Context;
/** Stub. */
public abstract class Worker {
    public Worker(Context context, WorkerParameters params) {}
    public abstract Result doWork();
    public static final class Result {
        private Result() {}
        public static Result success() { return new Result(); }
        public static Result retry()   { return new Result(); }
        public static Result failure() { return new Result(); }
    }
}
