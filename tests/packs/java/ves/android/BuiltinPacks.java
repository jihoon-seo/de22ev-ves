package ves.android;

import java.io.File;
import java.util.Arrays;
import java.util.TreeSet;

import ves.core.Pack;
import ves.core.PackError;

/**
 * BP1/BP5 (tests/packs/run.sh): every PackStore.BUILT_IN slot loads from a vesd.jar made of the repository's
 * packs/, and the packs/ folders are exactly the BUILT_IN slots. Args: mod folder (with vesd.jar), packs folder.
 */
public final class BuiltinPacks {
    public static void main(String[] args) throws Exception {
        int failed = 0;
        PackStore st = new PackStore(new File(args[0]), new File(args[0], "nodata"));
        StringBuilder bad = new StringBuilder(), ok = new StringBuilder();
        for (String slot : PackStore.BUILT_IN) {
            try {
                Pack p = st.load(slot);
                ok.append(' ').append(slot).append('=').append(p.name);
            } catch (PackError e) {
                bad.append(' ').append(e.getMessage());
            }
        }
        if (bad.length() == 0) System.out.println("PASS BP1 every built-in loads from vesd.jar:" + ok);
        else {
            System.out.println("FAIL BP1 built-in packs that do not load:" + bad);
            failed++;
        }
        TreeSet<String> dirs = new TreeSet<String>();
        File[] fs = new File(args[1]).listFiles();
        if (fs != null) for (File f : fs) if (f.isDirectory()) dirs.add(f.getName());
        TreeSet<String> want = new TreeSet<String>(Arrays.asList(PackStore.BUILT_IN));
        if (dirs.equals(want)) System.out.println("PASS BP5 packs/ folders == PackStore.BUILT_IN " + want);
        else {
            System.out.println("FAIL BP5 packs/ folders " + dirs + " != PackStore.BUILT_IN " + want);
            failed++;
        }
        System.exit(failed == 0 ? 0 : 1);
    }
}
