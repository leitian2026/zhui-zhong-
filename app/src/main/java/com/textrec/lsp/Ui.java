package com.textrec.lsp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** 三个页面共用的界面小工具：颜色走资源（自动适配深色模式），统一圆角、按钮、开关行、弹窗。 */
final class Ui {
    private Ui() {}

    interface IntCb {
        void on(int index);
    }

    // ------------------------------------------------------------ 基础

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static int col(Context c, int res) {
        return c.getColor(res);
    }

    static GradientDrawable rect(int fill, float radius, int stroke, int strokePx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        if (strokePx > 0) g.setStroke(strokePx, stroke);
        return g;
    }

    static Drawable ripple(Context c, Drawable base, float radius, boolean oval) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        if (oval) mask.setShape(GradientDrawable.OVAL);
        else mask.setCornerRadius(radius);
        int rc = (col(c, R.color.text) & 0x00FFFFFF) | 0x1A000000;
        return new RippleDrawable(ColorStateList.valueOf(rc), base, mask);
    }

    static TextView tv(Context c, CharSequence s, float sp, int colorRes, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(col(c, colorRes));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    static LinearLayout.LayoutParams lpM(Context c, int w, int h, float l, float t, float r, float b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        return p;
    }

    static LinearLayout vertical(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout horizontal(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static void enable(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.5f);
    }

    // ------------------------------------------------------------ 卡片 / 分区标题 / 分割线

    static LinearLayout card(Context c) {
        LinearLayout l = vertical(c);
        l.setBackground(rect(col(c, R.color.surface), dp(c, 16), col(c, R.color.outline), dp(c, 1)));
        l.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14));
        return l;
    }

    /** 分区标题（卡片外面的小标题）+ 一行说明。 */
    static LinearLayout section(Context c, String title, String desc) {
        LinearLayout l = vertical(c);
        l.setPadding(dp(c, 4), dp(c, 22), dp(c, 4), dp(c, 8));
        l.addView(tv(c, title, 13, R.color.text_sub, true));
        if (desc != null && !desc.isEmpty()) {
            TextView d = tv(c, desc, 12.5f, R.color.text_hint, false);
            d.setPadding(0, dp(c, 2), 0, 0);
            l.addView(d);
        }
        return l;
    }

    static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(col(c, R.color.outline));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(c, 1));
        v.setLayoutParams(p);
        return v;
    }

    // ------------------------------------------------------------ 按钮 / 芯片 / 标签

    static final int PRIMARY = 0, TONAL = 1, OUTLINE = 2, TEXT = 3;

    static TextView button(Context c, String text, int style, View.OnClickListener l) {
        TextView b = new TextView(c);
        b.setText(text);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(c, 48));
        b.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        int fill, txt, stroke = 0;
        switch (style) {
            case PRIMARY:
                fill = col(c, R.color.primary);
                txt = col(c, R.color.on_primary);
                break;
            case TONAL:
                fill = col(c, R.color.primary_container);
                txt = col(c, R.color.on_primary_container);
                break;
            case OUTLINE:
                fill = Color.TRANSPARENT;
                txt = col(c, R.color.text);
                stroke = col(c, R.color.outline);
                break;
            default:
                fill = Color.TRANSPARENT;
                txt = col(c, R.color.primary);
                break;
        }
        float r = dp(c, 14);
        b.setTextColor(txt);
        b.setBackground(ripple(c, rect(fill, r, stroke == 0 ? 0 : col(c, R.color.text_hint), stroke == 0 ? 0 : dp(c, 1)), r, false));
        b.setClickable(true);
        if (l != null) b.setOnClickListener(l);
        return b;
    }

    static TextView chip(Context c, String text, boolean selected, View.OnClickListener l) {
        TextView t = new TextView(c);
        t.setTextSize(13.5f);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(c, 36));
        t.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setMaxWidth(dp(c, 230));
        t.setClickable(true);
        if (l != null) t.setOnClickListener(l);
        setChip(t, selected, text);
        return t;
    }

    static void setChip(TextView t, boolean selected, String text) {
        Context c = t.getContext();
        t.setText(text);
        float r = dp(c, 18);
        if (selected) {
            t.setTextColor(col(c, R.color.on_primary_container));
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setBackground(ripple(c, rect(col(c, R.color.primary_container), r, 0, 0), r, false));
        } else {
            t.setTextColor(col(c, R.color.text_sub));
            t.setTypeface(Typeface.DEFAULT);
            t.setBackground(ripple(c, rect(col(c, R.color.surface), r, col(c, R.color.outline), dp(c, 1)), r, false));
        }
    }

    static int kindColor(String kind) {
        if ("TEXTVIEW".equals(kind)) return 0xFF4C6FFF;
        if ("COMPOSE".equals(kind)) return 0xFF9B5DE5;
        if ("CANVAS".equals(kind)) return 0xFF0FA3A3;
        if ("ICON".equals(kind)) return 0xFFF08A24;
        if ("TRACE".equals(kind)) return 0xFFE5487F;
        if ("STATE".equals(kind)) return 0xFF2FA96B;
        return 0xFF7C8596;
    }

    static TextView badge(Context c, String text, int color) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(11.5f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(color);
        t.setPadding(dp(c, 8), dp(c, 2), dp(c, 8), dp(c, 2));
        t.setBackground(rect((color & 0x00FFFFFF) | 0x26000000, dp(c, 6), 0, 0));
        return t;
    }

    /** 给已有的标签重新设置文字和颜色（列表复用行时用）。 */
    static void setBadge(TextView t, String text, int color) {
        Context c = t.getContext();
        t.setText(text);
        t.setTextColor(color);
        t.setBackground(rect((color & 0x00FFFFFF) | 0x26000000, dp(c, 6), 0, 0));
    }

    /** 提示框：warn=true 用橙色底。 */
    static TextView note(Context c, CharSequence s, boolean warn) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(12.5f);
        t.setLineSpacing(0, 1.15f);
        t.setTextColor(col(c, warn ? R.color.warn_text : R.color.text_sub));
        t.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        t.setBackground(rect(col(c, warn ? R.color.warn_bg : R.color.surface_alt), dp(c, 10), 0, 0));
        return t;
    }

    // ------------------------------------------------------------ 输入框 / 开关 / 分段

    static EditText field(Context c, String hint, boolean multi, boolean numeric) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setTextSize(14.5f);
        e.setTextColor(col(c, R.color.text));
        e.setHintTextColor(col(c, R.color.text_hint));
        e.setPadding(dp(c, 14), dp(c, 11), dp(c, 14), dp(c, 11));
        float r = dp(c, 12);
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, rect(col(c, R.color.surface_alt), r, col(c, R.color.primary), dp(c, 2)));
        sl.addState(new int[]{}, rect(col(c, R.color.surface_alt), r, 0, 0));
        e.setBackground(sl);
        if (numeric) {
            e.setSingleLine(true);
            e.setGravity(Gravity.CENTER);
            e.setInputType(InputType.TYPE_CLASS_NUMBER);
        } else if (multi) {
            e.setMinLines(3);
            e.setGravity(Gravity.TOP);
            e.setTypeface(Typeface.MONOSPACE);
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        } else {
            e.setSingleLine(true);
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        }
        return e;
    }

    static final class Sw {
        final LinearLayout row;
        final Switch sw;

        Sw(LinearLayout row, Switch sw) {
            this.row = row;
            this.sw = sw;
        }
    }

    /** 开关行：标题 + 一句人话说明 + 右侧开关，整行可点。 */
    static Sw switchRow(Context c, String title, String desc) {
        LinearLayout row = horizontal(c);
        row.setPadding(0, dp(c, 12), 0, dp(c, 12));
        LinearLayout left = vertical(c);
        left.addView(tv(c, title, 15.5f, R.color.text, false));
        if (desc != null && !desc.isEmpty()) {
            TextView d = tv(c, desc, 12.5f, R.color.text_sub, false);
            d.setPadding(0, dp(c, 2), 0, 0);
            d.setLineSpacing(0, 1.1f);
            left.addView(d);
        }
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        final Switch sw = new Switch(c);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.leftMargin = dp(c, 12);
        row.addView(sw, sp);
        row.setOnClickListener(v -> sw.toggle());
        return new Sw(row, sw);
    }

    static final class Seg {
        final LinearLayout root;
        final TextView[] items;
        int sel;
        IntCb cb;

        Seg(LinearLayout root, TextView[] items) {
            this.root = root;
            this.items = items;
        }

        void select(int i) {
            sel = i;
            Context c = root.getContext();
            for (int k = 0; k < items.length; k++) {
                TextView t = items[k];
                boolean on = k == i;
                t.setTextColor(col(c, on ? R.color.on_primary_container : R.color.text_sub));
                t.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                t.setBackground(on ? rect(col(c, R.color.primary_container), dp(c, 10), 0, 0) : null);
            }
        }
    }

    /** 分段选择：几个互斥选项排成一行。 */
    static Seg segmented(Context c, String[] labels, int sel, IntCb cb) {
        LinearLayout root = horizontal(c);
        root.setPadding(dp(c, 3), dp(c, 3), dp(c, 3), dp(c, 3));
        root.setBackground(rect(col(c, R.color.surface_alt), dp(c, 13), 0, 0));
        final TextView[] items = new TextView[labels.length];
        final Seg seg = new Seg(root, items);
        seg.cb = cb;
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView t = new TextView(c);
            t.setText(labels[i]);
            t.setTextSize(14);
            t.setGravity(Gravity.CENTER);
            t.setMinHeight(dp(c, 38));
            t.setClickable(true);
            t.setOnClickListener(v -> {
                if (seg.sel == idx) return;
                seg.select(idx);
                if (seg.cb != null) seg.cb.on(idx);
            });
            items[i] = t;
            root.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        seg.select(sel);
        return seg;
    }

    // ------------------------------------------------------------ 顶栏 / 图标按钮

    static ImageView iconBtn(Context c, int drawableRes, String desc, View.OnClickListener l) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(drawableRes);
        iv.setColorFilter(col(c, R.color.text));
        iv.setPadding(dp(c, 11), dp(c, 11), dp(c, 11), dp(c, 11));
        iv.setBackground(ripple(c, null, 0, true));
        iv.setContentDescription(desc);
        iv.setTooltipText(desc);
        iv.setClickable(true);
        if (l != null) iv.setOnClickListener(l);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 46), dp(c, 46)));
        return iv;
    }

    static final class Bar {
        final LinearLayout root;
        final TextView title;
        final TextView sub;
        final LinearLayout actions;

        Bar(LinearLayout root, TextView title, TextView sub, LinearLayout actions) {
            this.root = root;
            this.title = title;
            this.sub = sub;
            this.actions = actions;
        }
    }

    static Bar topBar(final Activity a, String title, String sub, boolean back) {
        LinearLayout root = horizontal(a);
        root.setBackgroundColor(col(a, R.color.bg));
        root.setMinimumHeight(dp(a, 60));
        root.setPadding(dp(a, back ? 6 : 18), dp(a, 4), dp(a, 8), dp(a, 4));
        if (back) root.addView(iconBtn(a, R.drawable.ic_back, "返回", v -> a.finish()));
        LinearLayout mid = vertical(a);
        mid.setPadding(dp(a, back ? 6 : 0), 0, dp(a, 6), 0);
        TextView t = tv(a, title, 20, R.color.text, true);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        mid.addView(t);
        TextView s = tv(a, sub == null ? "" : sub, 12.5f, R.color.text_sub, false);
        s.setSingleLine(true);
        s.setEllipsize(TextUtils.TruncateAt.END);
        s.setVisibility(sub == null || sub.isEmpty() ? View.GONE : View.VISIBLE);
        mid.addView(s);
        root.addView(mid, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout actions = horizontal(a);
        root.addView(actions, new LinearLayout.LayoutParams(-2, -2));
        return new Bar(root, t, s, actions);
    }

    /** 底部操作栏容器：白底 + 顶部细线。 */
    static LinearLayout bottomBar(Context c) {
        LinearLayout l = horizontal(c);
        l.setBackgroundColor(col(c, R.color.surface));
        l.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        return l;
    }

    // ------------------------------------------------------------ 弹窗

    /** 限制最大高度的 ScrollView（弹窗里放长列表用）。 */
    static final class MaxScroll extends ScrollView {
        private final int maxH;

        MaxScroll(Context c, int maxH) {
            super(c);
            this.maxH = maxH;
        }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST));
        }
    }

    /** 统一风格的弹窗：圆角卡片 + 标题 + 说明 + 内容 + 右下角按钮。 */
    static final class Dlg {
        private final Activity act;
        private final AlertDialog dlg;
        private final LinearLayout root;
        private final LinearLayout buttons;
        private boolean kb;

        Dlg(Activity a, String title, String sub) {
            act = a;
            root = vertical(a);
            root.setPadding(dp(a, 20), dp(a, 20), dp(a, 20), dp(a, 12));
            root.addView(tv(a, title, 18, R.color.text, true));
            if (sub != null && !sub.isEmpty()) {
                TextView s = tv(a, sub, 13, R.color.text_sub, false);
                s.setLineSpacing(0, 1.15f);
                s.setPadding(0, dp(a, 6), 0, 0);
                root.addView(s);
            }
            buttons = horizontal(a);
            buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            dlg = new AlertDialog.Builder(a).setView(root).create();
        }

        Dlg body(View v, boolean scroll) {
            View add = v;
            if (scroll) {
                MaxScroll ms = new MaxScroll(act, Math.round(act.getResources().getDisplayMetrics().heightPixels * 0.55f));
                ms.addView(v);
                add = ms;
            }
            root.addView(add, lpM(act, -1, -2, 0, 12, 0, 0));
            return this;
        }

        Dlg button(String label, boolean primary, final Runnable r) {
            TextView b = Ui.button(act, label, primary ? TONAL : TEXT, v -> {
                dlg.dismiss();
                if (r != null) r.run();
            });
            b.setMinHeight(dp(act, 44));
            buttons.addView(b, lpM(act, -2, -2, 4, 0, 0, 0));
            return this;
        }

        /** 弹窗里有输入框时调用：弹出即显示键盘。 */
        Dlg keyboard() {
            kb = true;
            return this;
        }

        void dismiss() {
            dlg.dismiss();
        }

        void show() {
            if (buttons.getChildCount() > 0) root.addView(buttons, lpM(act, -1, -2, 0, 12, 0, 0));
            dlg.show();
            if (dlg.getWindow() != null) {
                dlg.getWindow().setBackgroundDrawable(new InsetDrawable(
                        rect(col(act, R.color.surface), dp(act, 22), 0, 0), dp(act, 18), 0, dp(act, 18), 0));
                dlg.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (kb) dlg.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
            }
        }
    }

    /** 弹窗里的单选行：标题（选中高亮）+ 说明 + 右侧数量 / 对勾。 */
    static LinearLayout optionRow(Context c, String title, String desc, String trailing, boolean selected, View.OnClickListener l) {
        LinearLayout row = horizontal(c);
        row.setPadding(dp(c, 12), dp(c, 11), dp(c, 8), dp(c, 11));
        row.setBackground(ripple(c, selected ? rect(col(c, R.color.primary_container), dp(c, 12), 0, 0) : null, dp(c, 12), false));
        LinearLayout left = vertical(c);
        TextView t = tv(c, title, 15.5f, selected ? R.color.on_primary_container : R.color.text, selected);
        left.addView(t);
        if (desc != null && !desc.isEmpty()) {
            TextView d = tv(c, desc, 12.5f, R.color.text_sub, false);
            d.setPadding(0, dp(c, 2), 0, 0);
            left.addView(d);
        }
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        if (trailing != null && !trailing.isEmpty()) {
            TextView n = tv(c, trailing, 13, R.color.text_sub, false);
            n.setPadding(dp(c, 8), 0, dp(c, 8), 0);
            row.addView(n);
        }
        if (selected) row.addView(tv(c, "✓", 16, R.color.primary, true));
        row.setClickable(true);
        if (l != null) row.setOnClickListener(l);
        return row;
    }

    /** 导出面板里的操作行：左图标 + 标题 + 说明。 */
    static LinearLayout actionRow(Context c, int iconRes, String title, String desc, boolean recommend, View.OnClickListener l) {
        LinearLayout row = horizontal(c);
        row.setPadding(dp(c, 8), dp(c, 10), dp(c, 8), dp(c, 10));
        row.setBackground(ripple(c, null, dp(c, 12), false));
        ImageView ic = new ImageView(c);
        ic.setImageResource(iconRes);
        ic.setColorFilter(col(c, R.color.on_primary_container));
        ic.setPadding(dp(c, 10), dp(c, 10), dp(c, 10), dp(c, 10));
        ic.setBackground(rect(col(c, R.color.primary_container), dp(c, 12), 0, 0));
        row.addView(ic, new LinearLayout.LayoutParams(dp(c, 44), dp(c, 44)));
        LinearLayout mid = vertical(c);
        mid.setPadding(dp(c, 12), 0, dp(c, 4), 0);
        LinearLayout tl = horizontal(c);
        tl.addView(tv(c, title, 15.5f, R.color.text, true));
        if (recommend) tl.addView(badge(c, "推荐", col(c, R.color.primary)), lpM(c, -2, -2, 8, 0, 0, 0));
        mid.addView(tl);
        TextView d = tv(c, desc, 12.5f, R.color.text_sub, false);
        d.setPadding(0, dp(c, 2), 0, 0);
        mid.addView(d);
        row.addView(mid, new LinearLayout.LayoutParams(0, -2, 1f));
        row.setClickable(true);
        if (l != null) row.setOnClickListener(l);
        return row;
    }
}
