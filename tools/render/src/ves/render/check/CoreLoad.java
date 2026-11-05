/*
 * ves.render.check: analysis helpers for tests/render/run.sh (host only).
 * Part of the Java port of DasEtwas/enginesound <https://github.com/DasEtwas/enginesound>,
 * upstream commit e5fcca587397c0c8ba9c9d24874b951fed74d260 (this file is new code, no upstream source).
 * Copyright (c) 2020 DasEtwas. MIT License; the full notice is in THIRD_PARTY.md.
 * The Java port and its throttle extension are MIT as well.
 */
package ves.render.check;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * {@code CoreLoad <packdir>}: loads a rendered pack through the runtime's own loader,
 * ves.core.PackLoader (core/src, on the classpath at run time; reflection keeps tools/render
 * compilable without core). Prints PASS with a summary, or FAIL with the loader's reason; exit 1
 * on FAIL.
 */
public final class CoreLoad {
    private CoreLoad() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: CoreLoad <packdir>");
            System.exit(2);
        }
        final File dir = new File(args[0]);
        Class<?> loader = Class.forName("ves.core.PackLoader");
        Class<?> source = Class.forName("ves.core.PackLoader$Source");
        Object src = Proxy.newProxyInstance(source.getClassLoader(), new Class<?>[] {source}, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method m, Object[] a) throws IOException {
                if (!m.getName().equals("read")) {
                    throw new UnsupportedOperationException(m.getName());
                }
                return read(new File(dir, (String) a[0]), (Integer) a[1]);
            }
        });
        Object pack;
        try {
            pack = loader.getMethod("load", source).invoke(null, src);
        } catch (InvocationTargetException e) {
            System.out.println("FAIL " + dir.getName() + ": ves.core.PackLoader rejects the pack: " + e.getCause());
            System.exit(1);
            return;
        }
        Class<?> pc = pack.getClass();
        Object[] on = (Object[]) pc.getField("on").get(pack);
        Object[] off = (Object[]) pc.getField("off").get(pack);
        boolean aligned = pc.getField("aligned").getBoolean(pack);
        int samples = 0;
        for (Object[] layer : new Object[][] {on, off}) {
            for (Object l : layer) {
                samples += ((short[]) l.getClass().getField("pcm").get(l)).length;
            }
        }
        boolean ok = aligned && on.length == 10 && off.length == 10;
        System.out.println((ok ? "PASS " : "FAIL ") + dir.getName() + ": ves.core.PackLoader loads the pack (name="
                + pc.getField("name").get(pack) + " cylinders=" + pc.getField("cylinders").get(pack)
                + " aligned=" + aligned + " on=" + on.length + " off=" + off.length + " samples=" + samples + ")");
        System.exit(ok ? 0 : 1);
    }

    static byte[] read(File f, int maxBytes) throws IOException {
        if (f.length() > maxBytes) {
            throw new IOException(f.getName() + " is larger than " + maxBytes + " bytes");
        }
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[65536];
            int r;
            while ((r = in.read(b)) > 0) {
                bo.write(b, 0, r);
            }
            return bo.toByteArray();
        } finally {
            in.close();
        }
    }
}
