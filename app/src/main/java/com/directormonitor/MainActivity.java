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

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;

public class MainActivity extends Activity {

    private static final String DEFAULT_PORT = "9000";

    private LibVLC libVlc;
    private MediaPlayer player;
    private VLCVideoLayout videoLayout;
    private Overlay overlay;
    private EditText urlField;
    private TextView ipLabel;
    private LinearLayout topBar, bottomBar;
    private TextView status;
    private SharedPreferences prefs;
    private int dimLevel = 0;
    private final float[] dims = {1.0f, 0.5f, 0.2f};
    private boolean barsVisible = true;
    private int form = 0;
    private long ignoreStopUntil = 0;

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

        // Top bar: tablet IP + port + Listen
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(Color.argb(170, 0, 0, 0));
        topBar.setPadding(dp(8), dp(6), dp(8), dp(6));

        ipLabel = new TextView(this);
        ipLabel.setTextColor(Color.WHITE);
        ipLabel.setTextSize(16f);
        ipLabel.setPadding(0, 0, dp(12), 0);
        topBar.addView(ipLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        urlField = new EditText(this);
        urlField.setSingleLine(true);
        urlField.setHint("Port");
        urlField.setHintTextColor(Color.GRAY);
        urlField.setTextColor(Color.WHITE);
        urlField.setText(prefs.getString("in", DEFAULT_PORT));
        topBar.addView(urlField, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button listen = btn("Listen", v -> connect());
        topBar.addView(listen);
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
        final Button formBtn = btn("Form 1", null);
        formBtn.setOnClickListener(v -> {
            form = (form + 1) % 3;
            formBtn.setText("Form " + (form + 1));
        });
        bottomBar.addView(frameBtn);
        bottomBar.addView(gridBtn);
        bottomBar.addView(safeBtn);
        bottomBar.addView(dimBtn);
        bottomBar.addView(formBtn);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setPadding(dp(12), 0, 0, 0);
        status.setText("Idle - tap Listen");
        bottomBar.addView(status, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(bottomBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        setContentView(root);
        refreshIp();

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
                    status.setText("Error - try another Form button, then Listen");
                    break;
                case MediaPlayer.Event.EndReached:
                case MediaPlayer.Event.Stopped:
                    if (System.currentTimeMillis() < ignoreStopUntil) break;
                    status.setText("Stopped - tap Listen to retry");
                    break;
                case MediaPlayer.Event.Vout:
                    Media.VideoTrack vt = player.getCurrentVideoTrack();
                    if (vt != null && vt.width > 0 && vt.height > 0) {
                        overlay.setVideoAspect((float) vt.width / (float) vt.height);
                        status.setText("Playing " + vt.width + "x" + vt.height);
                    }
                    if (barsVisible) toggleBars();
                    break;
                default:
                    break;
            }
        }));
    }

    private String getLocalIp() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()
                            && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return "not on Wi-Fi";
    }

    private void refreshIp() {
        ipLabel.setText("This tablet: " + getLocalIp());
    }

    private String buildUrl(String in) {
        if (in.matches("\\d{2,5}")) {
            switch (form) {
                case 0:
                    return "srt://:" + in;
                case 1:
                    return "srt://@:" + in;
                default:
                    return "srt://0.0.0.0:" + in + "?mode=listener";
            }
        }
        return in; // a full URL typed by hand
    }

    private void connect() {
        String in = urlField.getText().toString().trim();
        if (in.isEmpty()) {
            in = DEFAULT_PORT;
            urlField.setText(in);
        }
        prefs.edit().putString("in", in).apply();
        refreshIp();
        String url = buildUrl(in);

        ignoreStopUntil = System.currentTimeMillis() + 1500;
        player.stop();
        Media m = new Media(libVlc, Uri.parse(url));
        m.addOption(":network-caching=150");
        m.addOption(":live-caching=150");
        player.setMedia(m);
        m.release();
        player.play();

        if (in.matches("\\d{2,5}")) {
            status.setText("Waiting - send to srt://" + getLocalIp() + ":" + in);
        } else {
            status.setText("Waiting for stream...");
        }

        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(urlField.getWindowToken(), 0);
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
        refreshIp();
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

            float vw = w;
            float vh = w / videoAspect;
            if (vh > h) {
                vh = h;
                vw = h * videoAspect;
            }
            RectF v = new RectF((w - vw) / 2f, (h - vh) / 2f, (w + vw) / 2f, (h + vh) / 2f);
            RectF f = v;

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
