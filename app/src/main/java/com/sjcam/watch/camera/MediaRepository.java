package com.sjcam.watch.camera;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Fetches the camera file tree and downloads files into the system Downloads collection. */
public final class MediaRepository {

    public interface Result {
        void onDone(List<MediaFile> files, String error);
    }

    public interface DownloadCallback {
        void onProgress(int percent);

        void onDone(boolean ok, String message);
    }

    private MediaRepository() {
    }

    /**
     * 3015 normally returns the file tree directly. Some firmwares only answer
     * it in playback mode, so fall back to 3001 par=2 and ask again. The mode is
     * deliberately NOT restored here: the album screen owns it (enterPlayback /
     * leavePlayback), and switching back to video mode in the middle of a
     * session is exactly what made every thumbnail request fail.
     */
    public static void load(final CameraSession session, final Result cb) {
        session.exec(CameraCmd.GET_CAMERA_MEDIA_FILES, null, null, 12000,
                new CameraSession.Callback<String>() {
                    @Override
                    public void onResult(String xml, String error) {
                        final List<String> paths = CameraHttp.allTags(xml, "FPATH");
                        if (!paths.isEmpty()) {
                            deliver(session, paths, cb);
                            return;
                        }
                        session.exec(CameraCmd.MODE_CHANGE, CameraCmd.MODE_PLAYBACK, null, 12000,
                                new CameraSession.Callback<String>() {
                                    @Override
                                    public void onResult(String ignored, String error2) {
                                        session.setMode(CameraCmd.MODE_PLAYBACK);
                                        session.exec(CameraCmd.GET_CAMERA_MEDIA_FILES, null, null,
                                                12000, new CameraSession.Callback<String>() {
                                                    @Override
                                                    public void onResult(String xml2, String error3) {
                                                        deliver(session,
                                                                CameraHttp.allTags(xml2, "FPATH"), cb);
                                                    }
                                                });
                                    }
                                });
                    }
                });
    }

    private static void deliver(CameraSession session, List<String> paths, Result cb) {
        List<MediaFile> files = new ArrayList<>();
        for (int i = 0; i < paths.size(); i++) {
            files.add(MediaFile.create(session.http, paths.get(i), i));
        }
        // Requirement: newest first.
        Collections.sort(files, new Comparator<MediaFile>() {
            @Override
            public int compare(MediaFile a, MediaFile b) {
                return (a.time < b.time) ? 1 : (a.time == b.time ? a.name.compareTo(b.name) : -1);
            }
        });
        cb.onDone(files, paths.isEmpty() ? "相机里暂无文件" : null);
    }

    /**
     * Streams the file into MediaStore.Downloads. Android 11 (API 30) needs no
     * storage permission for this. The whole transfer holds CameraHttp's serial
     * lock, so it cannot disturb a heartbeat or a command.
     */
    public static void download(final Context ctx, final CameraSession session,
                                final MediaFile f, final DownloadCallback cb) {
        final Handler h = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                ContentResolver cr = ctx.getContentResolver();
                Uri uri = null;
                OutputStream out = null;
                try {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, f.name);
                    cv.put(MediaStore.Downloads.MIME_TYPE, f.mime());
                    cv.put(MediaStore.Downloads.IS_PENDING, 1);
                    uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) {
                        throw new IOException("无法创建下载记录");
                    }
                    out = cr.openOutputStream(uri);
                    if (out == null) {
                        throw new IOException("无法打开输出流");
                    }
                    final OutputStream fOut = out;
                    session.http.streamTo(f.url, 15000, fOut,
                            new CameraHttp.Progress() {
                                @Override
                                public void onProgress(final long done, final long total) {
                                    final int pct = total > 0
                                            ? (int) Math.min(100, done * 100 / total) : 0;
                                    h.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            cb.onProgress(pct);
                                        }
                                    });
                                }
                            }, -1);
                    try {
                        fOut.close();
                    } catch (IOException ignored) {
                    }
                    out = null;
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Downloads.IS_PENDING, 0);
                    cr.update(uri, done, null, null);
                    final String msg = "已保存到下载：" + f.name;
                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onDone(true, msg);
                        }
                    });
                } catch (final Exception e) {
                    if (out != null) {
                        try {
                            out.close();
                        } catch (IOException ignored) {
                        }
                    }
                    if (uri != null) {
                        try {
                            cr.delete(uri, null, null);
                        } catch (Exception ignored) {
                        }
                    }
                    final String msg = "下载失败：" + (e.getMessage() == null ? "网络错误" : e.getMessage());
                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onDone(false, msg);
                        }
                    });
                }
            }
        }, "sjcam-download").start();
    }
}
