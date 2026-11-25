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
            String[] ress = {"1280x720 (HD)", "1600x900 (HD+)", "1920x1080 (FHD)", "2560x1600 (태블릿)"};
            String[] resVals = {"1280x720", "1600x900", "1920x1080", "2560x1600"};
            int rIdx = 0;
            for (int i = 0; i < resVals.length; i++) if (resVals[i].equals(LinuxEngine.resolution)) rIdx = i;
            final int frIdx = rIdx;
            gDesk.row(Icons.MONITOR, Ui.ACCENT, "기본 해상도", LinuxEngine.resolution, true, v ->
                    Widgets.pickSheet(main, v, "기본 해상도", ress, frIdx, idx -> {
                        LinuxEngine.resolution = resVals[idx];
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

            Widgets.Group gApp = new Widgets.Group(c, "화면 및 글래스");
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
            String[] styles = {"프리즘 글래스 (A)", "One UI 블러 (G)"};
            int stIdx = Ui.gStyle ? 1 : 0;
            gApp.row(Icons.INFO, 0xFF14B8A6, "글래스 스타일", styles[stIdx], true, v ->
                    Widgets.pickSheet(main, v, "글래스 스타일", styles, stIdx, idx -> {
                        p.edit().putString("ui_style", idx == 1 ? "oneui" : "prism").apply();
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
