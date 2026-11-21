package com.sjcam.watch.camera;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Photo / video resolution ("清晰度") menu.
 *
 * Two sources, in order of trust:
 *
 *  1. {@code ?custom=1&cmd=3031&str=all} - the camera's own menu table. Every
 *     <Item> carries a <Cmd> and a <MenuList>; each <Option><Index>N</Index>
 *     <Id>label</Id></Option>. N is exactly the `par` value to send back, so
 *     this is model independent and cannot drift.
 *  2. A built-in table recovered from the original APK's
 *     {@code assets/cparams/<model>$<firmware>.json}. That file is the phone
 *     app's own menu definition, so the label order equals the par index.
 *     Used only when the firmware has no 3031, matched on the model string
 *     reported by 3012.
 *
 * Setting is {@code cmd=<2002|1002>&par=<index>} followed by a best-effort
 * 3021 ("save menu to flash").
 */
public final class QualityOptions {

    public static final int CMD_VIDEO = CameraCmd.SET_VIDEO_RESOLUTION;   // 2002
    public static final int CMD_PHOTO = CameraCmd.SET_PHOTO_RESOLUTION;   // 1002

    public interface LoadCallback {
        void onResult(Options options, String error);
    }

    public interface ApplyCallback {
        void onDone(boolean ok, String message);
    }

    public static final class Option {
        public final int index;
        public final String label;
        /** Shortened for a 156dp row: "20M 5888X3312" -> "20M". */
        public final String shortLabel;

        Option(int index, String label) {
            this.index = index;
            this.label = label;
            this.shortLabel = shortLabel(label);
        }
    }

    public static final class Options {
        public final int cmd;
        public final List<Option> options;
        /** Currently selected par, or -1 when the firmware would not tell us. */
        public final int current;
        /** True when the list came from 3031 instead of the built-in table. */
        public final boolean fromCamera;

        Options(int cmd, List<Option> options, int current, boolean fromCamera) {
            this.cmd = cmd;
            this.options = options;
            this.current = current;
            this.fromCamera = fromCamera;
        }

        public String currentLabel() {
            for (Option o : options) {
                if (o.index == current) {
                    return o.label;
                }
            }
            return "";
        }
    }

    // Label cache so the capture screen can render without another round trip.
    private static volatile String videoLabel = "";
    private static volatile String photoLabel = "";
    /**
     * The camera's HTTP server is single threaded, so a hanging 3031 would also
     * delay the 3016 heartbeat. Bound how often we even try.
     */
    private static final int MAX_ATTEMPTS = 2;
    private static int videoAttempts = 0;
    private static int photoAttempts = 0;

    private QualityOptions() {
    }

    public static String cachedLabel(int cmd) {
        return cmd == CMD_PHOTO ? photoLabel : videoLabel;
    }

    public static void setCachedLabel(int cmd, String label) {
        if (label == null) {
            return;
        }
        if (cmd == CMD_PHOTO) {
            photoLabel = label;
        } else {
            videoLabel = label;
        }
    }

    // ------------------------------------------------------------------ load

    public static void load(final CameraSession session, final int cmd,
                            final LoadCallback cb) {
        int attempts = cmd == CMD_PHOTO ? photoAttempts : videoAttempts;
        if (attempts >= MAX_ATTEMPTS) {
            cb.onResult(null, "该固件不支持读取清晰度列表");
            return;
        }
        if (cmd == CMD_PHOTO) {
            photoAttempts++;
        } else {
            videoAttempts++;
        }
        session.exec(CameraCmd.GET_MENU_OPTIONS, null, "all", 6000,
                new CameraSession.Callback<String>() {
                    @Override
                    public void onResult(String xml, String error) {
                        List<Option> fromCamera = parseMenu(xml, cmd);
                        if (fromCamera.size() >= 2) {
                            readCurrent(session, cmd, fromCamera, true, cb);
                        } else {
                            final List<Option> builtin = builtin(session.getFirmware(), cmd);
                            if (builtin == null) {
                                cb.onResult(null, "该固件不支持读取清晰度列表");
                                return;
                            }
                            readCurrent(session, cmd, builtin, false, cb);
                        }
                    }
                });
    }

