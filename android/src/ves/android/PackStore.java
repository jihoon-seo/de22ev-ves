package ves.android;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import ves.core.Pack;
import ves.core.PackError;
import ves.core.PackLoader;

/**
 * Where vesd finds sound packs, and the import of the owner's packs from USB.
 * <ul>
 * <li>built-in slots (i4t, v6, v8, i6): the mod's folder, packs/&lt;slot&gt;/ (HMGAPI_MOD_DIR);</li>
 * <li>user slots (user1..3): the service's own data folder, packs/&lt;slot&gt;/ (HMGAPI_DATA), filled by
 * import from the stick's VES/packs/&lt;name&gt;/.</li>
 * </ul>
 * An import validates the pack on the stick with PackLoader, copies pack.txt and its .wav files into a
 * temporary folder, validates the copy again, and only then replaces the slot. Nothing else is written.
 */
final class PackStore {
    static final String[] BUILT_IN = { "i4t", "v6", "v8", "i6" };
    static final long MAX_COPY = 3L * 1024 * 1024;

    private final File modDir, dataDir;

    PackStore(File modDir, File dataDir) {
        this.modDir = modDir;
        this.dataDir = dataDir;
    }

    static PackStore fromEnv() {
        String m = System.getenv("HMGAPI_MOD_DIR"), d = System.getenv("HMGAPI_DATA");
        return new PackStore(new File(m != null ? m : "/data/hmgapi/mods/de22ev-ves"),
                new File(d != null ? d : "/data/hmgapi/run/de22ev-ves/vesd"));
    }

    static boolean isUserSlot(String s) {
        return "user1".equals(s) || "user2".equals(s) || "user3".equals(s);
    }

    File dir(String slot) {
        if (isUserSlot(slot)) return new File(new File(dataDir, "packs"), slot);
        for (String b : BUILT_IN) if (b.equals(slot)) return new File(new File(modDir, "packs"), slot);
        return null;
    }

    /** the pack in a slot; PackError with the reason when it is missing or invalid */
    Pack load(String slot) throws PackError {
        File d = dir(slot);
        if (d == null) throw new PackError(slot + ": not a pack slot");
        if (!new File(d, "pack.txt").isFile()) throw new PackError(slot + ": empty");
        return PackLoader.load(new DirSource(d));
    }

    /** "slot name|-" per line, for the app */
    String list() {
        StringBuilder b = new StringBuilder();
        List<String> slots = new ArrayList<String>();
        for (String s : BUILT_IN) slots.add(s);
        slots.add("user1");
        slots.add("user2");
        slots.add("user3");
        for (String s : slots) {
            String name;
            try {
                name = load(s).name;
            } catch (PackError e) {
                name = "-";
            }
            b.append(s).append(' ').append(name.replace('\n', ' ')).append('\n');
        }
        return b.toString();
    }

    /** pack folder names under the stick's VES/packs/ */
    static List<String> usbPacks(List<String> usbFolders) {
        List<String> out = new ArrayList<String>();
        for (String u : usbFolders) {
            File[] fs = new File(u, "packs").listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                if (f.isDirectory() && f.getName().matches("[A-Za-z0-9_-]{1,40}") && new File(f, "pack.txt").isFile()) out.add(f.getName());
            }
        }
        return out;
    }

    /** copies VES/packs/&lt;name&gt; into a user slot; returns the pack's name */
    String importPack(List<String> usbFolders, String name, String slot) throws PackError, IOException {
        if (!isUserSlot(slot)) throw new PackError("slot must be user1, user2 or user3");
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,40}")) throw new PackError("bad pack folder name");
        File src = null;
        for (String u : usbFolders) {
            File f = new File(new File(u, "packs"), name);
            if (new File(f, "pack.txt").isFile()) src = f;
        }
        if (src == null) throw new PackError("no VES/packs/" + name + " on the USB stick");
        Pack p = PackLoader.load(new DirSource(src)); // validate on the stick first
        File packs = new File(dataDir, "packs");
        File tmp = new File(packs, "." + slot + ".new"), old = new File(packs, "." + slot + ".old"), dst = new File(packs, slot);
        deleteTree(tmp);
        deleteTree(old);
        if (!tmp.mkdirs()) throw new IOException("cannot create " + tmp);
        long total = 0;
        File[] fs = src.listFiles();
        if (fs == null) throw new IOException("cannot list " + src);
        for (File f : fs) {
            String n = f.getName();
            if (!(n.equals("pack.txt") || n.matches("[a-z0-9_-]{1,60}\\.wav")) || !f.isFile()) continue;
            total += f.length();
            if (total > MAX_COPY) {
                deleteTree(tmp);
                throw new PackError("pack folder larger than 3 MB");
            }
            copy(f, new File(tmp, n));
        }
        try {
            PackLoader.load(new DirSource(tmp)); // the copy must be as good as the original
        } catch (PackError e) {
            deleteTree(tmp);
            throw e;
        }
        if (dst.exists() && !dst.renameTo(old)) throw new IOException("cannot move the old pack aside");
        if (!tmp.renameTo(dst)) {
            old.renameTo(dst);
            throw new IOException("cannot install the pack");
        }
        deleteTree(old);
        return p.name;
    }

    private static void copy(File from, File to) throws IOException {
        InputStream in = new FileInputStream(from);
        try {
            OutputStream out = new FileOutputStream(to);
            try {
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    private static void deleteTree(File f) {
        if (!f.exists()) return;
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) if (c.isFile()) c.delete(); // packs are flat: files only
        f.delete();
    }

    /** reads a pack's files from a folder (names already validated by PackLoader: no separators) */
    static final class DirSource implements PackLoader.Source {
        private final File dir;

        DirSource(File dir) {
            this.dir = dir;
        }

        public byte[] read(String name, int maxBytes) throws IOException {
            File f = new File(dir, name);
            long len = f.length();
            if (!f.isFile()) throw new IOException(name + " missing");
            if (len > maxBytes) throw new IOException(name + " too big");
            byte[] b = new byte[(int) len];
            InputStream in = new FileInputStream(f);
            try {
                int off = 0;
                while (off < b.length) {
                    int n = in.read(b, off, b.length - off);
                    if (n < 0) break;
                    off += n;
                }
                if (off != b.length) throw new IOException(name + " short read");
            } finally {
                in.close();
            }
            return b;
        }
    }
}
