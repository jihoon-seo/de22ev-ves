package ves.resynth;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** small file helpers */
final class Files {
    private Files() {
    }

    static void writeText(File f, String s) throws IOException {
        OutputStream o = new FileOutputStream(f);
        try {
            o.write(s.getBytes("UTF-8"));
        } finally {
            o.close();
        }
    }
}
