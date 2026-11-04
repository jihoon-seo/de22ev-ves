package ves.android;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;

/**
 * The head unit's audio manager (MicomAudioService in automotive-service), reached
 * as raw binder calls so nothing OEM is needed at build time. Firmware
 * DE22EV.KOR.0000.V039.001.260902, com.hkmc.media.IMicomAudioService:
 *   3 registerListener(IMicomAudioStateChangeListener)   4 unregisterListener(same)
 *   0x17 startAudio(int type, int, int) -> boolean        0x18 endAudio(int type, int) -> boolean
 *   0x7e hasStreamTypeInAudioStack(int type) -> boolean
 * startAudio opens the MICOM's mixing channel for a stream type (the stock
 * navigation does startAudio(NAVI_SOUNDEFFECT 0x45) around an AudioTrack on
 * Android stream 12, endAudio after). The listener's calls are oneway.
 *
 * Only these five calls are ever made (tests/android/allowlist.sh checks it).
 */
public final class MicomAudio {
    public static final String SERVICE = "com.hkmc.micom.audiosetup";
    static final String DESC = "com.hkmc.media.IMicomAudioService";
    static final String LDESC = "com.hkmc.media.IMicomAudioStateChangeListener";
    static final int T_REGISTER = 3, T_UNREGISTER = 4, T_START = 0x17, T_END = 0x18, T_HAS = 0x7e;

    /** MICOM stream types (MicomAudioManagerConstant$StreamType) */
    public static final int NAVI = 0x3, TTS = 0x7, RING = 0x9, GUIDE = 0xa, AANAVI = 0x12, VOICEREC = 0x15,
            VUI = 0x6, EMERGENCY = 0x1f, EMERGENCY_KIA = 0x22, TTS_EFFECT_SOUND = 0x39, NAVI_SOUNDEFFECT = 0x45,
            SYSTEM_ALARM = 0x46, UVOBLUE = 0x5, UVOBLUERING = 0x23, BTSIRI = 0x14, CARLIFE_NAVI = 0x24,
            MIRRORLINK_NAVI = 0x19, AA_VR_AV = 0x3f, PREVIEWAUDIOTTS = 0x1d, BTSMSREADING = 0x25, ANNOUNCE = 0x8;

    /** what the listener reports, already mapped to yield sources (or null) */
    public interface Events {
        void onYield(String source, boolean start, String raw);

        void onOther(String raw);
    }

    private IBinder svc;
    private Listener listener;

    public synchronized boolean connect() {
        try {
            svc = ServiceManager.getService(SERVICE);
        } catch (Throwable t) {
            svc = null;
        }
        return svc != null;
    }

    public synchronized boolean alive() {
        return svc != null && svc.isBinderAlive();
    }

    public boolean startAudio(int type) throws RemoteException {
        return callBool(T_START, new int[] { type, 0, 0 });
    }

    public boolean endAudio(int type) throws RemoteException {
        return callBool(T_END, new int[] { type, 0 });
    }

    public boolean hasStreamTypeInAudioStack(int type) throws RemoteException {
        return callBool(T_HAS, new int[] { type });
    }

