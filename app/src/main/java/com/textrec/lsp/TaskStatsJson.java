package com.textrec.lsp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.TreeMap;

/** 把快照里的 records 数组转成 {@link TaskStats.RecInfo}（Android 的 org.json 部分，统计算法在纯 Java 的 TaskStats 里）。 */
final class TaskStatsJson {
    private TaskStatsJson() {}

    static void addAll(JSONArray records, List<TaskStats.RecInfo> out) {
        if (records == null) return;
        for (int i = 0; i < records.length(); i++) {
            JSONObject r = records.optJSONObject(i);
            if (r == null) continue;
            TreeMap<Integer, Integer> seg = new TreeMap<>();
            JSONObject sg = r.optJSONObject("segCounts");
            if (sg != null) {
                Iterator<String> it = sg.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    try {
                        seg.put(Integer.parseInt(k), sg.optInt(k));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            TreeMap<Integer, Integer> ps = new TreeMap<>();
            JSONObject pm = r.optJSONObject("passes");
            if (pm != null) {
                Iterator<String> it = pm.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    try {
                        ps.put(Integer.parseInt(k), pm.optInt(k));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            out.add(new TaskStats.RecInfo(seg, r.optString("baseline"), ps));
        }
    }

    /** 快照里每种记录丢了多少（上限满 / 基线配额）：{@code stats.droppedByKindCap}，转成一句话；没有返回空串。 */
    static String capNote(JSONObject stats) {
        if (stats == null) return "";
        JSONObject kd = stats.optJSONObject("droppedByKindCap");
        StringBuilder sb = new StringBuilder();
        long total = 0, quota = 0;
        if (kd != null) {
            Iterator<String> it = kd.keys();
            ArrayList<String> ks = new ArrayList<>();
            while (it.hasNext()) ks.add(it.next());
            java.util.Collections.sort(ks);
            for (String k : ks) {
                long v = kd.optLong(k);
                if (v <= 0) continue;
                total += v;
                if (k.endsWith(".基线配额")) quota += v;
                if (sb.length() < 120) sb.append(sb.length() > 0 ? "、" : "").append(k).append(' ').append(v);
            }
        }
        long cap = stats.optLong("droppedByRecordCap");
        if (total == 0 && cap == 0) return "";
        return "有记录因为上限被丢掉：" + sb + (cap > 0 ? "、总数上限 " + cap : "") + (quota > 0 ? "（其中基线配额 " + quota + "，基线里周期性噪声太多）" : "")
                + "。调大对应的 cap 上限 / noiseQuota，或先点「应用并重新记录」清空再做";
    }
}