    private static void readCurrent(final CameraSession session, final int cmd,
                                    final List<Option> options, final boolean fromCamera,
                                    final LoadCallback cb) {
        session.exec(CameraCmd.GET_ALLSETTING_VALUE, null, null, 6000,
                new CameraSession.Callback<String>() {
                    @Override
                    public void onResult(String xml, String error) {
                        Integer value = itemValue(xml, cmd);
                        String text = itemString(xml, cmd);
                        int current = value == null ? -1 : value;
                        if (current < 0 && text != null) {
                            current = indexOfLabel(options, text);
                        }
                        if (text == null || text.isEmpty()) {
                            for (Option o : options) {
                                if (o.index == current) {
                                    text = o.label;
                                }
                            }
                        }
                        setCachedLabel(cmd, text);
                        cb.onResult(new Options(cmd, options, current, fromCamera), null);
                    }
                });
    }

    // ----------------------------------------------------------------- apply

    public static void apply(final CameraSession session, final int cmd, final int index,
                             final ApplyCallback cb) {
        session.exec(cmd, index, null, 9000, new CameraSession.Callback<String>() {
            @Override
            public void onResult(String xml, String error) {
                int st = CameraHttp.status(xml);
                if (error != null) {
                    cb.onDone(false, error);
                    return;
                }
                if (st != Integer.MIN_VALUE && st != 0) {
                    cb.onDone(false, CameraCmd.statusText(st));
                    return;
                }
                // Persist; a firmware that does not know 3021 is not an error.
                session.exec(CameraCmd.SAVE_SETTINGS, null, null, 6000,
                        new CameraSession.Callback<String>() {
                            @Override
                            public void onResult(String xml2, String e2) {
                                cb.onDone(true, "已切换");
                            }
                        });
            }
        });
    }

    // ----------------------------------------------------------------- parse

