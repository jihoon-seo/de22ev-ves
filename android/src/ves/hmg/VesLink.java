package ves.hmg;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The app's line to the vesd service: abstract socket "de22ev-ves". One request
 * per connection: a single line "COMMAND [args]\n"; the answer is UTF-8 text until
 * the service closes the socket. The first answer line is "ok" or "error <why>".
 */
public final class VesLink {
    public static final String SOCKET = "de22ev-ves";

    private VesLink() {}

    public static String send(String line, int timeoutMs) throws IOException {
        LocalSocket s = new LocalSocket();
        try {
            s.connect(new LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.ABSTRACT));
            s.setSoTimeout(timeoutMs);
            OutputStream out = s.getOutputStream();
            out.write((line.replace('\n', ' ') + "\n").getBytes("UTF-8"));
            out.flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                b.write(buf, 0, n);
                if (b.size() > (1 << 20)) break;
            }
            return b.toString("UTF-8");
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }
}
