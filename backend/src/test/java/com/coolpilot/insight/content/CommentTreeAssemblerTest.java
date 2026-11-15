package com.coolpilot.insight.content;

import com.coolpilot.insight.content.application.CommentTreeAssembler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 楼中楼组装（纯函数，不起容器）。
 *
 * <p>这些边界在真实数据里都出现过：父评论被删导致子评论找不到爸爸、
 * 平台返回 parent 指向自己、一级评论数量超过分页上限。
 */
class CommentTreeAssemblerTest {

    private static Map<String, Object> row(String cid, String parent) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cid", cid);
        m.put("parentCid", parent);
        m.put("content", "text-" + cid);
        return m;
    }

    @Test
    @DisplayName("一级评论成为根，子评论挂到父节点")
    void buildsTwoLevels() {
        List<Map<String, Object>> rows = new ArrayList<>(List.of(
                row("a", null), row("b", "a"), row("c", "a")));

        CommentTreeAssembler.Result r = CommentTreeAssembler.assemble(rows, 10);

        assertThat(r.totalRoots()).isEqualTo(1);
        assertThat(r.totalComments()).isEqualTo(3);
        Map<String, Object> root = r.roots().get(0);
        assertThat(root.get("cid")).isEqualTo("a");
        assertThat((List<?>) root.get("replies")).hasSize(2);
    }

    @Test
    @DisplayName("找不到父节点的评论降级为一级，不能丢")
    void orphanBecomesRoot() {
        List<Map<String, Object>> rows = new ArrayList<>(List.of(
                row("a", null), row("x", "deleted-parent")));

        CommentTreeAssembler.Result r = CommentTreeAssembler.assemble(rows, 10);

        assertThat(r.totalRoots()).isEqualTo(2);
        assertThat(r.roots()).extracting(m -> m.get("cid")).contains("a", "x");
    }

    @Test
    @DisplayName("parent 指向自己时不形成环")
    void selfReferenceIsNotAttached() {
        List<Map<String, Object>> rows = new ArrayList<>(List.of(row("s", "s")));

        CommentTreeAssembler.Result r = CommentTreeAssembler.assemble(rows, 10);

        // 既不是根（parent 非空也不算 0），也不能挂到自己身上——
        // 实现里 self==parent 时走 else 分支降级为根，保证不产生环
        assertThat(r.totalRoots()).isEqualTo(1);
        assertThat((List<?>) r.roots().get(0).get("replies")).isEmpty();
    }

    @Test
    @DisplayName("parentCid 为 \"0\" 也视为一级")
    void zeroParentIsRoot() {
        List<Map<String, Object>> rows = new ArrayList<>(List.of(row("a", "0")));
        assertThat(CommentTreeAssembler.assemble(rows, 10).totalRoots()).isEqualTo(1);
    }

    @Test
    @DisplayName("一级评论超过上限时截断，但总数仍报真实值")
    void rootLimitTruncates() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            rows.add(row("r" + i, null));
        }

        CommentTreeAssembler.Result r = CommentTreeAssembler.assemble(rows, 10);

        assertThat(r.roots()).hasSize(10);
        assertThat(r.totalRoots()).isEqualTo(30);
        assertThat(r.totalComments()).isEqualTo(30);
    }
}
