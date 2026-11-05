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
        st.importPack(sticks, "mycar", "user1"); // replace in place
        check("my car".equals(st.load("user1").name) && !new File(data, "packs/.user1.old").exists()
                && !new File(data, "packs/.user1.new").exists(), "re-import replaces cleanly");
        System.out.println(failed == 0 ? "packstore: all checks passed" : "packstore: " + failed + " check(s) failed");
        System.exit(failed == 0 ? 0 : 1);
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
