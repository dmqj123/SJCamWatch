package com.sjcam.watch;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.sjcam.watch.camera.CameraSession;
import com.sjcam.watch.camera.MediaFile;
import com.sjcam.watch.camera.MediaRepository;
import com.sjcam.watch.ui.BaseRoundActivity;
import com.sjcam.watch.ui.ScrollTarget;
import com.sjcam.watch.ui.ThumbnailLoader;
import com.sjcam.watch.ui.Ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Step 4 - camera history.
 *
 * Layout: the filter row (全部 / 照片 / 视频) sits at the TOP of the circle and
 * the thumbnail grid fills everything below it, so the screen is used from the
 * top arc of the bezel downwards instead of being squeezed into a centred box.
 *
 * The header is the only part that has to respect the arc: at y pixels from the
 * top the visible width is 2*sqrt(R^2-(R-y)^2), so its width is fitted at
 * runtime instead of hard-coded (fitWidthAt()).
 *
 * Tap a tile to preview it in place (photo: 4002 JPEG, video: HTTP stream);
 * long press or the preview's 下载 button saves it to the watch.
 */
public class HistoryActivity extends BaseRoundActivity {

    @Override
    protected float shellRatio() {
        return 0.76f;
    }

    @Override
    protected boolean shellScrollable() {
        return false;
    }

    private final CameraSession session = CameraSession.get();
    private final List<MediaFile> allFiles = new ArrayList<>();
    private final List<MediaFile> shown = new ArrayList<>();
    private Adapter adapter;
    private ThumbnailLoader thumbs;
    private TextView statusLine;
    private TextView filterAll;
    private TextView filterPhoto;
    private TextView filterVideo;
    private GridView grid;
    private int filter = 0; // 0 all, 1 photo, 2 video

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        thumbs = new ThumbnailLoader(session.http);

        // ---------------------------------------------------------- header
        int headerTop = Ui.dp(this, 18);
        int headerW = fitWidthAt(headerTop, Ui.dp(this, 46));

        LinearLayout header = Ui.column(this);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                headerW, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = headerTop;
        hp.gravity = Gravity.CENTER_HORIZONTAL;

