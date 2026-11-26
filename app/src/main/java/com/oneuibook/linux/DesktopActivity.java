package com.oneuibook.linux;

import android.app.Activity;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Built-in fullscreen RFB 3.8 VNC desktop viewer.
 * Native support for Galaxy Tab Book Cover Keyboard / Pro Keyboard & Trackpad:
 * hardware hover, L/R/M clicks, V/H scroll wheel, F1-F12/Ctrl/Alt/Meta/Hangul keys,
 * Ctrl+V clipboard sync, and two-finger touchscreen gestures.
 */
public final class DesktopActivity extends Activity {
    private VncClient client;
    private VncView canvasView;
    private TextView statusTxt;
    private boolean ctrlDown, altDown;
    private boolean hardwarePointerActive;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this, LinuxEngine.prefs(this));
        Window w = getWindow();
        w.setDecorFitsSystemWindows(false);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);

        String host = getIntent().getStringExtra("host");
        if (host == null || host.isEmpty()) host = "127.0.0.1";
        int port = getIntent().getIntExtra("port", LinuxEngine.vncPort);
        String pass = getIntent().getStringExtra("password");
        String title = getIntent().getStringExtra("title");
        if (title == null) title = host + ":" + port;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0A0C12);

        canvasView = new VncView(this);
        root.addView(canvasView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        statusTxt = Ui.text(this, title + " 연결 중...", 15, Ui.semibold, 0xFFFFFFFF);
        statusTxt.setGravity(Gravity.CENTER);
        statusTxt.setPadding(Ui.dp(20), Ui.dp(12), Ui.dp(20), Ui.dp(12));
        statusTxt.setBackground(Ui.round(0xCC181B24, 100));
        root.addView(statusTxt, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(10), Ui.dp(6), Ui.dp(10), Ui.dp(6));
        bar.setBackground(Ui.round(0xD8161922, 100));

        bar.addView(keyChip("Esc", 0xFF1B));
        bar.addView(keyChip("Tab", 0xFF09));
        bar.addView(modChip("Ctrl", true));
        bar.addView(modChip("Alt", false));
        bar.addView(actionChip("키보드", v -> {
            canvasView.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
        }));
        bar.addView(actionChip(LinuxEngine.touchpadMode ? "트랙패드" : "직접터치", v -> {
            LinuxEngine.touchpadMode = !LinuxEngine.touchpadMode;
            ((TextView) v).setText(LinuxEngine.touchpadMode ? "트랙패드" : "직접터치");
        }));
        bar.addView(actionChip("우클릭", v -> canvasView.rightClick()));
        bar.addView(actionChip("종료", v -> finish()));

        android.widget.HorizontalScrollView barScroll = new android.widget.HorizontalScrollView(this);
        barScroll.setHorizontalScrollBarEnabled(false);
        barScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        barScroll.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        blp.leftMargin = Ui.dp(8);
        blp.rightMargin = Ui.dp(8);
        blp.bottomMargin = Ui.dp(18);
        root.addView(barScroll, blp);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int bot = Math.max(insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom,
                    insets.getInsets(WindowInsets.Type.ime()).bottom);
            blp.bottomMargin = bot + Ui.dp(12);
            barScroll.setLayoutParams(blp);
            return insets;
        });
        setContentView(root);
        WindowInsetsController ctrl = w.getInsetsController();
        if (ctrl != null) {
            ctrl.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            ctrl.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }

        client = new VncClient();
        final String fTitle = title;
        client.connect(host, port, pass, new VncClient.Callback() {
            @Override public void onConnected(int cw, int ch, String name) {
                runOnUiThread(() -> {
                    canvasView.setDesktopSize(cw, ch);
                    canvasView.requestFocus();
                    statusTxt.setVisibility(View.GONE);
                    Toast.makeText(DesktopActivity.this, (name.isEmpty() ? fTitle : name) + " (" + cw + "x" + ch + ")", Toast.LENGTH_SHORT).show();
                });
            }
            @Override public void onFrameUpdated(Bitmap bmp) {
                runOnUiThread(() -> canvasView.setBitmap(bmp));
            }
            @Override public void onError(String msg) {
                runOnUiThread(() -> {
                    statusTxt.setVisibility(View.VISIBLE);
                    statusTxt.setText("연결 실패: " + msg);
                });
            }
            @Override public void onDisconnected() {
                runOnUiThread(() -> {
                    if (statusTxt.getVisibility() != View.VISIBLE) {
                        statusTxt.setVisibility(View.VISIBLE);
                        statusTxt.setText("연결 종료");
                    }
                });
            }
        });
    }

    private TextView keyChip(String lbl, int keysym) {
        return actionChip(lbl, v -> {
            if (client != null) { client.sendKey(keysym, true); client.sendKey(keysym, false); }
        });
    }

    private TextView modChip(String lbl, boolean isCtrl) {
        TextView tv = actionChip(lbl, null);
        tv.setOnClickListener(v -> {
            if (isCtrl) { ctrlDown = !ctrlDown; if (client != null) client.sendKey(0xFFE3, ctrlDown); }
            else { altDown = !altDown; if (client != null) client.sendKey(0xFFE9, altDown); }
            boolean on = isCtrl ? ctrlDown : altDown;
            tv.setBackground(Ui.round(on ? Ui.ACCENT : 0x26FFFFFF, 100));
        });
        return tv;
    }

    private TextView actionChip(String lbl, View.OnClickListener click) {
        TextView tv = Ui.text(this, lbl, 12.5f, Ui.semibold, 0xFFFFFFFF);
        tv.setSingleLine(false);
        tv.setEllipsize(null);
        tv.setPadding(Ui.dp(11), Ui.dp(7), Ui.dp(11), Ui.dp(7));
        tv.setBackground(Ui.round(0x26FFFFFF, 100));
        if (click != null) tv.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(3); lp.rightMargin = Ui.dp(3);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void pushClipboard() {
        if (client == null) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            if (cs != null && cs.length() > 0) client.sendCutText(cs.toString());
        }
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_V && (event.isCtrlPressed() || ctrlDown)) pushClipboard();
        int sym = mapKeySym(keyCode, event);
        if (sym != 0 && client != null) { client.sendKey(sym, true); return true; }
        return super.onKeyDown(keyCode, event);
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        int sym = mapKeySym(keyCode, event);
        if (sym != 0 && client != null) { client.sendKey(sym, false); return true; }
        return super.onKeyUp(keyCode, event);
    }

    /** Maps Android physical keyboard KeyEvent (including Book Cover / Pro Keyboard) to X11 KeySym. */
    static int mapKeySym(int code, KeyEvent ev) {
        if (code >= KeyEvent.KEYCODE_F1 && code <= KeyEvent.KEYCODE_F12) return 0xFFBE + (code - KeyEvent.KEYCODE_F1);
        switch (code) {
            case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER: return 0xFF0D;
            case KeyEvent.KEYCODE_DEL: return 0xFF08;
            case KeyEvent.KEYCODE_TAB: return 0xFF09;
            case KeyEvent.KEYCODE_ESCAPE: return 0xFF1B;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 0xFF51;
            case KeyEvent.KEYCODE_DPAD_UP: return 0xFF52;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 0xFF53;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 0xFF54;
            case KeyEvent.KEYCODE_PAGE_UP: return 0xFF55;
            case KeyEvent.KEYCODE_PAGE_DOWN: return 0xFF56;
            case KeyEvent.KEYCODE_MOVE_HOME: return 0xFF50;
            case KeyEvent.KEYCODE_MOVE_END: return 0xFF57;
            case KeyEvent.KEYCODE_INSERT: return 0xFF63;
            case KeyEvent.KEYCODE_FORWARD_DEL: return 0xFFFF;
            case KeyEvent.KEYCODE_CAPS_LOCK: return 0xFFE5;
            case KeyEvent.KEYCODE_SYSRQ: return 0xFF61;
            case KeyEvent.KEYCODE_CTRL_LEFT: return 0xFFE3;
            case KeyEvent.KEYCODE_CTRL_RIGHT: return 0xFFE4;
            case KeyEvent.KEYCODE_ALT_LEFT: return 0xFFE9;
            case KeyEvent.KEYCODE_ALT_RIGHT: return 0xFFEA;
            case KeyEvent.KEYCODE_SHIFT_LEFT: return 0xFFE1;
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return 0xFFE2;
            case KeyEvent.KEYCODE_META_LEFT: return 0xFFEB;
            case KeyEvent.KEYCODE_META_RIGHT: return 0xFFEC;
            case KeyEvent.KEYCODE_LANGUAGE_SWITCH: case KeyEvent.KEYCODE_KANA: return 0xFF31; // Hangul
            default:
                // When Ctrl/Alt/Meta is held, Android returns 0 or ASCII control char; resolve raw letter/digit keysym
                if (ev.isCtrlPressed() || ev.isAltPressed() || ev.isMetaPressed()) {
                    if (code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z)
                        return (ev.isShiftPressed() ? 'A' : 'a') + (code - KeyEvent.KEYCODE_A);
                    if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9)
                        return '0' + (code - KeyEvent.KEYCODE_0);
                }
                int uni = ev.getUnicodeChar();
                if (uni >= 0x20 && uni <= 0x7E) return uni;
                if (uni > 0x7E) return 0x01000000 | uni; // X11 Unicode KeySym
                return 0;
        }
    }

    @Override protected void onDestroy() {
        if (client != null) client.close();
        super.onDestroy();
    }

    final class VncView extends View {
        private Bitmap bmp;
        private int deskW = 1280, deskH = 720;
        private float cursorX = 640, cursorY = 360;
        private float lastX, lastY, downX, downY, twoY;
        private long downTime;
        private boolean twoFinger;
        private final RectF dst = new RectF();
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint curPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        VncView(Context c) {
            super(c);
            setFocusable(true);
            setFocusableInTouchMode(true);
        }

        @Override public boolean onCheckIsTextEditor() { return true; }

        @Override public android.view.inputmethod.InputConnection onCreateInputConnection(android.view.inputmethod.EditorInfo outAttrs) {
            outAttrs.inputType = android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
            outAttrs.imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI;
            return new android.view.inputmethod.BaseInputConnection(this, false) {
                @Override public boolean commitText(CharSequence text, int newCursorPosition) {
                    if (client != null && text != null) {
                        for (int i = 0; i < text.length(); i++) {
                            int cp = text.charAt(i);
                            int sym = (cp >= 0x20 && cp <= 0x7E) ? cp : (0x01000000 | cp);
                            client.sendKey(sym, true);
                            client.sendKey(sym, false);
                        }
                    }
                    return true;
                }
                @Override public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                    if (client != null && beforeLength > 0) {
                        for (int i = 0; i < beforeLength; i++) {
                            client.sendKey(0xFF08, true);
                            client.sendKey(0xFF08, false);
                        }
                    }
                    return true;
                }
            };
        }

        void setDesktopSize(int w, int h) { deskW = Math.max(1, w); deskH = Math.max(1, h); cursorX = deskW / 2f; cursorY = deskH / 2f; invalidate(); }
        void setBitmap(Bitmap b) { bmp = b; invalidate(); }

        void rightClick() {
            if (client == null) return;
            client.sendPointer((int) cursorX, (int) cursorY, 4);
            client.sendPointer((int) cursorX, (int) cursorY, 0);
        }

        private int vncButtonMask(int buttonState) {
            int m = 0;
            if ((buttonState & MotionEvent.BUTTON_PRIMARY) != 0) m |= 1;
            if ((buttonState & MotionEvent.BUTTON_TERTIARY) != 0) m |= 2;
            if ((buttonState & MotionEvent.BUTTON_SECONDARY) != 0) m |= 4;
            return m;
        }

        private void mapPointer(MotionEvent e) {
            if (dst.width() <= 1) return;
            cursorX = Ui.clamp((e.getX() - dst.left) / dst.width() * deskW, 0, deskW - 1);
            cursorY = Ui.clamp((e.getY() - dst.top) / dst.height() * deskH, 0, deskH - 1);
        }

        private boolean isHardwareMouse(MotionEvent e) {
            int src = e.getSource();
            return (src & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                    || (src & InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD
                    || e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE;
        }

        @Override protected void onDraw(Canvas c) {
            c.drawColor(0xFF0A0C12);
            if (bmp == null) return;
            float vw = getWidth(), vh = getHeight();
            float scale = Math.min(vw / deskW, vh / deskH);
            float dw = deskW * scale, dh = deskH * scale;
            dst.set((vw - dw) / 2f, (vh - dh) / 2f, (vw + dw) / 2f, (vh + dh) / 2f);
            c.drawBitmap(bmp, null, dst, paint);

            if (LinuxEngine.touchpadMode && !hardwarePointerActive) {
                float sx = dst.left + cursorX * scale;
                float sy = dst.top + cursorY * scale;
                curPaint.setColor(0xFFFFFFFF);
                curPaint.setStyle(Paint.Style.FILL);
                c.drawCircle(sx, sy, Ui.dpf(6), curPaint);
                curPaint.setColor(0xFF111111);
                curPaint.setStyle(Paint.Style.STROKE);
                curPaint.setStrokeWidth(Ui.dpf(1.8f));
                c.drawCircle(sx, sy, Ui.dpf(6), curPaint);
            }
        }

        @Override public boolean onGenericMotionEvent(MotionEvent e) {
            if (client == null || dst.width() <= 1) return super.onGenericMotionEvent(e);
            if (isHardwareMouse(e)) hardwarePointerActive = true;
            int act = e.getActionMasked();
            if (act == MotionEvent.ACTION_HOVER_MOVE || act == MotionEvent.ACTION_HOVER_ENTER) {
                mapPointer(e);
                client.sendPointer((int) cursorX, (int) cursorY, vncButtonMask(e.getButtonState()));
                invalidate();
                return true;
            }
            if (act == MotionEvent.ACTION_SCROLL) {
                mapPointer(e);
                float vy = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
                float hx = e.getAxisValue(MotionEvent.AXIS_HSCROLL);
                if (vy > 0) pulseWheel(8);       // VNC button 4 = scroll up
                else if (vy < 0) pulseWheel(16); // VNC button 5 = scroll down
                if (hx < 0) pulseWheel(32);      // VNC button 6 = scroll left
                else if (hx > 0) pulseWheel(64); // VNC button 7 = scroll right
                return true;
            }
            if (act == MotionEvent.ACTION_BUTTON_PRESS || act == MotionEvent.ACTION_BUTTON_RELEASE) {
                mapPointer(e);
                client.sendPointer((int) cursorX, (int) cursorY, vncButtonMask(e.getButtonState()));
                return true;
            }
            return super.onGenericMotionEvent(e);
        }

        private void pulseWheel(int mask) {
            client.sendPointer((int) cursorX, (int) cursorY, mask);
            client.sendPointer((int) cursorX, (int) cursorY, 0);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (dst.width() <= 1 || client == null) return true;
            // Hardware mouse/trackpad clicks & drags (Galaxy Tab Book Cover / Pro Keyboard)
            if (isHardwareMouse(e)) {
                hardwarePointerActive = true;
                mapPointer(e);
                int mask = vncButtonMask(e.getButtonState());
                if (e.getActionMasked() == MotionEvent.ACTION_UP) mask = 0;
                client.sendPointer((int) cursorX, (int) cursorY, mask);
                invalidate();
                return true;
            }
            hardwarePointerActive = false;
            float scale = dst.width() / deskW;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = lastX = e.getX(); downY = lastY = e.getY();
                    downTime = e.getEventTime();
                    twoFinger = false;
                    if (!LinuxEngine.touchpadMode) {
                        mapPointer(e);
                        client.sendPointer((int) cursorX, (int) cursorY, 1);
                    }
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (e.getPointerCount() == 2) {
                        twoFinger = true;
                        twoY = (e.getY(0) + e.getY(1)) / 2f;
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (twoFinger && e.getPointerCount() >= 2) {
                        float ny = (e.getY(0) + e.getY(1)) / 2f;
                        if (Math.abs(ny - twoY) > Ui.dpf(18)) {
                            pulseWheel(ny > twoY ? 8 : 16);
                            twoY = ny;
                        }
                        return true;
                    }
                    if (LinuxEngine.touchpadMode) {
                        cursorX = Ui.clamp(cursorX + (e.getX() - lastX) / scale * 1.4f, 0, deskW - 1);
                        cursorY = Ui.clamp(cursorY + (e.getY() - lastY) / scale * 1.4f, 0, deskH - 1);
                        client.sendPointer((int) cursorX, (int) cursorY, 0);
                    } else {
                        mapPointer(e);
                        client.sendPointer((int) cursorX, (int) cursorY, 1);
                    }
                    lastX = e.getX(); lastY = e.getY();
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    float dist = (float) Math.hypot(e.getX() - downX, e.getY() - downY);
                    if (twoFinger && dist < Ui.dpf(16) && e.getEventTime() - downTime < 350) {
                        rightClick();
                    } else if (LinuxEngine.touchpadMode && !twoFinger && dist < Ui.dpf(12) && e.getEventTime() - downTime < 350) {
                        client.sendPointer((int) cursorX, (int) cursorY, 1);
                        client.sendPointer((int) cursorX, (int) cursorY, 0);
                    } else if (!LinuxEngine.touchpadMode) {
                        client.sendPointer((int) cursorX, (int) cursorY, 0);
                    }
                    twoFinger = false;
                    return true;
            }
            return true;
        }
    }
}