    public synchronized void register(Events e, int ownType) throws RemoteException {
        if (svc == null) throw new RemoteException();
        listener = new Listener(e, ownType);
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESC);
            d.writeStrongBinder(listener);
            svc.transact(T_REGISTER, d, r, 0);
            r.readException();
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    public synchronized void unregister() {
        if (svc == null || listener == null) return;
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESC);
            d.writeStrongBinder(listener);
            svc.transact(T_UNREGISTER, d, r, 0);
            r.readException();
        } catch (Throwable ignored) {
        } finally {
            d.recycle();
            r.recycle();
            listener = null;
        }
    }

    public synchronized void setOwnType(int t) {
        if (listener != null) listener.ownType = t;
    }

    private synchronized boolean callBool(int code, int[] args) throws RemoteException {
        if (svc == null) throw new RemoteException();
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESC);
            for (int a : args) d.writeInt(a);
            svc.transact(code, d, r, 0);
            r.readException();
            return r.readInt() != 0;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    /** Hand-written Stub for IMicomAudioStateChangeListener (transaction codes from the firmware). */
    static final class Listener extends Binder {
        final Events ev;
        volatile int ownType;

        Listener(Events ev, int ownType) {
            this.ev = ev;
            this.ownType = ownType;
            attachInterface(null, LDESC);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(LDESC);
                return true;
            }
            try {
                data.enforceInterface(LDESC);
            } catch (Throwable t) {
                return false;
            }
            String raw = "cb" + code;
            try {
                switch (code) {
                    case 0x10: ev.onYield("navi", true, "NavigationVoiceStart"); break;
                    case 0x11: ev.onYield("navi", false, "NavigationVoiceEnd"); break;
                    case 0x19: ev.onYield("tts", true, "TtsStart"); break;
                    case 0x1a: ev.onYield("tts", false, "TtsEnd"); break;
                    case 0x1b: ev.onYield("vui", true, "VuiStart"); break;
                    case 0x1c: ev.onYield("vui", false, "VuiEnd"); break;
                    case 0x14: ev.onYield("btvoice", true, "BtVoiceStart"); break;
                    case 0x16: ev.onYield("btvoice", false, "BtVoiceEnd"); break;
                    case 0x27: ev.onYield("ring", true, "RingStart"); break;
                    case 0x28: ev.onYield("ring", false, "RingEnd"); break;
                    case 0x44: ev.onYield("vr", true, "VoiceRecStart"); break;
                    case 0x45: ev.onYield("vr", false, "VoiceRecEnd"); break;
                    case 0x24: ev.onYield("announce", true, "AnnounceStart"); break;
                    case 0x25: ev.onYield("announce", false, "AnnounceEnd"); break;
                    case 0x1f: ev.onYield("reartalk", true, "RearTalkStart"); break;
                    case 0x20: ev.onYield("reartalk", false, "RearTalkEnd"); break;
                    case 0x32: ev.onYield("uvo", true, "UvoBlueLinkStart"); break;
                    case 0x33: ev.onYield("uvo", false, "UvoBlueLinkEnd"); break;
                    case 0x3a: ev.onYield("dipo_alt", true, "DiPOAltStart"); break;
                    case 0x3b: ev.onYield("dipo_alt", false, "DiPOAltEnd"); break;
                    case 0x3c: ev.onYield("dipo_call", true, "DiPOCallStart"); break;
                    case 0x3d: ev.onYield("dipo_call", false, "DiPOCallEnd"); break;
                    case 0x3e: ev.onYield("dipo_ring", true, "DiPORingStart"); break;
                    case 0x3f: ev.onYield("dipo_ring", false, "DiPORingEnd"); break;
                    case 0x40: ev.onYield("dipo_siri", true, "DiPOSiriStart"); break;
                    case 0x41: ev.onYield("dipo_siri", false, "DiPOSiriEnd"); break;
                    case 0x42: ev.onYield("btsiri", true, "BtSiriStart"); break;
                    case 0x43: ev.onYield("btsiri", false, "BtSiriEnd"); break;
                    case 0x21:
                    case 0x22: {
                        int t = data.readInt();
                        boolean start = code == 0x21;
                        String what = (start ? "AudioStart " : "AudioEnd ") + t;
                        if (t == ownType) ev.onOther(what + " (ours)");
                        else if (yieldsFor(t)) ev.onYield("a" + t, start, what);
                        else ev.onOther(what);
                        break;
                    }
                    case 0x5: raw = "Mute " + data.readInt(); ev.onOther(raw); break;
                    case 0xf: raw = "Knob " + data.readInt() + " " + data.readInt(); ev.onOther(raw); break;
                    case 0x3: raw = "Volume " + data.readInt() + " " + data.readInt(); ev.onOther(raw); break;
                    case 0x12: raw = "NaviVolume " + data.readInt(); ev.onOther(raw); break;
                    case 0x36: raw = "SoundPriority " + data.readInt(); ev.onOther(raw); break;
                    case 0xb: raw = "Beep " + data.readInt(); ev.onOther(raw); break;
                    default: ev.onOther(raw); break;
                }
            } catch (Throwable t) {
                ev.onOther(raw + " parse error " + t);
            }
            return true;
        }
    }

    /** stream types that speak to the driver: our sound steps aside while they play */
    static boolean yieldsFor(int t) {
        switch (t) {
            case NAVI: case TTS: case RING: case GUIDE: case AANAVI: case VOICEREC: case VUI: case EMERGENCY:
            case EMERGENCY_KIA: case UVOBLUE: case UVOBLUERING: case BTSIRI: case CARLIFE_NAVI: case MIRRORLINK_NAVI:
            case AA_VR_AV: case BTSMSREADING: case ANNOUNCE: case NAVI_SOUNDEFFECT: case TTS_EFFECT_SOUND:
            case SYSTEM_ALARM: case PREVIEWAUDIOTTS:
                return true;
            default:
                return false;
        }
    }
}
