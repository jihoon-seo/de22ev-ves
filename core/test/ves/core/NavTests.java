package ves.core;

import java.util.Calendar;
import java.util.List;

import ves.gauge.LiveData;
import ves.gauge.NavText;

import static ves.core.TestMain.check;

/**
 * 0.2.13 speed cameras from the stock navigation's ClusterService log lines (vesd reads logcat -b system). The text
 * pieces below are byte-faithful to fw260902 ClusterService$NaviCluster.smali (StringBuilder const-strings).
 */
final class NavTests {
    private NavTests() {}

    /** a real line from the car (logcat-all.txt, 2026-11-20): the 22-field variant, no camera */
    static final String REAL = "11-20 17:46:33.460 D/ClusterService(  601): NaviCluster.updateTBTTrapInfo() fixedSpeed: 0 mobileSpeed: 0"
            + " overSpeedAlarm: 3 redlineSpeed: 0 nonSpeed: 0 warningZone: 0 curveWarning: 0 mergeWarning: 0 tollGateCurrency: 0"
            + " tollGate: 0 displayTG: 0 barGraphLevel: 0 speedLimit: 0 busSpeed: 0 speedLimitType: 0 speedLimitUnit: 1"
            + " speedInfoType: 0 warningZone2: 0 warningZone3: 0 warningZone4: 0 displayCountry: 0cameraSafetyAlert0";

