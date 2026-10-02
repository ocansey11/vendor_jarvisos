package android.os;
/** Stub. */
public class ResultReceiver {
    public static final Creator CREATOR = new Creator();
    public ResultReceiver(Handler handler) {}
    protected void onReceiveResult(int resultCode, Bundle resultData) {}
    public void writeToParcel(Parcel out, int flags) {}
    public static class Creator {
        public ResultReceiver createFromParcel(Parcel in) { return new ResultReceiver(null); }
    }
}
