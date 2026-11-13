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
            st.load("vex4");
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
        for (String[] bad : new String[][] {{"../mycar", "user2"}, {"mycar", "vex4"}, {"mycar", "../user1"}, {"nope", "user2"}}) {
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
        // PS-C1 (0.2.8): a pack the size of S2000 Baked (about 3.3 MB) imports; one over 5 MB is refused
        writeBig(new File(usb, "packs/big"), "big", 38);
        check("big".equals(st.importPack(sticks, "big", "user2")) && st.load("user2").on.length == 38,
                "PS-C1 a 3.4 MB pack imports into a user slot");
        writeBig(new File(usb, "packs/huge"), "huge", 60);
        try {
            st.importPack(sticks, "huge", "user2");
            check(false, "PS-C1 a pack over 5 MB is refused");
        } catch (PackError e) {
            check(e.getMessage().contains("larger than"), "PS-C1 a pack over 5 MB is refused: " + e.getMessage());
        }
        check("big".equals(st.load("user2").name), "PS-C1 the refused import leaves user2 as it was");
        st.importPack(sticks, "mycar", "user1"); // replace in place
        check("my car".equals(st.load("user1").name) && !new File(data, "packs/.user1.old").exists()
                && !new File(data, "packs/.user1.new").exists(), "re-import replaces cleanly");

        // built-ins live inside vesd.jar (0.2.5): HMG-API makes the mod's folder root-only and only the
        // service's own jar readable, so a loose packs/ folder never loaded on the car (vesd runs as system)
        File mod2 = new File(root, "mod2");
        writePack(new File(root, "src/vex4"), "VEX 4-cyl", 1);
        writePack(new File(root, "src/gt3"), "911 GT3 992", 1);
        jar(new File(mod2, "vesd.jar"), new File(root, "src"), "packs/", "bad/pack.txt");
        PackStore j = new PackStore(mod2, data);
        check(!new File(mod2, "packs").exists(), "no loose packs folder in the mod");
        try {
            check("VEX 4-cyl".equals(j.load("vex4").name), "built-in vex4 loads from vesd.jar");
            check("911 GT3 992".equals(j.load("gt3").name), "built-in gt3 loads from vesd.jar");
        } catch (PackError e) {
            check(false, "built-in from vesd.jar: " + e.getMessage());
        }
        try {
            j.load("ae86");
            check(false, "a built-in missing from the jar is refused");
        } catch (PackError e) {
            check(e.getMessage().contains("empty"), "a built-in missing from the jar: " + e.getMessage());
        }
        int before = PackStore.jarOpens;
        for (int i = 0; i < 5; i++) j.list();
        String l = j.list();
        check(l.contains("vex4 VEX 4-cyl") && l.contains("gt3 911 GT3 992") && l.contains("ae86 -") && l.contains("user1 my car"),
                "list: built-ins from the jar, user slots from data: " + l.replace('\n', ';'));
        check(PackStore.jarOpens - before <= 1, "list reads the jar's names once: " + (PackStore.jarOpens - before) + " opens");
        PackStore none = new PackStore(new File(root, "nomod"), data);
        try {
            none.load("vex4");
            check(false, "no vesd.jar: refused");
        } catch (PackError e) {
            check(true, "no vesd.jar: " + e.getMessage());
        }
        check(none.list().contains("vex4 -"), "no vesd.jar: list still answers");

        // 0.2.8: a chosen pack that does not load falls back to the default built-in (vex4), then to the synth
        StringBuilder note = new StringBuilder();
        try {
            check("911 GT3 992".equals(j.loadOrDefault("gt3", note).name) && note.toString().equals("gt3: 911 GT3 992"),
                    "PS-F0 a pack that loads is used as it is: " + note);
        } catch (PackError e) {
            check(false, "PS-F0 gt3: " + e.getMessage());
        }
        note.setLength(0);
        try {
            ves.core.Pack p = j.loadOrDefault("user3", note);
            check("VEX 4-cyl".equals(p.name) && note.indexOf("user3: empty") >= 0 && note.indexOf("vex4 plays") >= 0,
                    "PS-F1 empty user3 -> the vex4 pack: " + p.name + ", note " + note);
        } catch (PackError e) {
            check(false, "PS-F1 empty user3 -> vex4: " + e.getMessage());
        }
        check("vex4".equals(PackStore.DEFAULT), "PackStore.DEFAULT is vex4");
        File src3 = new File(root, "src3"), mod3 = new File(root, "mod3");
        writePack(new File(src3, "gt3"), "911 GT3 992", 1);
        jar(new File(mod3, "vesd.jar"), src3, "packs/", "bad/pack.txt");
        try {
            new PackStore(mod3, data).loadOrDefault("user3", new StringBuilder());
            check(false, "PS-F2 no vex4 in the jar + an empty slot: PackError");
        } catch (PackError e) {
            check(e.getMessage().contains("user3") && e.getMessage().contains("vex4"), "PS-F2 no vex4 in the jar + an empty slot: " + e.getMessage());
        }
        File src4 = new File(root, "src4"), mod4 = new File(root, "mod4");
        new File(src4, "vex4").mkdirs();
        write(new File(src4, "vex4/pack.txt"), "name broken\ncylinders 4\n".getBytes("UTF-8"));
        jar(new File(mod4, "vesd.jar"), src4, "packs/", "bad/pack.txt");
        PackStore broken = new PackStore(mod4, data);
        for (String slot : new String[] { "vex4", "user3" }) {
            try {
                broken.loadOrDefault(slot, new StringBuilder());
                check(false, "PS-F3 broken vex4 (" + slot + "): PackError");
            } catch (PackError e) {
                String m = e.getMessage();
                check(m.indexOf("vex4:") == m.lastIndexOf("vex4:"), "PS-F3 broken vex4 (" + slot + "): one PackError, no loop: " + m);
            }
        }
        java.util.List<String> want = new java.util.ArrayList<String>(java.util.Arrays.asList(PackStore.BUILT_IN));
        want.addAll(java.util.Arrays.asList(PackStore.USER_SLOTS));
        check(want.equals(java.util.Arrays.asList(ves.core.Settings.SOUNDS)), "PS-L1 Settings.SOUNDS == BUILT_IN + USER_SLOTS: "
                + java.util.Arrays.asList(ves.core.Settings.SOUNDS));
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

    /** n on-load loops of 2 s each (88 KB) at distinct rpm from 900 up */
    static void writeBig(File dir, String name, int n) throws IOException {
        dir.mkdirs();
        StringBuilder t = new StringBuilder("name " + name + "\ncylinders 4\nidle 800\nredline 9000\n");
        for (int i = 0; i < n; i++) {
            int r = 900 + i * 100;
            String f = "b" + r + ".wav";
            write(new File(dir, f), wav(1, 44100, r / 30.0));
            t.append("loop ").append(f).append(" rpm=").append(r).append(" load=on\n");
        }
        write(new File(dir, "pack.txt"), t.toString().getBytes("UTF-8"));
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
