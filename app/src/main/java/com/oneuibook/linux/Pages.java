package com.oneuibook.linux;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;

/** Main screens for Linux Desktop: Desktop manager, Terminal console, VNC profiles, Search. */
final class Pages {
    private Pages() {}

    static LinearLayout cardBox(Context c) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = Ui.round(Ui.card(), Ui.cardRadius());
        if (Ui.gStyle) bg.setStroke(Ui.dp(0.8f), Ui.gStroke());
        card.setBackground(bg);
        card.setClipToOutline(true);
        return card;
    }

    static LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(8));
        return lp;
    }

    // ---- 1. Desktop Page ----
    static final class DesktopPage extends Page {
        private int catIdx = 0;

        DesktopPage(MainActivity m) {
            super(m, m.getString(R.string.app_name), true);
            setGrouped(true);
            headerIcons = new int[]{Icons.PLAY, Icons.PLUS};
        }

        @Override void build() {
            Context c = getContext();
            boolean running = LinuxEngine.isRunning();
            boolean busy = LinuxEngine.installing || LinuxEngine.launching;
            LinuxEngine.Distro selected = LinuxEngine.findDistro(running ? LinuxEngine.activeDistroId : LinuxEngine.selectedDistroId);
            File proot = LinuxEngine.prootBinary(c);

            // Active / One-Click Launch hero card (Prism 26dp card)
            LinearLayout hero = cardBox(c);
            hero.setPadding(Ui.dp(20), Ui.dp(18), Ui.dp(20), Ui.dp(18));

            LinearLayout top = new LinearLayout(c);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(Ui.text(c, selected.name, 20, Ui.bold, Ui.text()),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            int badgeCol = running ? 0xFF19B36B : (busy ? 0xFFF59E0B : Ui.ACCENT);
            String badgeText = running ? "실행 중" : (busy ? "준비 중" : (LinuxEngine.isInstalled(c, selected.id) ? "설치됨" : "원클릭"));
            top.addView(Widgets.pillBadge(c, badgeText, Ui.alpha(badgeCol, Ui.dark ? 0.28f : 0.14f), badgeCol));
            hero.addView(top, Widgets.MW);

            String detail = LinuxEngine.resolutionLabel(c) + " · DPI " + LinuxEngine.dpiScale
                    + " · PRoot " + (proot != null ? "내장됨" : "대기");
            TextView sub = Ui.text(c, detail, 12.5f, Ui.regular, Ui.text2());
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = Ui.dp(4);
            hero.addView(sub, slp);

            if (busy) {
                TextView st = Ui.text(c, LinuxEngine.installStatus + " (" + LinuxEngine.installProgress + "%)", 13, Ui.semibold, Ui.ACCENT);
                LinearLayout.LayoutParams islp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                islp.topMargin = Ui.dp(12);
                hero.addView(st, islp);
                final float frac = Math.max(0.05f, LinuxEngine.installProgress / 100f);
                View pb = new View(c) {
                    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                    @Override protected void onDraw(Canvas cv) {
                        float r = getHeight() / 2f;
                        p.setColor(Ui.separator());
                        cv.drawRoundRect(0, 0, getWidth(), getHeight(), r, r, p);
                        p.setColor(Ui.ACCENT);
                        cv.drawRoundRect(0, 0, Math.max(getHeight(), getWidth() * frac), getHeight(), r, r, p);
                    }
                };
                LinearLayout.LayoutParams pblp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(6));
                pblp.topMargin = Ui.dp(6);
                hero.addView(pb, pblp);
            }

            LinearLayout btns = new LinearLayout(c);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.topMargin = Ui.dp(14);

            TextView primary = Widgets.button(c, running ? "화면 열기" : "원클릭 시작", true, v -> {
                if (running) {
                    main.openVnc(selected.name, "127.0.0.1", LinuxEngine.vncPort, "");
                } else {
                    Toast.makeText(c, selected.name + " 원클릭 실행 시작", Toast.LENGTH_SHORT).show();
                    LinuxEngine.oneClickLaunch(c, selected, () -> main.openVnc(selected.name, "127.0.0.1", LinuxEngine.vncPort, ""));
                }
            });
            LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            p1.rightMargin = Ui.dp(8);
            btns.addView(primary, p1);

            TextView secondary = Widgets.button(c, running ? "중지" : "배포판 변경", false, v -> {
                if (running) {
                    LinuxEngine.stopDesktop(c);
                } else {
                    String[] names = new String[LinuxEngine.DISTROS.length];
                    int curIdx = 0;
                    for (int i = 0; i < LinuxEngine.DISTROS.length; i++) {
                        names[i] = LinuxEngine.DISTROS[i].name + " (" + LinuxEngine.DISTROS[i].pkgMgr + ")";
                        if (LinuxEngine.DISTROS[i].id.equals(selected.id)) curIdx = i;
                    }
                    Widgets.pickSheet(main, v, "기본 배포판 선택", names, curIdx, idx -> {
                        LinuxEngine.selectedDistroId = LinuxEngine.DISTROS[idx].id;
                        LinuxEngine.saveProfiles(main);
                        rebuild();
                    });
                }
            });
            btns.addView(secondary, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            hero.addView(btns, blp);
            content.addView(hero, cardLp());

            // Distro catalog with category filter chips (10 distros)
            content.addView(Widgets.section(c, "배포판 (" + LinuxEngine.DISTROS.length + ")"));
            content.addView(new Widgets.Chips(c, LinuxEngine.DISTRO_CATS, catIdx, idx -> {
                catIdx = idx;
                rebuild();
            }), Widgets.MW);

            String wantCat = catIdx == 0 ? null : LinuxEngine.DISTRO_CATS[catIdx];
            for (LinuxEngine.Distro d : LinuxEngine.DISTROS) {
                if (wantCat != null && !wantCat.equals(d.category)) continue;
                content.addView(distroCard(c, d), cardLp());
            }
        }

        private View distroCard(Context c, LinuxEngine.Distro d) {
            boolean installed = LinuxEngine.isInstalled(c, d.id);
            boolean active = d.id.equals(LinuxEngine.activeDistroId) && LinuxEngine.isRunning();
            LinearLayout card = cardBox(c);
            card.setPadding(Ui.dp(18), Ui.dp(14), Ui.dp(18), Ui.dp(14));

            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout info = new LinearLayout(c);
            info.setOrientation(LinearLayout.VERTICAL);
            LinearLayout nameRow = new LinearLayout(c);
            nameRow.setGravity(Gravity.CENTER_VERTICAL);
            nameRow.addView(Ui.text(c, d.name, 16, Ui.bold, Ui.text()));
            nameRow.addView(Widgets.pillBadge(c, d.pkgMgr, Ui.alpha(d.color, Ui.dark ? 0.3f : 0.14f), d.color));
            info.addView(nameRow);
            String status = active ? "실행 중" : (installed ? "설치됨 · " + Ui.size(LinuxEngine.dirSizeBytes(LinuxEngine.rootfsDir(c, d.id))) : d.summary + " · " + d.sizeMb + " MB");
            TextView sum = Ui.text(c, status, 12.5f, Ui.regular, installed ? 0xFF19B36B : Ui.text2());
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = Ui.dp(3);
            info.addView(sum, slp);
            row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (installed) {
                TextView del = Ui.text(c, "삭제", 13, Ui.semibold, 0xFFE5484D);
                del.setSingleLine(false); del.setEllipsize(null);
                del.setPadding(Ui.dp(12), Ui.dp(8), Ui.dp(12), Ui.dp(8));
                del.setBackground(Ui.round(Ui.alpha(0xFFE5484D, Ui.dark ? 0.22f : 0.12f), 100));
                del.setOnClickListener(v -> LinuxEngine.removeDistro(c, d));
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                dlp.rightMargin = Ui.dp(6);
                row.addView(del, dlp);
            }

            TextView act = Widgets.button(c, active ? "열기" : (installed ? "실행" : "원클릭"), !installed || active, v -> {
                if (active) {
                    main.openVnc(d.name, "127.0.0.1", LinuxEngine.vncPort, "");
                } else {
                    Toast.makeText(c, d.name + " 원클릭 실행", Toast.LENGTH_SHORT).show();
                    LinuxEngine.oneClickLaunch(c, d, () -> main.openVnc(d.name, "127.0.0.1", LinuxEngine.vncPort, ""));
                }
            });
            act.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(8));
            row.addView(act);
            card.setOnClickListener(v -> {
                LinuxEngine.selectedDistroId = d.id;
                LinuxEngine.saveProfiles(main);
                rebuild();
            });
            card.addView(row, Widgets.MW);
            return card;
        }
    }

    // ---- 2. Terminal Page ----
    static final class TerminalPage extends Page {
        TerminalPage(MainActivity m) {
            super(m, m.getString(R.string.tab_terminal), true);
            setGrouped(true);
            headerIcons = new int[]{Icons.REFRESH};
        }

        @Override void build() {
            Context c = getContext();
            LinearLayout console = cardBox(c);
            console.setBackground(Ui.round(0xFF0E1320, Ui.cardRadius()));
            console.setPadding(Ui.dp(16), Ui.dp(16), Ui.dp(16), Ui.dp(16));
            TextView out = new TextView(c);
            out.setText(LinuxEngine.getLog());
            out.setTypeface(Typeface.MONOSPACE);
            out.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12.5f);
            out.setTextColor(0xFFD6E4FF);
            out.setMinLines(10);
            console.addView(out, Widgets.MW);
            content.addView(console, cardLp());

            LinearLayout inCard = cardBox(c);
            inCard.setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(14), Ui.dp(10));
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            EditText cmdEt = new EditText(c);
            cmdEt.setHint("명령어 입력");
            cmdEt.setSingleLine(true);
            cmdEt.setTextColor(Ui.text());
            cmdEt.setHintTextColor(Ui.text3());
            cmdEt.setBackground(null);
            row.addView(cmdEt, new LinearLayout.LayoutParams(0, Ui.dp(44), 1f));
            TextView runBtn = Widgets.button(c, "실행", true, v -> {
                String cmd = cmdEt.getText().toString().trim();
                if (!cmd.isEmpty()) { LinuxEngine.runShellCommand(c, cmd); cmdEt.setText(""); }
            });
            runBtn.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(8));
            row.addView(runBtn);
            inCard.addView(row, Widgets.MW);
            content.addView(inCard, cardLp());

            content.addView(Widgets.section(c, "빠른 명령"));
            String[] macros = {"uname -a", "id", "free -m", "df -h", "cat /etc/os-release"};
            HorizontalScrollView hs = new HorizontalScrollView(c);
            hs.setHorizontalScrollBarEnabled(false);
            hs.setClipToPadding(false);
            LinearLayout mr = new LinearLayout(c);
            mr.setPadding(Ui.dp(16), Ui.dp(4), Ui.dp(16), Ui.dp(8));
            for (String mac : macros) {
                TextView ch = Ui.text(c, mac, 13, Ui.semibold, Ui.text());
                ch.setPadding(Ui.dp(14), Ui.dp(8), Ui.dp(14), Ui.dp(8));
                ch.setBackground(Ui.round(Ui.chip(), 100));
                ch.setOnClickListener(v -> LinuxEngine.runShellCommand(c, mac));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = Ui.dp(8);
                mr.addView(ch, lp);
            }
            hs.addView(mr);
            content.addView(hs, Widgets.MW);
        }
    }

    // ---- 3. VNC Profiles Page ----
    static final class VncPage extends Page {
        VncPage(MainActivity m) {
            super(m, m.getString(R.string.tab_vnc), true);
            setGrouped(true);
            headerIcons = new int[]{Icons.PLUS};
        }

        @Override void build() {
            Context c = getContext();
            Widgets.Group g = new Widgets.Group(c, "저장된 연결");
            for (LinuxEngine.VncProfile p : LinuxEngine.profiles) {
                g.row(Icons.MONITOR, p.local ? 0xFF19B36B : Ui.ACCENT, p.name, p.host + ":" + p.port, true,
                        v -> main.openVnc(p.name, p.host, p.port, p.password));
            }
            content.addView(g, Widgets.MW);

            LinearLayout addWrap = new LinearLayout(c);
            addWrap.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(8));
            addWrap.addView(Widgets.button(c, "VNC 연결 추가", true, v -> openVncProfileSheet(main, null)), Widgets.MW);
            content.addView(addWrap, Widgets.MW);
        }
    }

    // ---- 4. Search Page ----
    static final class SearchPage extends Page {
        private String q = "";
        SearchPage(MainActivity m) { super(m, m.getString(R.string.search), true); setGrouped(true); headerIcons = new int[0]; }
        void setQuery(String s) { q = s == null ? "" : s.trim().toLowerCase(Locale.ROOT); rebuild(); }
        @Override void build() {
            Context c = getContext();
            Widgets.Group g = new Widgets.Group(c, "배포판 및 연결");
            for (LinuxEngine.Distro d : LinuxEngine.DISTROS) {
                if (q.isEmpty() || d.name.toLowerCase(Locale.ROOT).contains(q) || d.summary.toLowerCase(Locale.ROOT).contains(q)) {
                    g.row(Icons.CPU, d.color, d.name, d.pkgMgr, true, v -> main.onTab(0, false));
                }
            }
            for (LinuxEngine.VncProfile p : LinuxEngine.profiles) {
                if (q.isEmpty() || p.name.toLowerCase(Locale.ROOT).contains(q) || p.host.contains(q)) {
                    g.row(Icons.MONITOR, Ui.ACCENT, p.name, p.host + ":" + p.port, true,
                            v -> main.openVnc(p.name, p.host, p.port, p.password));
                }
            }
            content.addView(g, Widgets.MW);
        }
    }

    static void openVncProfileSheet(MainActivity m, LinuxEngine.VncProfile existing) {
        final LinuxEngine.VncProfile work = existing == null ? new LinuxEngine.VncProfile() : existing;
        LinearLayout form = new LinearLayout(m);
        form.setOrientation(LinearLayout.VERTICAL);
        EditText nameEt = Widgets.formField(m, "이름", "로컬 리눅스", existing == null ? "" : work.name, InputType.TYPE_CLASS_TEXT, form);
        EditText hostEt = Widgets.formField(m, "호스트", "127.0.0.1", work.host, InputType.TYPE_CLASS_TEXT, form);
        EditText portEt = Widgets.formField(m, "포트", "5901", String.valueOf(work.port), InputType.TYPE_CLASS_NUMBER, form);
        EditText passEt = Widgets.formField(m, "비밀번호", "선택", work.password, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, form);
        Widgets.showFormSheet(m, "VNC 연결 추가", form, m.getString(R.string.save), () -> {
            work.name = nameEt.getText().toString().trim();
            if (work.name.isEmpty()) work.name = hostEt.getText().toString().trim();
            work.host = hostEt.getText().toString().trim();
            if (work.host.isEmpty()) work.host = "127.0.0.1";
            try { work.port = Integer.parseInt(portEt.getText().toString().trim()); } catch (Exception ignored) { work.port = 5901; }
            work.password = passEt.getText().toString();
            if (existing == null) LinuxEngine.profiles.add(work);
            LinuxEngine.saveProfiles(m);
            m.reloadAll();
        });
    }
}
