package com.coolpilot.insight.content.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 楼中楼组装（纯函数，无依赖）。
 *
 * <p>从 {@code CommentServiceImpl} 抽出：树构建是"输入扁平行、输出树"的纯计算，
 * 混在 Service 里既难单测（要起容器、造数据），也无法复用。
 *
 * <p>三条规则：
 * 1. `parentCid` 为空或 "0" 视为一级；
 * 2. 找不到父节点的评论**降级为一级**，不能丢——平台数据常见父评论已被删除；
 * 3. 自引用（parent == 自己）跳过，否则会形成环让序列化爆栈。
 */
public final class CommentTreeAssembler {

    private CommentTreeAssembler() {
    }

    @SuppressWarnings("unchecked")
    public static Result assemble(List<Map<String, Object>> rows, int rootLimit) {
        Map<String, Map<String, Object>> byCid = new LinkedHashMap<>();
        List<Map<String, Object>> roots = new ArrayList<>();

        for (Map<String, Object> r : rows) {
            Map<String, Object> node = new LinkedHashMap<>(r);
            node.put("replies", new ArrayList<Map<String, Object>>());
            String cid = str(r.get("cid"));
            if (!cid.isEmpty()) {
                byCid.put(cid, node);
            }
            if (isRoot(r)) {
                roots.add(node);
            }
        }

        for (Map<String, Object> r : rows) {
            if (isRoot(r)) {
                continue;
            }
            String cid = str(r.get("cid"));
            if (cid.isEmpty()) {
                continue;
            }
            Map<String, Object> parent = byCid.get(str(r.get("parentCid")));
            Map<String, Object> self = byCid.get(cid);
            if (parent != null && self != null && parent != self) {
                ((List<Map<String, Object>>) parent.get("replies")).add(self);
            } else if (self != null) {
                roots.add(self);
            }
        }

        int safeRoots = Math.min(Math.max(rootLimit, 1), 200);
        List<Map<String, Object>> page = roots.size() > safeRoots
                ? new ArrayList<>(roots.subList(0, safeRoots))
                : new ArrayList<>(roots);

        return new Result(page, rows.size(), roots.size());
    }

    private static boolean isRoot(Map<String, Object> r) {
        String parent = str(r.get("parentCid"));
        return parent.isEmpty() || "0".equals(parent);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** 组装结果：分页后的一级评论 + 总数统计。 */
    public record Result(List<Map<String, Object>> roots, int totalComments, int totalRoots) {

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("roots", roots);
            out.put("totalComments", totalComments);
            out.put("totalRoots", totalRoots);
            out.put("shownRoots", roots.size());
            return out;
        }
    }
}
