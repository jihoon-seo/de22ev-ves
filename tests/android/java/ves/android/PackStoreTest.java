package ves.android;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.List;

import ves.core.PackError;

/** Host test of PackStore (plain java.io): import from a fake USB stick into user slots, and refusals. */
public final class PackStoreTest {
    static int failed;

    static void check(boolean c, String what) {
        System.out.println((c ? "PASS " : "FAIL ") + what);
        if (!c) failed++;
    }

    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        File mod = new File(root, "mod"), data = new File(root, "data"), usb = new File(root, "usb/VES");
        PackStore st = new PackStore(mod, data);
        List<String> sticks = Collections.singletonList(usb.getPath());

        writePack(new File(usb, "packs/mycar"), "my car", 1);
        writePack(new File(usb, "packs/stereo"), "bad", 2);
        new File(usb, "packs/notapack").mkdirs();

        check(PackStore.usbPacks(sticks).contains("mycar") && !PackStore.usbPacks(sticks).contains("notapack"), "lists pack folders on the stick");
        try {
            st.load("v8");
            check(false, "missing built-in refused");
        } catch (PackError e) {
            check(e.getMessage().contains("empty"), "missing built-in: " + e.getMessage());
        }
        String name = st.importPack(sticks, "mycar", "user1");
        check("my car".equals(name), "import returns the pack name");
        check("my car".equals(st.load("user1").name), "user1 loads after import");
        check(st.list().contains("user1 my car"), "list shows it: " + st.list().replace('\n', ';'));
        try {
            st.importPack(sticks, "stereo", "user1");
            check(false, "invalid pack refused");
        } catch (PackError e) {
            check("my car".equals(st.load("user1").name), "a refused import leaves the slot as it was (" + e.getMessage() + ")");
        }
        for (String[] bad : new String[][] {{"../mycar", "user2"}, {"mycar", "v8"}, {"mycar", "../user1"}, {"nope", "user2"}}) {
            try {
                st.importPack(sticks, bad[0], bad[1]);
                check(false, "refused " + bad[0] + " -> " + bad[1]);
            } catch (PackError e) {
                check(true, "refused " + bad[0] + " -> " + bad[1] + ": " + e.getMessage());
            }
        }
        // eight user slots (0.2.6)
        check("my car".equals(st.importPack(sticks, "mycar", "user8")) && "my car".equals(st.load("user8").name),
                "import into user8 and load it");
        String all = st.list();
        int last = -1;
        boolean ordered = true;
        for (int i = 1; i <= 8; i++) {
            int at = all.indexOf("user" + i + " ");
            ordered &= at > last;
            last = at;
        }
        check(ordered, "list shows user1..user8 in order: " + all.replace('\n', ';'));
        for (String bad : new String[] { "user0", "user9", "user10" }) {
            try {
                st.importPack(sticks, "mycar", bad);
                check(false, "refused slot " + bad);
            } catch (PackError e) {
                check(true, "refused slot " + bad + ": " + e.getMessage());
            }
        }
        st.importPack(sticks, "mycar", "user1"); // replace in place
        check("my car".equals(st.load("user1").name) && !new File(data, "packs/.user1.old").exists()
                && !new File(data, "packs/.user1.new").exists(), "re-import replaces cleanly");

        // built-ins live inside vesd.jar (0.2.5): HMG-API makes the mod's folder root-only and only the
        // service's own jar readable, so a loose packs/ folder never loaded on the car (vesd runs as system)
        File mod2 = new File(root, "mod2");
        writePack(new File(root, "src/v8"), "Cross V8", 1);
        writePack(new File(root, "src/i6"), "Inline 6", 1);
        jar(new File(mod2, "vesd.jar"), new File(root, "src"), "packs/", "bad/pack.txt");
        PackStore j = new PackStore(mod2, data);
        check(!new File(mod2, "packs").exists(), "no loose packs folder in the mod");
        try {
            check("Cross V8".equals(j.load("v8").name), "built-in v8 loads from vesd.jar");
            check("Inline 6".equals(j.load("i6").name), "built-in i6 loads from vesd.jar");
        } catch (PackError e) {
            check(false, "built-in from vesd.jar: " + e.getMessage());
        }
        try {
            j.load("v6");
            check(false, "a built-in missing from the jar is refused");
        } catch (PackError e) {
            check(e.getMessage().contains("empty"), "a built-in missing from the jar: " + e.getMessage());
        }
        int before = PackStore.jarOpens;
        for (int i = 0; i < 5; i++) j.list();
        String l = j.list();
        check(l.contains("v8 Cross V8") && l.contains("i6 Inline 6") && l.contains("v6 -") && l.contains("user1 my car"),
                "list: built-ins from the jar, user slots from data: " + l.replace('\n', ';'));
        check(PackStore.jarOpens - before <= 1, "list reads the jar's names once: " + (PackStore.jarOpens - before) + " opens");
        PackStore none = new PackStore(new File(root, "nomod"), data);
        try {
            none.load("v8");
            check(false, "no vesd.jar: refused");
        } catch (PackError e) {
            check(true, "no vesd.jar: " + e.getMessage());
        }
        check(none.list().contains("v8 -"), "no vesd.jar: list still answers");
        System.out.println(failed == 0 ? "packstore: all checks passed" : "packstore: " + failed + " check(s) failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    /** a jar with prefix + each folder's files under src (packs/<folder>/<file>), plus one extra entry */
    static void jar(File out, File src, String prefix, String extra) throws IOException {
        out.getParentFile().mkdirs();
        java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(new FileOutputStream(out));
        try {
            z.putNextEntry(new java.util.zip.ZipEntry("classes.dex"));
            z.write(new byte[] { 1, 2, 3 });
            for (File d : src.listFiles()) {
                for (File f : d.listFiles()) {
                    z.putNextEntry(new java.util.zip.ZipEntry(prefix + d.getName() + "/" + f.getName()));
                    z.write(java.nio.file.Files.readAllBytes(f.toPath()));
                }
            }
            z.putNextEntry(new java.util.zip.ZipEntry(extra));
            z.write("x".getBytes("UTF-8"));
        } finally {
            z.close();
        }
    }

    static void writePack(File dir, String name, int channels) throws IOException {
        dir.mkdirs();
        StringBuilder t = new StringBuilder("name " + name + "\ncylinders 4\nidle 800\nredline 6500\n");
        int[] rpms = {900, 2000, 3500, 5000};
        for (int r : rpms) {
            String f = "l" + r + ".wav";
            write(new File(dir, f), wav(channels, 11025, r / 30.0));
            t.append("loop ").append(f).append(" rpm=").append(r).append(" load=on\n");
        }
        write(new File(dir, "pack.txt"), t.toString().getBytes("UTF-8"));
        write(new File(dir, "readme.txt"), "not copied".getBytes("UTF-8"));
    }

    static void write(File f, byte[] b) throws IOException {
        FileOutputStream o = new FileOutputStream(f);
        try {
            o.write(b);
        } finally {
            o.close();
        }
    }

    static byte[] wav(int ch, int samples, double hz) {
        int data = samples * ch * 2;
        ByteBuffer b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + data).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) ch).putInt(22050)
                .putInt(22050 * ch * 2).putShort((short) (ch * 2)).putShort((short) 16);
        b.put("data".getBytes()).putInt(data);
        for (int i = 0; i < samples; i++) for (int c = 0; c < ch; c++) b.putShort((short) (Math.sin(2 * Math.PI * hz * i / 22050) * 9000));
        return b.array();
    }
}
