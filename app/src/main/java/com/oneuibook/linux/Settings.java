package com.oneuibook.linux;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;

final class Settings {
    private Settings() {}

    static final class SettingsPage extends Page {
        SettingsPage(MainActivity m) {
            super(m, m.getString(R.string.tab_settings), true);
            setGrouped(true);
            headerIcons = new int[0];
        }

        @Override void build() {
            Context c = getContext();
            SharedPreferences p = MainActivity.prefs(c);

            Widgets.Group gDesk = new Widgets.Group(c, "데스크탑 환경");
            String autoLabel = "기기 해상도 맞춤 (" + LinuxEngine.resolveGeometry(c) + ")";
            String[] ress = {autoLabel, "1280x720 (HD)", "1600x900 (HD+)", "1920x1080 (FHD)", "2560x1600 (태블릿)"};
            String[] resVals = {"auto", "1280x720", "1600x900", "1920x1080", "2560x1600"};
            int rIdx = 0;
            for (int i = 0; i < resVals.length; i++) if (resVals[i].equals(LinuxEngine.resolution)) rIdx = i;
            final int frIdx = rIdx;
            gDesk.row(Icons.MONITOR, Ui.ACCENT, "화면 해상도", LinuxEngine.resolutionLabel(c), true, v ->
                    Widgets.pickSheet(main, v, "화면 해상도", ress, frIdx, idx -> {
                        LinuxEngine.resolution = resVals[idx];
                        LinuxEngine.saveProfiles(main);
                        rebuild();
                    }));

            String[] dpis = {"96 DPI (작게)", "120 DPI (기본)", "144 DPI (크게)", "168 DPI (고해상도)"};
            int[] dpiVals = {96, 120, 144, 168};
            int dIdx = 1;
            for (int i = 0; i < dpiVals.length; i++) if (dpiVals[i] == LinuxEngine.dpiScale) dIdx = i;
            final int fdIdx = dIdx;
            gDesk.row(Icons.APPEARANCE, 0xFF6366F1, "화면 배율 (DPI)", LinuxEngine.dpiScale + " DPI", true, v ->
                    Widgets.pickSheet(main, v, "화면 배율 (DPI)", dpis, fdIdx, idx -> {
                        LinuxEngine.dpiScale = dpiVals[idx];
                        LinuxEngine.saveProfiles(main);
                        rebuild();
                    }));

            LinearLayout tpRow = new LinearLayout(c);
            tpRow.setGravity(Gravity.CENTER_VERTICAL);
            tpRow.setPadding(Ui.dp(18), Ui.dp(14), Ui.dp(18), Ui.dp(14));
            tpRow.addView(Ui.text(c, "트랙패드 커서 모드 기본 사용", 15.5f, Ui.medium, Ui.text()),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            Widgets.Toggle tp = new Widgets.Toggle(c, LinuxEngine.touchpadMode);
            tp.change = on -> { LinuxEngine.touchpadMode = on; LinuxEngine.saveProfiles(main); };
            tpRow.addView(tp);
            gDesk.addCustom(tpRow);
            content.addView(gDesk, Widgets.MW);

            Widgets.Group gApp = new Widgets.Group(c, "화면 스타일 및 글래스");
            String[] themes = {"시스템 설정", "라이트", "다크"};
            String[] themeKeys = {"system", "light", "dark"};
            String curTheme = p.getString("theme", "system");
            int tIdx = curTheme.equals("light") ? 1 : (curTheme.equals("dark") ? 2 : 0);
            gApp.row(Icons.APPEARANCE, 0xFF3B8BF5, "테마", themes[tIdx], true, v ->
                    Widgets.pickSheet(main, v, "테마", themes, tIdx, idx -> {
                        p.edit().putString("theme", themeKeys[idx]).apply();
                        Ui.init(main, p);
                        main.refreshAppearance();
                    }));
            String[] styles = {"A 스타일 (글래스)", "G 스타일 (블러)"};
            int stIdx = Ui.gStyle ? 1 : 0;
            gApp.row(Icons.INFO, 0xFF14B8A6, "글래스 스타일", styles[stIdx], true, v ->
                    Widgets.pickSheet(main, v, "글래스 스타일", styles, stIdx, idx -> {
                        p.edit().putString("ui_style", idx == 1 ? "oneui" : "prism").apply();
                        Ui.init(main, p);
                        main.refreshAppearance();
                    }));
            String[] accentNames = new String[Ui.ACCENT_LABELS.length];
            int aIdx = 0;
            String curAcc = p.getString("accent_color", "blue");
            for (int i = 0; i < Ui.ACCENT_LABELS.length; i++) {
                accentNames[i] = c.getString(Ui.ACCENT_LABELS[i]);
                if (Ui.ACCENT_KEYS[i].equals(curAcc)) aIdx = i;
            }
            final int faIdx = aIdx;
            gApp.row(Icons.APPEARANCE, Ui.ACCENT, "강조 색상", accentNames[faIdx], true, v ->
                    Widgets.pickSheet(main, v, "강조 색상", accentNames, faIdx, idx -> {
                        p.edit().putString("accent_color", Ui.ACCENT_KEYS[idx]).apply();
                        Ui.init(main, p);
                        main.refreshAppearance();
                    }));
            content.addView(gApp, Widgets.MW);

            Widgets.Group gAbout = new Widgets.Group(c, "앱 정보");
            gAbout.row(Icons.INFO, Ui.text2(), c.getString(R.string.app_name), MainActivity.VERSION, false, null);
            gAbout.row(Icons.DOWNLOAD, Ui.ACCENT, "업데이트 확인", "GitHub", true, v -> Updater.checkInteractive(main));
            content.addView(gAbout, Widgets.MW);
        }
    }
}
