package com.directormonitor;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;

public class MainActivity extends Activity {

    private static final String DEFAULT_URL = "srt://:9000";

    private LibVLC libVlc;
    private MediaPlayer player;
    private VLCVideoLayout videoLayout;
    private Overlay overlay;
    private EditText urlField;
    private LinearLayout topBar, bottomBar;
    private TextView status;
    private SharedPreferences prefs;
    private int dimLevel = 0;
    private final float[] dims = {1.0f, 0.5f, 0.2f};
    private boolean barsVisible = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("dm", MODE_PRIVATE);

        ArrayList<String> opts = new ArrayList<>();
        opts.add("--network-caching=150");
        opts.add("--clock-jitter=0");
        opts.add("--clock-synchro=0");
        libVlc = new LibVLC(this, opts);
        player = new MediaPlayer(libVlc);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        ViewGroup.LayoutParams match = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);

        videoLayout = new VLCVideoLayout(this);
        root.addView(videoLayout, match);

        overlay = new Overlay(this);
        overlay.setOnClickListener(v -> toggleBars());
        root.addView(overlay, match);

        // Top bar: URL + connect
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setBackgroundColor(Color.argb(170, 0, 0, 0));
        topBar.setPadding(dp(8), dp(6), dp(8), dp(6));
        urlField = new EditText(this);
        urlField.setSingleLine(true);
        urlField.setTextColor(Color.WHITE);
        urlField.setText(prefs.getString("url", DEFAULT_URL));
        topBar.addView(urlField, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button connect = btn("Connect", v -> connect());
        topBar.addView(connect);
        root.addView(topBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));

        // Bottom bar: overlay controls + status
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setBackgroundColor(Color.argb(170, 0, 0, 0));
        bottomBar.setPadding(dp(8), dp(6), dp(8), dp(6));

        final Button frameBtn = btn(Overlay.FRAME_NAMES[0], null);
        frameBtn.setOnClickListener(v -> frameBtn.setText(overlay.cycleFrame()));
        final Button gridBtn = btn(Overlay.GRID_NAMES[0], null);
        gridBtn.setOnClickListener(v -> gridBtn.setText(overlay.cycleGrid()));
        final Button safeBtn = btn("Safe: Off", null);
        safeBtn.setOnClickListener(v -> safeBtn.setText(overlay.toggleSafe()));
        final Button dimBtn = btn("Dim 100%", null);
        dimBtn.setOnClickListener(v -> {
            dimLevel = (dimLevel + 1) % dims.length;
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = dims[dimLevel];
            getWindow().setAttributes(lp);
            dimBtn.setText("Dim " + Math.round(dims[dimLevel] * 100) + "%");
        });
        bottomBar.addView(frameBtn);
        bottomBar.addView(gridBtn);
        bottomBar.addView(safeBtn);
        bottomBar.addView(dimBtn);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setPadding(dp(12), 0, 0, 0);
        status.setText("Idle");
        bottomBar.addView(status, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(bottomBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        setContentView(root);

        player.setEventListener(event -> runOnUiThread(() -> {
            switch (event.type) {
                case MediaPlayer.Event.Playing:
                    status.setText("Playing");
                    break;
                case MediaPlayer.Event.Buffering:
                    if (event.getBuffering() < 100f) {
                        status.setText("Buffering " + Math.round(event.getBuffering()) + "%");
                    }
                    break;
                case MediaPlayer.Event.EncounteredError:
                    status.setText("Error - check URL / sender");
                    break;
                case MediaPlayer.Event.EndReached:
                case MediaPlayer.Event.Stopped:
                    status.setText("Stopped");
                    break;
                case MediaPlayer.Event.Vout:
                    Media.VideoTrack vt = player.getCurrentVideoTrack();
                    if (vt != null && vt.width > 0 && vt.height > 0) {
                        overlay.setVideoAspect((float) vt.width / (float) vt.height);
                        status.setText("Playing " + vt.width + "x" + vt.height);
                    }
                    break;
                default:
                    break;
            }
        }));
    }

    private void connect() {
        String url = urlField.getText().toString().trim();
        if (url.isEmpty()) return;
        prefs.edit().putString("url", url).apply();

        player.stop();
        Media m = new Media(libVlc, Uri.parse(url));
        m.addOption(":network-caching=150");
        m.addOption(":live-caching=150");
        player.setMedia(m);
        m.release();
        player.play();
        status.setText("Waiting for stream...");

        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(urlField.getWindowToken(), 0);
        barsVisible = true;
        toggleBars(); // hide bars so the picture is clean
    }

    private void toggleBars() {
        barsVisible = !barsVisible;
        int v = barsVisible ? View.VISIBLE : View.GONE;
        topBar.setVisibility(v);
        bottomBar.setVisibility(v);
    }

    private Button btn(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        if (l != null) b.setOnClickListener(l);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void goImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goImmersive();
    }

    @Override
    protected void onStart() {
        super.onStart();
        player.attachViews(videoLayout, null, false, false);
        player.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT);
    }

    @Override
    protected void onStop() {
        super.onStop();
        player.stop();
        player.detachViews();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        player.release();
        libVlc.release();
    }

    // ------------------------------------------------------------------
    // Overlay: frame guides, grids, safe areas
    // ------------------------------------------------------------------
    static class Overlay extends View {
        static final String[] FRAME_NAMES = {
                "Frame: Off", "Frame 16:9", "Frame 2.39:1", "Frame 2:1",
                "Frame 4:3", "Frame 1:1", "Frame 9:16"};
        static final float[] FRAME_RATIOS = {
                0f, 16f / 9f, 2.39f, 2f, 4f / 3f, 1f, 9f / 16f};
        static final String[] GRID_NAMES = {
                "Grid: Off", "Grid: Thirds", "Grid: Center", "Grid: Both"};

        private int frame = 0;
        private int grid = 0;
        private boolean safe = false;
        private float videoAspect = 16f / 9f;

        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shade = new Paint();
        private final Paint safeLine = new Paint(Paint.ANTI_ALIAS_FLAG);

        Overlay(Context c) {
            super(c);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(2f);
            line.setColor(Color.argb(210, 255, 255, 255));
            shade.setColor(Color.argb(190, 0, 0, 0));
            safeLine.setStyle(Paint.Style.STROKE);
            safeLine.setStrokeWidth(2f);
            safeLine.setColor(Color.argb(200, 255, 200, 0));
        }

        String cycleFrame() {
            frame = (frame + 1) % FRAME_NAMES.length;
            invalidate();
            return FRAME_NAMES[frame];
        }

        String cycleGrid() {
            grid = (grid + 1) % GRID_NAMES.length;
            invalidate();
            return GRID_NAMES[grid];
        }

        String toggleSafe() {
            safe = !safe;
            invalidate();
            return safe ? "Safe: On" : "Safe: Off";
        }

        void setVideoAspect(float a) {
            videoAspect = a;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();

            // Where the video actually sits (letterboxed inside the screen)
            float vw = w;
            float vh = w / videoAspect;
            if (vh > h) {
                vh = h;
                vw = h * videoAspect;
            }
            RectF v = new RectF((w - vw) / 2f, (h - vh) / 2f, (w + vw) / 2f, (h + vh) / 2f);
            RectF f = v;

            // Frame guide: dim everything outside the chosen aspect
            float r = FRAME_RATIOS[frame];
            if (r > 0f) {
                float fw = v.width();
                float fh = fw / r;
                if (fh > v.height()) {
                    fh = v.height();
                    fw = fh * r;
                }
                f = new RectF(v.centerX() - fw / 2f, v.centerY() - fh / 2f,
                        v.centerX() + fw / 2f, v.centerY() + fh / 2f);
                c.drawRect(v.left, v.top, v.right, f.top, shade);
                c.drawRect(v.left, f.bottom, v.right, v.bottom, shade);
                c.drawRect(v.left, f.top, f.left, f.bottom, shade);
                c.drawRect(f.right, f.top, v.right, f.bottom, shade);
                c.drawRect(f, line);
            }

            // Grids
            if ((grid & 1) != 0) {
                float x1 = f.left + f.width() / 3f;
                float x2 = f.left + 2f * f.width() / 3f;
                float y1 = f.top + f.height() / 3f;
                float y2 = f.top + 2f * f.height() / 3f;
                c.drawLine(x1, f.top, x1, f.bottom, line);
                c.drawLine(x2, f.top, x2, f.bottom, line);
                c.drawLine(f.left, y1, f.right, y1, line);
                c.drawLine(f.left, y2, f.right, y2, line);
            }
            if ((grid & 2) != 0) {
                float cx = f.centerX();
                float cy = f.centerY();
                float s = Math.min(f.width(), f.height()) * 0.04f;
                c.drawLine(cx - s, cy, cx + s, cy, line);
                c.drawLine(cx, cy - s, cx, cy + s, line);
            }

            // Safe areas: action 93%, title 90%
            if (safe) {
                float[] pct = {0.93f, 0.90f};
                for (float p : pct) {
                    float dx = f.width() * (1f - p) / 2f;
                    float dy = f.height() * (1f - p) / 2f;
                    c.drawRect(f.left + dx, f.top + dy, f.right - dx, f.bottom - dy, safeLine);
                }
            }
        }
    }
}
