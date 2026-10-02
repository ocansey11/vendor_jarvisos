package android.content;

/** Stub — minimal Context for JVM unit tests. */
public abstract class Context {
    public abstract void sendBroadcast(Intent intent);
    public void sendBroadcastAsUser(Intent intent, android.os.UserHandle user) {}
    public abstract Object getSystemService(String name);
    public <T> T getSystemService(Class<T> serviceClass) { return null; }
}
