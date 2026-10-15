package com.textrec.lsp;

import java.util.concurrent.atomic.AtomicInteger;

/** 一次“设置文字”的位置（同一位置共享一个对象，次数累加在这里）。 */
final class SetStack {
    final Stacks.Stk stk;
    final String thread;
    final AtomicInteger count = new AtomicInteger();
    /** 反射解析出的可 hook 方法签名（异步填充） */
    volatile String[] sigs = new String[0];

    SetStack(Stacks.Stk stk, String thread) {
        this.stk = stk;
        this.thread = thread;
    }
}

/** 一条记录 = 一个“位置”：(设置栈, 绘制栈, View) 的组合。文字内容不进归并 key，所以时长每秒变也只有一条。 */
final class Rec {
    static final String TEXTVIEW = "TEXTVIEW";
    static final String CANVAS = "CANVAS";
    static final String COMPOSE = "COMPOSE";

    final int id;
    final String key;
    final String kind;
    final String viewClass;
    final String viewId;
    final String activity;
    final String[] parents;
    final SetStack set;          // 可能为 null：自绘 / Compose / 设置发生在记录之前
    final Stacks.Stk draw;
    final String drawThread;
    final long first;
    final String firstText;
    final AtomicInteger drawCount = new AtomicInteger();
    volatile long last;
    volatile String lastText;
    volatile String[] drawSigs = new String[0];

    Rec(int id, String key, String kind, String viewClass, String viewId, String activity, String[] parents,
        SetStack set, Stacks.Stk draw, String drawThread, String text) {
        this.id = id;
        this.key = key;
        this.kind = kind;
        this.viewClass = viewClass;
        this.viewId = viewId;
        this.activity = activity;
        this.parents = parents;
        this.set = set;
        this.draw = draw;
        this.drawThread = drawThread;
        this.first = System.currentTimeMillis();
        this.firstText = clip(text);
        this.last = first;
        this.lastText = firstText;
        this.drawCount.set(1);
    }

    void touch(String text) {
        drawCount.incrementAndGet();
        last = System.currentTimeMillis();
        lastText = clip(text);
    }

    static String clip(String s) {
        if (s == null) return "";
        return s.length() > 160 ? s.substring(0, 160) + "…(" + s.length() + ")" : s;
    }
}
