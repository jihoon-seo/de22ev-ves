package android.os;

/** Compile-only stand-in for the hidden framework class (not packaged). */
public final class ServiceManager {
    public static IBinder getService(String name) {
        throw new RuntimeException("stub");
    }

    public static IBinder checkService(String name) {
        throw new RuntimeException("stub");
    }

    public static String[] listServices() {
        throw new RuntimeException("stub");
    }
}
