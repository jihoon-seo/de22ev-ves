package hmgapi.engine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Desktop stand-in for HMG-API's Engine: only the three file helpers that HMG-API's
 * Signing and Json use, so those two upstream files (fetched by build.sh, AGPL-3.0)
 * compile on their own into the mod signer.
 */
final class Engine {
    private Engine() {}

    static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toByteArray();
        } finally {
            in.close();
        }
    }

    static String read(File f) throws IOException {
        return new String(readAll(new FileInputStream(f)), "UTF-8");
    }

    static void write(File f, String s) throws IOException {
        OutputStream o = new FileOutputStream(f);
        try {
            o.write(s.getBytes("UTF-8"));
        } finally {
            o.close();
        }
    }
}
