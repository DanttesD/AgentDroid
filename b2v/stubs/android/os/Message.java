package android.os;

public final class Message {
    public int what;
    public Object obj;
    public static Message obtain() { return new Message(); }
}