    private static final Pattern ITEM =
            Pattern.compile("<Item\\b[^>]*>(.*?)</Item>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern OPTION =
            Pattern.compile("<Option\\b[^>]*>(.*?)</Option>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern CMD_TAG =
            Pattern.compile("<Cmd>\\s*(\\d+)\\s*</Cmd>", Pattern.CASE_INSENSITIVE);
    private static final Pattern INDEX_TAG =
            Pattern.compile("<Index>\\s*(-?\\d+)\\s*</Index>", Pattern.CASE_INSENSITIVE);
    private static final Pattern VALUE_TAG =
            Pattern.compile("<Value>\\s*(-?\\d+)\\s*</Value>", Pattern.CASE_INSENSITIVE);
    private static final Pattern STRING_TAG =
            Pattern.compile("<String>\\s*([^<]*?)\\s*</String>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ID_TAG =
            Pattern.compile("<(?:Id|Name|Text)>\\s*([^<]*?)\\s*</(?:Id|Name|Text)>",
                    Pattern.CASE_INSENSITIVE);

    /** Pull the <MenuList> of one cmd out of a 3031 response. */
    static List<Option> parseMenu(String xml, int cmd) {
        List<Option> out = new ArrayList<>();
        if (xml == null) {
            return out;
        }
        String s = unescape(xml);
        Matcher m = ITEM.matcher(s);
        while (m.find()) {
            String body = m.group(1);
            Integer c = intOf(CMD_TAG, body);
            if (c == null || c != cmd) {
                continue;
            }
            Matcher om = OPTION.matcher(body);
            while (om.find()) {
                String ob = om.group(1);
                Integer idx = intOf(INDEX_TAG, ob);
                String id = strOf(ID_TAG, ob);
                if (id.isEmpty()) {
                    id = strOf(STRING_TAG, ob);
                }
                if (idx != null) {
                    out.add(new Option(idx, id.isEmpty() ? ("档位 " + idx) : id));
                }
            }
        }
        Collections.sort(out, new Comparator<Option>() {
            @Override
            public int compare(Option a, Option b) {
                return a.index - b.index;
            }
        });
        return out;
    }

    private static Integer itemValue(String xml, int cmd) {
        String body = itemBody(xml, cmd);
        return body == null ? null : intOf(VALUE_TAG, body);
    }

    private static String itemString(String xml, int cmd) {
        String body = itemBody(xml, cmd);
        if (body == null) {
            return null;
        }
        String v = strOf(STRING_TAG, body);
        return v.isEmpty() ? null : v;
    }

    private static String itemBody(String xml, int cmd) {
        if (xml == null) {
            return null;
        }
        String s = unescape(xml);
        Matcher m = ITEM.matcher(s);
        while (m.find()) {
            String body = m.group(1);
            Integer c = intOf(CMD_TAG, body);
            if (c != null && c == cmd) {
                return body;
            }
        }
        return null;
    }

    private static int indexOfLabel(List<Option> options, String text) {
        if (text == null) {
            return -1;
        }
        String t = text.trim();
        for (Option o : options) {
            if (o.label.equalsIgnoreCase(t) || o.shortLabel.equalsIgnoreCase(t)) {
                return o.index;
            }
        }
        return -1;
    }

    private static Integer intOf(Pattern p, String s) {
        Matcher m = p.matcher(s);
        if (!m.find()) {
            return null;
        }
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String strOf(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1).trim() : "";
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
                .replace("&quot;", "\"").replace("&apos;", "'");
    }

    // ---------------------------------------------------- built-in fallbacks

    /** C400 / C400Pro / S10 / C110+ share the same video ladder. */
    private static final String[] VIDEO_4K9 = {
            "4K 30FPS", "2K 60FPS", "2K 30FPS", "1080P 120FPS", "1080P 60FPS",
            "1080P 30FPS", "720P 120FPS", "720P 60FPS", "720P 30FPS"
    };

    private static final String[] PHOTO_C400 = {
            "24M", "16M", "12M", "8M", "5M", "2M"
    };

    private static final String[] PHOTO_C110P = {
            "20M 5888X3312", "16M 5376X3024", "14M 5008X2816", "12M 4640X2608",
            "10M 4320X2432", "8M 3840X2160", "5M 3200X1808", "3M 2560X1440",
            "2M 1920X1088"
    };

    private static final String[] VIDEO_SJ30 = {
            "8K 20fps", "6K 30fps", "5K 45fps", "5K 30fps", "4K 60fps", "4K 30fps",
            "2K 120fps", "2K 60fps", "2K 30fps", "1080P 240fps", "1080P 120fps",
            "1080P 60fps", "1080P 30fps", "720P 120fps", "720P 60fps", "720P 30fps"
    };

    private static final String[] PHOTO_SJ30 = {
            "48M", "32M", "20M", "16M", "14M", "12M", "10M", "8M", "5M", "3M", "2M"
    };

    /**
     * Model string comes from 3012's <String>; SJCAM firmwares return things
     * like "568-C400 V1.1.8A", "SJCAM C110+ ..." or "SF20200714" (unknown).
     */
    private static List<Option> builtin(String model, int cmd) {
        String m = model == null ? "" : model.toUpperCase();
        if (m.contains("SJ30")) {
            return build(cmd == CMD_PHOTO ? PHOTO_SJ30 : VIDEO_SJ30);
        }
        if (m.contains("C400") || m.contains("S10")) {
            return build(cmd == CMD_PHOTO ? PHOTO_C400 : VIDEO_4K9);
        }
        if (m.contains("C110") || m.contains("C100") || m.contains("SJ11")
                || m.contains("C300") || m.contains("C200") || m.contains("SJ20")
                || m.contains("SJ6")) {
            return build(cmd == CMD_PHOTO ? PHOTO_C110P : VIDEO_4K9);
        }
        // Model string unknown (some firmwares report only a build id such as
        // "SF20200714"). The 9-step ladder is shared by C400 / C400Pro / C110+ /
        // C200Pro / SJ6Ultra, so it is the safest guess; a wrong index is simply
        // rejected by the camera with a negative status and changes nothing.
        return build(cmd == CMD_PHOTO ? PHOTO_C400 : VIDEO_4K9);
    }

    private static List<Option> build(String[] labels) {
        List<Option> out = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            out.add(new Option(i, labels[i]));
        }
        return out;
    }

    /** "20M 5888X3312" is unreadable on a 156dp row; keep the "20M" part. */
    public static String shortLabel(String label) {
        if (label == null) {
            return "";
        }
        String s = label.trim();
        int sp = s.indexOf(' ');
        if (sp <= 0) {
            return s;
        }
        String head = s.substring(0, sp);
        String tail = s.substring(sp + 1);
        if (tail.matches(".*\\d+\\s*[xX]\\s*\\d+.*")) {
            return head;
        }
        return s;
    }
}