        LinearLayout filters = Ui.row(this);
        filters.setGravity(Gravity.CENTER);
        filterAll = tab("全部");
        filterPhoto = tab(getString(R.string.photo));
        filterVideo = tab(getString(R.string.video));
        filters.addView(filterAll, Ui.rowItem(this, 0));
        filters.addView(filterPhoto, Ui.rowItem(this, 3));
        filters.addView(filterVideo, Ui.rowItem(this, 3));
        header.addView(filters, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 30)));

        statusLine = Ui.label(this, "读取相机文件…", 9,
                Color.parseColor("#FF93A0B4"), false);
        statusLine.setSingleLine(true);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = Ui.dp(this, 3);
        header.addView(statusLine, sp);

        content.addView(header, hp);

        filterAll.setOnClickListener(new FilterClick(0));
        filterPhoto.setOnClickListener(new FilterClick(1));
        filterVideo.setOnClickListener(new FilterClick(2));
        // Tap the status line to re-read the card, long press it to flip the
        // crown direction if a ColorOS build reports the axis the other way.
        statusLine.setClickable(true);
        statusLine.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toast("正在刷新列表…");
                load();
            }
        });
        enableDirectionToggle(statusLine);

        // ------------------------------------------------------------- grid
        grid = new GridView(this);
        grid.setNumColumns(2);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(Ui.dp(this, 4));
        grid.setVerticalSpacing(Ui.dp(this, 4));
        grid.setClipToPadding(false);
        grid.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 34));
        grid.setVerticalScrollBarEnabled(false);
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        adapter = new Adapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < shown.size()) {
                    PreviewActivity.open(HistoryActivity.this, shown.get(position));
                }
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view,
                                           int position, long id) {
                if (position < shown.size()) {
                    startDownload(shown.get(position));
                    return true;
                }
                return false;
            }
        });

        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        content.addView(grid, gp);

        // Crown / volume keys drive the grid.
        scrollTarget = new ScrollTarget() {
            @Override
            public void scrollByPx(int dy) {
                grid.scrollListBy(dy);
            }
        };

        updateFilterUi();
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The preview screen may have downloaded a file; refresh the tick badges.
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (thumbs != null) {
            thumbs.clear();
        }
        // Hand the camera back in video mode so the next capture does not start
        // from a playback-mode device.
        session.leavePlayback();
    }

    private TextView tab(String text) {
        TextView b = Ui.button(this, text, Color.parseColor("#FF202836"), 28);
        b.setMinHeight(0);
        b.setHeight(Ui.dp(this, 28));
        b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10);
        return b;
    }

    private final class FilterClick implements View.OnClickListener {
        private final int value;

        FilterClick(int v) {
            value = v;
        }

        @Override
        public void onClick(View v) {
            if (filter == value) {
                return;
            }
            filter = value;
            updateFilterUi();
            rebuildRows();
        }
    }

    private void updateFilterUi() {
        int on = Color.parseColor("#FF4DA3FF");
        int off = Color.parseColor("#FF202836");
        filterAll.setBackground(Ui.rounded(this, filter == 0 ? on : off, 14));
        filterPhoto.setBackground(Ui.rounded(this, filter == 1 ? on : off, 14));
        filterVideo.setBackground(Ui.rounded(this, filter == 2 ? on : off, 14));
    }

    private void load() {
        // 4001/4002 thumbnails and a reliable 3015 both require playback mode.
        statusLine.setText("切换到回放模式…");
        session.enterPlayback(new Runnable() {
            @Override
            public void run() {
                fetchList();
            }
        });
    }

    private void fetchList() {
        statusLine.setText("读取相机文件…");
        MediaRepository.load(session, new MediaRepository.Result() {
            @Override
            public void onDone(List<MediaFile> files, String error) {
                allFiles.clear();
                allFiles.addAll(files);
                rebuildRows();
                if (error != null && allFiles.isEmpty()) {
                    statusLine.setText(error);
                }
            }
        });
    }

    private void rebuildRows() {
        shown.clear();
        for (MediaFile f : allFiles) {
            if (filter == 1 && !f.isPhoto()) {
                continue;
            }
            if (filter == 2 && !f.isVideo()) {
                continue;
            }
            shown.add(f);
        }
        int photos = 0;
        int videos = 0;
        for (MediaFile f : shown) {
            if (f.isPhoto()) {
                photos++;
            } else {
                videos++;
            }
        }
        if (shown.isEmpty()) {
            statusLine.setText("暂无文件");
        } else {
            statusLine.setText(photos + " 照片 · " + videos + " 视频");
        }
        adapter.notifyDataSetChanged();
        grid.setSelection(0);
    }

    private void startDownload(final MediaFile f) {
        if (f.downloading || f.progress == 101) {
            toast(f.progress == 101 ? "已保存到手表下载目录" : "正在下载…");
            return;
        }
        f.downloading = true;
        f.progress = 0;
        statusLine.setText("下载 " + f.shortName() + " 0%");
        MediaRepository.download(this, session, f, new MediaRepository.DownloadCallback() {
            @Override
            public void onProgress(int percent) {
                f.progress = percent;
                statusLine.setText("下载 " + percent + "%");
                adapter.notifyDataSetChanged();
            }

            @Override
            public void onDone(boolean ok, String message) {
                f.downloading = false;
                f.progress = ok ? 101 : -2;
                statusLine.setText(ok ? ("已保存 · " + f.name) : "下载失败");
                adapter.notifyDataSetChanged();
                toast(message);
            }
        });
    }

    /** Two-column tile: thumbnail, duration/clock badge and a download tick. */
    private final class Adapter extends BaseAdapter {

        private final int cellH = Ui.dp(HistoryActivity.this, 62);

        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            FrameLayout tile;
            Holder h;
            if (convert instanceof FrameLayout && convert.getTag() instanceof Holder) {
                tile = (FrameLayout) convert;
                h = (Holder) convert.getTag();
            } else {
                tile = new FrameLayout(HistoryActivity.this);
                tile.setLayoutParams(new android.widget.AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, cellH));
                tile.setBackground(Ui.rounded(HistoryActivity.this,
                        Color.parseColor("#FF161B24"), 12));

                ImageView img = new ImageView(HistoryActivity.this);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                tile.addView(img, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));

                TextView badge = Ui.label(HistoryActivity.this, "", 8, Color.WHITE, true);
                badge.setSingleLine(true);
                badge.setMaxLines(1);
                badge.setBackground(Ui.rounded(HistoryActivity.this,
                        Color.parseColor("#B3000000"), 6));
                badge.setPadding(Ui.dp(HistoryActivity.this, 3), 0,
                        Ui.dp(HistoryActivity.this, 3), 0);
                FrameLayout.LayoutParams bglp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(HistoryActivity.this, 13));
                bglp.gravity = Gravity.START | Gravity.BOTTOM;
                bglp.setMargins(Ui.dp(HistoryActivity.this, 3), 0, 0,
                        Ui.dp(HistoryActivity.this, 3));
                tile.addView(badge, bglp);

                TextView kind = Ui.label(HistoryActivity.this, "", 9, Color.WHITE, true);
                kind.setSingleLine(true);
                FrameLayout.LayoutParams klp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                klp.gravity = Gravity.END | Gravity.TOP;
                klp.setMargins(0, Ui.dp(HistoryActivity.this, 2),
                        Ui.dp(HistoryActivity.this, 4), 0);
                tile.addView(kind, klp);

                h = new Holder();
                h.image = img;
                h.badge = badge;
                h.kind = kind;
                tile.setTag(h);
            }

            MediaFile f = shown.get(position);
            thumbs.load(f, h.image);
            h.badge.setText(shortTime(f.timeText));
            if (f.progress == 101) {
                h.kind.setText("✓");
                h.kind.setTextColor(Color.parseColor("#FF57D9A3"));
            } else if (f.downloading) {
                h.kind.setText(f.progress + "%");
                h.kind.setTextColor(Color.parseColor("#FF4DA3FF"));
            } else if (f.isVideo()) {
                h.kind.setText("▶");
                h.kind.setTextColor(Color.WHITE);
            } else {
                h.kind.setText("");
            }
            return tile;
        }
    }

    /** "MM-dd HH:mm" -> "HH:mm"; keeps the tile readable at 8sp. */
    private static String shortTime(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int space = text.indexOf(' ');
        return space > 0 ? text.substring(space + 1) : text;
    }

    private static final class Holder {
        ImageView image;
        TextView badge;
        TextView kind;
    }
}
