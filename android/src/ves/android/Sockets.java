package ves.android;

import android.net.LocalSocket;

import java.io.FileDescriptor;
import java.lang.reflect.Method;

/**
 * Android 4.4's LocalSocketImpl.accept() doesn't mark the accepted socket's descriptor
 * as its own (mFdCreatedInternally stays false), so LocalSocket.close() only forgets the
 * descriptor: the peer never sees the end of the answer and the service leaks one
 * descriptor per connection. Fixed in Android 5; checked on the car's framework
 * (DE22EV 260902). closeAccepted ends the answer and closes the descriptor itself.
 */
final class Sockets {
    private Sockets() {}

    static void closeAccepted(LocalSocket s) {
        FileDescriptor fd = null;
        try {
            fd = s.getFileDescriptor(); // before close(), which drops it
        } catch (Throwable ignored) {
        }
        try {
            s.shutdownOutput(); // the peer reads the end of the answer now
        } catch (Throwable ignored) {
        }
        try {
            s.close();
        } catch (Throwable ignored) {
        }
        if (fd != null && fd.valid()) closeFd(fd); // still open: the 4.4 case
    }

    private static Method close;
    private static Object os;

    private static synchronized void closeFd(FileDescriptor fd) {
        try {
            if (close == null) {
                os = Class.forName("libcore.io.Libcore").getField("os").get(null);
                close = os.getClass().getMethod("close", FileDescriptor.class);
            }
            close.invoke(os, fd); // also marks fd invalid
        } catch (Throwable e) {
            Out.log("control: cannot close a connection: " + e);
        }
    }
}