    static long wall(int year, int mon, int day, int h, int mi, int s, int ms) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, mon - 1, day, h, mi, s);
        c.set(Calendar.MILLISECOND, ms);
        return c.getTimeInMillis();
    }

    static String two(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    /** logcat -v time prefix for a wall time: "MM-dd HH:mm:ss.SSS D/ClusterService(  601): " */
    static String head(long wallMs) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(wallMs);
        int ms = c.get(Calendar.MILLISECOND);
        return two(c.get(Calendar.MONTH) + 1) + "-" + two(c.get(Calendar.DAY_OF_MONTH)) + " " + two(c.get(Calendar.HOUR_OF_DAY)) + ":"
                + two(c.get(Calendar.MINUTE)) + ":" + two(c.get(Calendar.SECOND)) + "." + (ms < 10 ? "00" : ms < 100 ? "0" : "") + ms
                + " D/ClusterService(  601): ";
    }

    /** updateTBTTrapInfo(NaviTbtTrapInfo): 22 fields, the variant the car logs */
    static String trap(long at, int fixed, int mobile, int limit, int zone) {
        return head(at) + "NaviCluster.updateTBTTrapInfo() fixedSpeed: " + fixed + " mobileSpeed: " + mobile + " overSpeedAlarm: 3"
                + " redlineSpeed: 0 nonSpeed: 0 warningZone: " + zone + " curveWarning: 0 mergeWarning: 0 tollGateCurrency: 0"
                + " tollGate: 0 displayTG: 0 barGraphLevel: 0 speedLimit: " + limit + " busSpeed: 0 speedLimitType: 0"
                + " speedLimitUnit: 1 speedInfoType: 0 warningZone2: 0 warningZone3: 0 warningZone4: 0 displayCountry: 0cameraSafetyAlert0";
    }

    /** updateTBTTrapInfo(IIIIIIIIIIIIIIIII): the 17-field variant */
    static String trap17(long at, int fixed, int mobile, int limit, int zone) {
        return head(at) + "NaviCluster.updateTBTTrapInfo() fixedSpeed: " + fixed + " mobileSpeed: " + mobile + " overSpeedAlarm: 0"
                + " redlineSpeed: 0 nonSpeed: 0 warningZone: " + zone + " curveWarning: 0 mergeWarning: 0 tollGateCurrency: 0"
                + " tollGate: 0 displayTG: 0 barGraphLevel: 0 speedLimit: " + limit + " busSpeed: 0 speedLimitType: 0"
                + " speedLimitUnit: 1 speedInfoType: 0";
    }

    /** updateNaviWarnDist(III) */
    static String warn(long at, int over, int under, int scale) {
        return head(at) + "NaviCluster.updateNaviWarnDist() overDec: " + over + " underDec: " + under + " scale: " + scale;
    }

    /** updateNaviWarnDist(IIIIIIIIII): the first of its four lines */
    static String warn10(long at, int over, int under, int scale) {
        return head(at) + "NaviCluster.updateNaviWarnDist() warnDistOverDec: " + over + " warnDistUnderDec: " + under
                + " warnDistScale: " + scale;
    }

    /** updateSCC_HU_Navi_P (40 args, 28 logged) */
    static String scc(long at, int info, int dist, int lim, int avg) {
        return head(at) + "NaviCluster.updateSCC_HU_Navi_P() msgGroupNum1: 1 setDestStatus: 1 offRouteStatus: 0 nextTurnType1: 3"
                + " naviValidity: 1 nextTurnDist1: 420 nextTurnDist2: 0 curLinkCntLane: 3 curRoadClass: 1 curLinkClass: 2"
                + " nextTurnType2: 0 msgGroupNum2: 2 nextCamType: 0 nextCamDist: 0 nextCamSpdLimit: 0 rangeCamInfo: " + info
                + " rangeCamDist: " + dist + " rangeCamSpdLimit: " + lim + " rangeCamAvgMovingSpd: " + avg + " msgGroupNum3: 3"
                + " msgGroupNum4: 4 msgGroupNum5: 5 frontPosUpdate: 0 msgGroupNum6: 6 curTrafLength: 0 nextTrafDistance: 0"
                + " nextPosTraffic: 0 curPosTraffic: 0";
    }

    /** the value of " key=" in a LIVE suffix (null when missing) */
    static String f(String live, String key) {
        String k = " " + key + "=";
        int i = live.indexOf(k);
        if (i < 0) return null;
        int e = live.indexOf(' ', i + k.length());
        return live.substring(i + k.length(), e < 0 ? live.length() : e);
    }

    static void is(String live, String key, String want) {
        check(want.equals(f(live, key)), key + "=" + want + " wanted: " + live);
    }

    // a running clock: wall W (2026-11-23 14:00:00 local) and uptime U advance together
    static final long W = wall(2026, 11, 23, 14, 0, 0, 0), U = 1000000;

    /** feeds a line logged at offset ms, read by vesd 50 ms later */
    static void feed(NavCam n, String line, long offMs) {
        n.onLine(line, W + offMs + 50, U + offMs + 50);
    }

    static LiveData live(String suffix, double kph) {
        return LiveData.parse("ok\nrpm=800 gear=0 sh=0 sel=5 kph=" + kph + " kw=0 shift=- dm=- profile=ice_smooth state=drive red=6500"
                + " max=8000 on=1" + suffix + "\n");
    }

    static void add(List<TestMain.T> l) {
        // ---------------- NC1 fixed camera 60 km/h with a 350 m warning distance; the real car line; priorities
        l.add(new TestMain.T("NC1_trap_speed_camera") {
            void run() {
                NavCam n = new NavCam();
                String empty = n.liveFields(U);
                check(" nav=- cam=- lim=- dist=- zone=- sec=0 slim=- savg=- ssrc=- sdist=-".equals(empty), "nothing yet: '" + empty + "'");
                long real = wall(2026, 11, 20, 17, 46, 33, 460);
                n.onLine(REAL, real + 50, U);
                String s = n.liveFields(U + 10);
                is(s, "cam", "none");
                is(s, "zone", "0");
                is(s, "lim", "-");
                is(s, "nav", "0");
                feed(n, trap(W + 1000, 60, 0, 0, 0), 1000);
                feed(n, warn(W + 1100, 350, 0, 0), 1100);
                s = n.liveFields(U + 1200);
                check(s.startsWith(" nav="), "suffix starts with a space: " + s);
                is(s, "cam", "speed");
                is(s, "lim", "60");
                is(s, "dist", "350");
                is(s, "zone", "0");
                is(s, "sec", "0");
                feed(n, trap17(W + 2000, 0, 50, 0, 0), 2000);
                s = n.liveFields(U + 2100);
                is(s, "cam", "mobile");
                is(s, "lim", "50");
                feed(n, trap(W + 3000, 0, 0, 80, 0), 3000);
                is(n.liveFields(U + 3100), "cam", "limit");
                is(n.liveFields(U + 3100), "lim", "80");
                feed(n, trap(W + 4000, 0, 0, 0, 2), 4000);
                s = n.liveFields(U + 4100);
                is(s, "cam", "zone");
                is(s, "lim", "-");
                is(s, "zone", "2");
                feed(n, trap(W + 5000, 60, 50, 80, 2), 5000);
                is(n.liveFields(U + 5100), "cam", "speed");
                is(n.liveFields(U + 5100), "lim", "60");
                feed(n, trap(W + 6000, -1, 50, 0, 0), 6000);
                is(n.liveFields(U + 6100), "cam", "mobile");
            }
        });

        // ---------------- NC2 an all-zero trap line clears the camera (and its distance)
        l.add(new TestMain.T("NC2_zero_trap_clears") {
            void run() {
                NavCam n = new NavCam();
                feed(n, trap(W, 60, 0, 0, 0), 0);
                feed(n, warn(W + 100, 350, 0, 0), 100);
                is(n.liveFields(U + 200), "cam", "speed");
                feed(n, trap(W + 900, 0, 0, 0, 0), 900);
                String s = n.liveFields(U + 1000);
                is(s, "cam", "none");
                is(s, "lim", "-");
                is(s, "dist", "-");
                is(s, "zone", "0");
                feed(n, trap(W + 2000, 0, 50, 0, 0), 2000);
                is(n.liveFields(U + 2100), "dist", "-"); // the old camera's distance is gone
            }
        });

        // ---------------- NC3 the logcat backlog at vesd's start: lines older than HOLD_MS never become active
        l.add(new TestMain.T("NC3_backlog_never_active") {
            void run() {
                check(NavCam.HOLD_MS == 15000, "HOLD_MS " + NavCam.HOLD_MS);
                NavCam n = new NavCam();
                n.onLine(trap(W - 20000, 60, 0, 0, 0), W, U);
                n.onLine(warn(W - 19900, 350, 0, 0), W, U);
                n.onLine(scc(W - 19800, 1, 900, 80, 87), W, U);
                String s = n.liveFields(U + 100);
                is(s, "cam", "-");
                is(s, "dist", "-");
                is(s, "sec", "0");
                is(s, "nav", "19"); // the nav is alive, the lines are just old (newest: 19.8 s + 0.1 s)
                check("".equals(n.takeLog()), "backlog not logged");
                n.onLine(trap(W - 14000, 60, 0, 0, 0), W, U);
                is(n.liveFields(U + 100), "cam", "speed");
                // the year turns: a line stamped 12-31 read on 01-01 is from last year, 7 s old
                NavCam y = new NavCam();
                long ny = wall(2027, 1, 1, 0, 0, 5, 0);
                y.onLine(trap(wall(2026, 12, 31, 23, 59, 58, 0), 60, 0, 0, 0), ny, U);
                is(y.liveFields(U), "cam", "speed");
                is(y.liveFields(U), "nav", "7");
            }
        });

        // ---------------- NC4 section enforcement from the SCC line: the nav's average, else our own after 10 s
        l.add(new TestMain.T("NC4_scc_section") {
            void run() {
                NavCam n = new NavCam();
                feed(n, scc(W, 1, 1500, 80, 87), 0);
                String s = n.liveFields(U + 100);
                is(s, "sec", "1");
                is(s, "slim", "80");
                is(s, "savg", "87");
                is(s, "ssrc", "nav");
                is(s, "sdist", "1500");
                is(s, "cam", "section");
                is(s, "lim", "80");
                // no average from the nav: ours from the speed ticks, shown after 10 s
                NavCam o = new NavCam();
                feed(o, scc(W, 1, 3000, 80, 0), 0);
                for (int i = 1; i <= 50; i++) o.tickSpeed(60, U + 50 + i * 100);
                s = o.liveFields(U + 5100);
                is(s, "sec", "1");
                is(s, "savg", "-");
                is(s, "ssrc", "-");
                for (int i = 51; i <= 115; i++) o.tickSpeed(i % 2 == 0 ? 50 : 70, U + 50 + i * 100);
                s = o.liveFields(U + 11600);
                is(s, "ssrc", "own");
                String avg = f(s, "savg");
                check(avg != null && Integer.parseInt(avg) >= 59 && Integer.parseInt(avg) <= 61, "own average ~60: " + s);
                // the section ends
                feed(o, scc(W + 12000, 0, 0, 0, 0), 12000);
                s = o.liveFields(U + 12100);
                is(s, "sec", "0");
                is(s, "slim", "-");
                is(s, "savg", "-");
                is(s, "ssrc", "-");
                is(s, "sdist", "-");
                is(s, "cam", "-");
            }
        });

        // ---------------- NC5 garbage, foreign tags and broken numbers: ignored, never an exception
        l.add(new TestMain.T("NC5_malformed_ignored") {
            void run() {
                NavCam n = new NavCam();
                String h = head(W);
                String[] bad = {null, "", "garbage", "--------- beginning of system", h + "something else",
                        h.replace("ClusterService", "Other") + "NaviCluster.updateTBTTrapInfo() fixedSpeed: 60",
                        "99-99 25:61:61.000 D/ClusterService(  601): NaviCluster.updateTBTTrapInfo() fixedSpeed: 60",
                        h + "NaviCluster.updateTBTTrapInfo() fixedSpeed: 99999999999999999999 mobileSpeed: 0",
                        h + "NaviCluster.updateTBTTrapInfo() fixedSpeed: abc", h + "NaviCluster.updateTBTTrapInfo() mobileSpeed:",
                        h + "NaviCluster.updateTBTTrapInfo(", h + "NaviCluster.() fixedSpeed: 60", trap(W, 60, 0, 0, 0).substring(0, 40)};
                for (String b : bad) n.onLine(b, W + 50, U + 50);
                n.tickSpeed(Double.NaN, U + 60);
                n.tickSpeed(-5, U + 70);
                String s = n.liveFields(U + 100);
                is(s, "cam", "-");
                is(s, "nav", "-");
                check("".equals(n.takeLog()), "nothing logged");
                // a trap line without fixedSpeed is not a camera update
                feed(n, head(W) + "NaviCluster.updateTBTTrapInfo() mobileSpeed: 50", 0);
                is(n.liveFields(U + 100), "cam", "-");
            }
        });

        // ---------------- NC6 warning distance = over + under/10 in the line's unit (0 m, 1 km, 2 mile, 3 feet)
        l.add(new TestMain.T("NC6_warn_dist_scale") {
            void run() {
                check(NavCam.distMeters(350, 0, 0) == 350 && NavCam.distMeters(1, 2, 1) == 1200, "m and km");
                check(NavCam.distMeters(1, 0, 2) == 1609 && NavCam.distMeters(1000, 0, 3) == 305, "mile and feet");
                check(NavCam.distMeters(5, 0, 7) == -1 && NavCam.distMeters(-1, 0, 0) == -1, "unknown unit / negative");
                NavCam n = new NavCam();
                feed(n, trap(W, 60, 0, 0, 0), 0);
                feed(n, warn(W + 100, 1, 2, 1), 100);
                is(n.liveFields(U + 200), "dist", "1200");
                feed(n, warn10(W + 300, 0, 8, 1), 300);
                is(n.liveFields(U + 400), "dist", "800");
                feed(n, head(W + 400) + "NaviCluster.updateNaviWarnDist() unit1: 3 unit2: 0 unit3: 0", 400);
                is(n.liveFields(U + 500), "dist", "800"); // the other lines of the 10-arg call are not distances
            }
        });

        // ---------------- NC7 raw lines for vesd.log: changes only, <= 1 per second per line kind, a clear always
        l.add(new TestMain.T("NC7_log_rate_limit") {
            void run() {
                NavCam n = new NavCam();
                feed(n, trap(W, 60, 0, 0, 0), 0);
                feed(n, warn(W + 100, 350, 0, 0), 100);
                feed(n, trap(W + 200, 60, 0, 0, 0), 200); // same
                feed(n, trap(W + 400, 0, 50, 0, 0), 400); // changed, but within 1 s
                feed(n, trap(W + 600, 0, 0, 0, 0), 600); // the clear: always
                feed(n, trap(W + 700, 0, 0, 0, 0), 700); // same
                feed(n, head(W + 750) + "NaviCluster.updateRemainDis() remained_distance: 12 scale_unit: 1 under_decimal: 3 nv_display_TG: 0", 750);
                feed(n, head(W + 760) + "NaviCluster.updateV2MetaData() mapProvider: 1 countryCode: 82", 760);
                String log = n.takeLog();
                String[] rows = log.split("\n");
                check(rows.length == 3, "three rows: " + log.replace('\n', '|'));
                check(rows[0].startsWith("nav: updateTBTTrapInfo fixedSpeed: 60 mobileSpeed: 0 overSpeedAlarm: 3"), rows[0]);
                check(rows[0].endsWith("displayCountry: 0cameraSafetyAlert0"), "args verbatim: " + rows[0]);
                check(rows[1].equals("nav: updateNaviWarnDist overDec: 350 underDec: 0 scale: 0"), rows[1]);
                check(rows[2].startsWith("nav: updateTBTTrapInfo fixedSpeed: 0 mobileSpeed: 0"), rows[2]);
                check("".equals(n.takeLog()), "taken");
                feed(n, trap(W + 2000, 0, 50, 0, 0), 2000); // a change 1.4 s after the last row
                String two = n.takeLog();
                check(two.startsWith("nav: updateTBTTrapInfo fixedSpeed: 0 mobileSpeed: 50") && two.split("\n").length == 1, two);
                // the 10-arg warning distance logs four lines per call: each kind keeps its own rate
                feed(n, warn10(W + 3000, 0, 8, 1), 3000);
                feed(n, head(W + 3000) + "NaviCluster.updateNaviWarnDist() unit1: 3 unit2: 0 unit3: 0", 3000);
                check(n.takeLog().split("\n").length == 2, "both kinds of the same call");
                feed(n, scc(W + 4000, 1, 900, 80, 87), 4000);
                feed(n, head(W + 4100) + "NaviCluster.updateSLIFNaviInformation()::speedLimit:60 / linkClass:2 / frwInfo: 0 / spdUnit:1", 4100);
                String more = n.takeLog();
                check(more.indexOf("nav: updateSCC_HU_Navi_P msgGroupNum1: 1") >= 0
                        && more.indexOf("nav: updateSLIFNaviInformation ::speedLimit:60 / linkClass:2") >= 0, more);
            }
        });

        // ---------------- NC8 nav age; a camera nobody refreshes for STALE_MS goes unknown
        l.add(new TestMain.T("NC8_nav_age_and_stale") {
            void run() {
                NavCam n = new NavCam();
                feed(n, head(W) + "NaviCluster.updateRemainDis() remained_distance: 12 scale_unit: 1 under_decimal: 3 nv_display_TG: 0", 0);
                is(n.liveFields(U + 3400), "nav", "3");
                is(n.liveFields(U + 3400), "cam", "-");
                feed(n, trap(W + 4000, 60, 0, 0, 0), 4000);
                is(n.liveFields(U + 4100), "nav", "0");
                feed(n, warn(W + 50000, 200, 0, 0), 50000); // the distance keeps the camera fresh
                is(n.liveFields(U + 100000), "cam", "speed");
                check(NavCam.STALE_MS == 60000, "STALE_MS " + NavCam.STALE_MS);
                String s = n.liveFields(U + 50050 + NavCam.STALE_MS + 1);
                is(s, "cam", "-");
                is(s, "lim", "-");
                is(s, "zone", "-");
                is(s, "dist", "-");
                is(s, "nav", "60");
                // a line stamped after vesd's clock (skew): age 0, not negative
                NavCam k = new NavCam();
                k.onLine(trap(W + 2000, 60, 0, 0, 0), W, U);
                is(k.liveFields(U), "nav", "0");
                is(k.liveFields(U), "cam", "speed");
            }
        });

        // ---------------- NT1 the main screen's camera line (Korean, short)
        l.add(new TestMain.T("NT1_text_speed_camera") {
            void run() {
                String rest = " zone=0 sec=0 slim=- savg=- ssrc=- sdist=-";
                check("단속 60 · 350 m".equals(NavText.line(live(" nav=1 cam=speed lim=60 dist=350" + rest, 50))), "speed 350 m");
                check("단속 60 · 1.2 km".equals(NavText.line(live(" nav=1 cam=speed lim=60 dist=1200" + rest, 50))), "speed 1.2 km");
                check("단속 60".equals(NavText.line(live(" nav=1 cam=speed lim=60 dist=-" + rest, 50))), "no distance");
                check("이동식 단속 50 · 350 m".equals(NavText.line(live(" nav=1 cam=mobile lim=50 dist=350" + rest, 50))), "mobile");
                check("제한속도 80 · 350 m".equals(NavText.line(live(" nav=1 cam=limit lim=80 dist=350" + rest, 50))), "limit");
                check("주의 구간 · 350 m".equals(NavText.line(live(" nav=1 cam=zone lim=- dist=350 zone=2 sec=0 slim=- savg=- ssrc=- sdist=-", 50))),
                        "zone: " + NavText.line(live(" nav=1 cam=zone lim=- dist=350 zone=2 sec=0 slim=- savg=- ssrc=- sdist=-", 50)));
                check("".equals(NavText.line(live(" nav=1 cam=none lim=- dist=-" + rest, 50))), "none");
                check("".equals(NavText.line(live(" nav=- cam=- lim=- dist=- zone=- sec=0 slim=- savg=- ssrc=- sdist=-", 50))), "unknown");
                check("".equals(NavText.line(live("", 50))) && "".equals(NavText.line(null)), "old vesd / no data");
            }
        });

        // ---------------- NT2 section enforcement: limit and average
        l.add(new TestMain.T("NT2_text_section") {
            void run() {
                String s = NavText.line(live(" nav=1 cam=section lim=80 dist=- zone=- sec=1 slim=80 savg=87 ssrc=nav sdist=1500", 70));
                check("구간단속 80 · 평균 87".equals(s), "section: " + s);
                check("구간단속 80".equals(NavText.line(live(" nav=1 cam=section lim=80 dist=- zone=- sec=1 slim=80 savg=- ssrc=- sdist=-", 70))),
                        "no average yet");
            }
        });

        // ---------------- NT4 the full gauge's text under the limit sign: what the sign doesn't show (owner: "단속 60" repeats
        // the sign); without a limit (warning triangle) the whole line
        l.add(new TestMain.T("NT4_sign_detail") {
            void run() {
                String rest = " zone=0 sec=0 slim=- savg=- ssrc=- sdist=-";
                check("350 m".equals(NavText.signDetail(live(" nav=1 cam=speed lim=60 dist=350" + rest, 50))), "speed: distance only");
                check("단속 카메라".equals(NavText.signDetail(live(" nav=1 cam=speed lim=60 dist=-" + rest, 50))), "speed, no distance");
                check("이동식 · 1.2 km".equals(NavText.signDetail(live(" nav=1 cam=mobile lim=50 dist=1200" + rest, 50))), "mobile");
                check("350 m".equals(NavText.signDetail(live(" nav=1 cam=limit lim=80 dist=350" + rest, 50))), "limit");
                check("주의 구간 · 350 m".equals(NavText.signDetail(live(" nav=1 cam=zone lim=- dist=350 zone=2 sec=0 slim=- savg=- ssrc=- sdist=-", 50))), "zone: no sign number");
                LiveData sec = live(" nav=1 cam=section lim=80 dist=- zone=0 sec=1 slim=80 savg=87 ssrc=nav sdist=1500", 50);
                check("구간단속".equals(NavText.signDetail(sec)), "section: the average has its own line");
                check("평균 87".equals(NavText.sectionAvg(sec)), "section average line");
                check(NavText.avgOver(sec) && !NavText.nowOver(sec), "average 87 over 80, current 50 not");
                check("".equals(NavText.sectionAvg(live(" nav=1 cam=speed lim=60 dist=350" + rest, 50))), "no section: no average line");
                check("구간단속".equals(NavText.signDetail(live(" nav=1 cam=section lim=- dist=- zone=0 sec=1 slim=80 savg=- ssrc=- sdist=-", 50))), "section, no average");
                check("".equals(NavText.signDetail(live(" nav=1 cam=none lim=- dist=-" + rest, 50))) && "".equals(NavText.signDetail(null)), "none");
            }
        });

        // ---------------- NT3 over the limit: speed above the camera's limit (a section also by its average)
        l.add(new TestMain.T("NT3_over_flag") {
            void run() {
                String cam = " nav=1 cam=speed lim=60 dist=350 zone=0 sec=0 slim=- savg=- ssrc=- sdist=-";
                check(NavText.over(live(cam, 61)) && !NavText.over(live(cam, 60)) && !NavText.over(live(cam, 40)), "kph vs lim");
                check(!NavText.over(live(" nav=1 cam=zone lim=- dist=350 zone=2 sec=0 slim=- savg=- ssrc=- sdist=-", 90)), "no limit");
                check(!NavText.over(live(cam, Double.NaN)) && !NavText.over(null) && !NavText.over(live("", 99)), "unknown speed / data");
                String sec = " nav=1 cam=section lim=80 dist=- zone=- sec=1 slim=80 savg=%s ssrc=nav sdist=1500";
                check(NavText.over(live(sec.replace("%s", "87"), 70)), "section average over");
                check(!NavText.over(live(sec.replace("%s", "75"), 70)), "section average under, speed under");
                check(NavText.over(live(sec.replace("%s", "75"), 85)), "section speed over");
            }
        });
    }
}
