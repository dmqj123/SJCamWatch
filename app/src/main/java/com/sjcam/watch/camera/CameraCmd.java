package com.sjcam.watch.camera;

/**
 * SJCAM X camera CGI command numbers.
 *
 * These were recovered by reverse engineering SJCAMX_1006397.apk:
 * the DEX class org/jght/sjcam/x/camera/ly/LyCmd holds the constants as
 * static initialiser values, and org/jght/sjcam/x/camera/api/LyCmdApi
 * declares the three query keys "cmd" / "par" / "str".
 *
 * Request form (Retrofit @GET on the gateway root):
 *     http://192.168.1.254/?custom=1&cmd={CMD}[&par={PAR}][&str={STR}]
 *
 * Gateways found in org/jght/sjcam/x/camera/NovaCameraContent:
 *     LY_HOST            = http://192.168.1.254/
 *     lyRequestBaseUrl   = http://192.168.1.254/?custom=1
 *     cameraIp           = 192.168.1.254
 *     M_star_IP          = 192.168.8.1   (MStar based models)
 *
 * Response form:
 *     <Function><Cmd>CMD</Cmd><Status>0</Status><Value>VAL</Value></Function>
 *     <String>..</String>  -> firmware version for 3012
 *     <File><FPATH>..</FPATH></File> -> path returned after a capture
 *     3015 is special: it returns a file tree XML with one <FPATH> per file.
 *
 * Live view: LyCamera.initIP() builds rtsp://192.168.1.254:554/xxx.mp4 and
 * enables the stream with 2015. TCP :8192 is the LY push-event socket; not
 * needed for this simplified watch client.
 *
 * IMPORTANT (measured behaviour of the Novatek HTTP server):
 *  - it is single threaded; all requests MUST be serialised
 *  - 3016 heartbeat every 3-5s, otherwise the camera drops the Wi-Fi link
 *  - 1001 only works in photo mode (3001 par=0), 2001 only in video mode
 */
public final class CameraCmd {

    private CameraCmd() {
    }

    public static final int CUSTOM = 1;

    // capture / record
    public static final int CAPTURE = 1001;
    /** Photo still size; par = index into the model's list (cparams json). */
    public static final int SET_PHOTO_RESOLUTION = 1002;
    public static final int FREE_PICTURE = 1003;
    public static final int RECORD = 2001;
    /**
     * Camera-side resolution / bitrate tier. This is the ONLY setting that
     * affects how many bits the camera's Wi-Fi AP has to push. The value is an
     * index into the per-model list in the APK's assets/cparams/*.json files:
     *   SJ30    : 15 = 720P 30fps  (list has 16 entries)
     *   C400 / C110+ / C400Pro / C200Pro / SJ6Ultra : 8 = 720P 30fps (9 entries)
     *   C110 / C100P : 6 = 720P 30fps (7 entries)
     *   C300   : 9 = 720P 30fps (10 entries)
     *   SJ20   : 19 = 720P 30fps
     * Responds with the usual <Function><Status> XML; a negative Status means
     * the index does not exist on that model.
     */
    public static final int SET_VIDEO_RESOLUTION = 2002;
    public static final int FREE_RECORD_TIME = 2009;
    public static final int LIVE_VIEW = 2015;
    public static final int RECORD_TIME = 2016;

    // mode / status
    public static final int MODE_CHANGE = 3001;
    public static final int SET_DATA = 3005;
    public static final int SET_DATA_TIME = 3006;
    public static final int SHUTDOWN = 3007;
    public static final int GET_TV_MODE = 3009;
    public static final int FORMAT_SDCARD = 3010;
    public static final int RESET = 3011;
    public static final int GET_VERSION = 3012;
    public static final int PATH_OTA_TO_ROOT = 3013;
    public static final int GET_ALLSETTING_VALUE = 3014;
    public static final int GET_CAMERA_MEDIA_FILES = 3015;
    /** APK calls this GET_MODEL(3016); the community protocol uses it as the keep-alive ping. */
    public static final int HEARTBEAT = 3016;
    public static final int GET_DISK_SPACE = 3017;
    public static final int GET_BATTERY = 3019;
    /** Persist the menu values to flash; a few firmwares need this after a change. */
    public static final int SAVE_SETTINGS = 3021;
    public static final int GET_DEVICEID = 3027;
    /** ?custom=1&cmd=3031&str=all -> every settable cmd with its <MenuList>. */
    public static final int GET_MENU_OPTIONS = 3031;
    public static final int HDR_LOCK = 3061;

    // file operations
    public static final int THUMBNAIL = 4001;
    public static final int PREVIEW_IMAGE = 4002;
    public static final int DELETE_FILE = 4003;

    // vendor extensions
    public static final int CHANGE_CAMERA = 9041;
    public static final int ZOOM_IN_OUT = 9030;
    public static final int CREATE_OTA = 9080;

    // mode parameters for MODE_CHANGE
    public static final int MODE_PHOTO = 0;
    public static final int MODE_VIDEO = 1;
    public static final int MODE_PLAYBACK = 2;

    // record parameters for RECORD
    public static final int RECORD_STOP = 0;
    public static final int RECORD_START = 1;

    // live view parameters
    public static final int LIVE_OFF = 0;
    public static final int LIVE_ON = 1;

    /** Built-in status codes worth showing to the user. */
    public static String statusText(int status) {
        switch (status) {
            case 0: return "成功";
            case -1: return "无文件";
            case -5: return "写卡失败/卡满";
            case -7: return "录像存储已满";
            case -11: return "存储空间不足";
            case -13: return "执行失败";
            case -21: return "参数错误";
            case -22: return "状态不允许";
            case -256: return "命令不支持";
            default: return "状态码 " + status;
        }
    }
}
