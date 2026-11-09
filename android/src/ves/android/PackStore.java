package ves.android;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import ves.core.Pack;
import ves.core.PackError;
import ves.core.PackLoader;

/**
 * Where vesd finds sound packs, and the import of the owner's packs from USB.
 * <ul>
 * <li>built-in slots (i4t, v6, v8, i6): entries packs/&lt;slot&gt;/ inside the mod's vesd.jar. HMG-API makes
 * the mod's folder root-only and only the service's own jar readable to its user (system), so a loose
 * packs/ folder never loaded on the car (0.2.0-0.2.4: "v8: empty", the synth played);</li>
 * <li>user slots (user1..3): the service's own data folder, packs/&lt;slot&gt;/ (HMGAPI_DATA), filled by
 * import from the stick's VES/packs/&lt;name&gt;/.</li>
 * </ul>
 * An import validates the pack on the stick with PackLoader, copies pack.txt and its .wav files into a
 * temporary folder, validates the copy again, and only then replaces the slot. Nothing else is written.
 */
final class PackStore {
    static final String[] BUILT_IN = { "i4t", "v6", "v8", "i6" };
    static final long MAX_COPY = 3L * 1024 * 1024;
    static int jarOpens; // for the host test: vesd.jar opened to read names

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

    /** a user slot's folder (null for anything else) */
    File dir(String slot) {
        if (isUserSlot(slot)) return new File(new File(dataDir, "packs"), slot);
        return null;
    }

    static boolean isBuiltIn(String slot) {
        for (String b : BUILT_IN) if (b.equals(slot)) return true;
        return false;
    }

    File jar() {
        return new File(modDir, "vesd.jar");
    }

    /** the pack in a slot; PackError with the reason when it is missing or invalid */
    Pack load(String slot) throws PackError {
        if (isBuiltIn(slot)) return loadBuiltIn(slot);
        File d = dir(slot);
        if (d == null) throw new PackError(slot + ": not a pack slot");
        if (!new File(d, "pack.txt").isFile()) throw new PackError(slot + ": empty");
        return PackLoader.load(new DirSource(d));
    }

    private Pack loadBuiltIn(String slot) throws PackError {
        ZipFile z;
        try {
            z = new ZipFile(jar());
        } catch (IOException e) {
            throw new PackError(slot + ": empty (no readable vesd.jar: " + e.getMessage() + ")");
        }
        try {
            String prefix = "packs/" + slot + "/";
            if (z.getEntry(prefix + "pack.txt") == null) throw new PackError(slot + ": empty");
            return PackLoader.load(new ZipSource(z, prefix));
        } finally {
            try {
                z.close();
            } catch (IOException ignored) {
            }
        }
    }

    private Map<String, String> builtInNames; // read once: the jar only changes with a service restart

    private synchronized String builtInName(String slot) {
        if (builtInNames == null) {
            builtInNames = new HashMap<String, String>();
            jarOpens++;
            ZipFile z = null;
            try {
                z = new ZipFile(jar());
                for (String b : BUILT_IN) {
                    try {
                        builtInNames.put(b, nameFrom(new ZipSource(z, "packs/" + b + "/").read("pack.txt", 16 * 1024)));
                    } catch (IOException ignored) {
                    }
                }
            } catch (IOException ignored) {
            } finally {
                if (z != null) {
                    try {
                        z.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        String n = builtInNames.get(slot);
        return n == null ? "-" : n;
    }

    /** "slot name|-" per line, for the app */
    String list() {
        StringBuilder b = new StringBuilder();
        List<String> slots = new ArrayList<String>();
        for (String s : BUILT_IN) slots.add(s);
        slots.add("user1");
        slots.add("user2");
        slots.add("user3");
        for (String s : slots) b.append(s).append(' ').append(nameOf(s)).append('\n');
        return b.toString();
    }

    /** the name line of a slot's pack.txt, or "-" (cheap: the app asks every second; load() validates) */
    String nameOf(String slot) {
        if (isBuiltIn(slot)) return builtInName(slot);
        File d = dir(slot);
        if (d == null) return "-";
        try {
            return nameFrom(new DirSource(d).read("pack.txt", 16 * 1024));
        } catch (IOException ignored) {
        }
        return "-";
    }

    static String nameFrom(byte[] packTxt) throws IOException {
        String t = new String(packTxt, "UTF-8");
        for (String l : t.split("\n")) {
            l = l.trim();
            if (l.startsWith("name ")) {
                String n = l.substring(5).trim().replaceAll("[^\\p{L}\\p{N} ._-]", "");
                return n.length() == 0 ? "-" : n.length() > 40 ? n.substring(0, 40) : n;
            }
        }
        return "-";
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

    /** reads a pack's files from entries prefix + name of a zip (vesd.jar) */
    static final class ZipSource implements PackLoader.Source {
        private final ZipFile zip;
        private final String prefix;

        ZipSource(ZipFile zip, String prefix) {
            this.zip = zip;
            this.prefix = prefix;
        }

        public byte[] read(String name, int maxBytes) throws IOException {
            ZipEntry e = zip.getEntry(prefix + name);
            if (e == null) throw new IOException(name + " missing");
            long len = e.getSize();
            if (len > maxBytes) throw new IOException(name + " too big");
            InputStream in = zip.getInputStream(e);
            try {
                java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(len > 0 ? (int) len : 8192);
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) > 0) {
                    o.write(b, 0, n);
                    if (o.size() > maxBytes) throw new IOException(name + " too big");
                }
                return o.toByteArray();
            } finally {
                in.close();
            }
        }
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
