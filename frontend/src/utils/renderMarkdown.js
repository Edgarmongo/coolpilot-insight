/**
 * 极简 Markdown 渲染器（零依赖 / 默认转义 HTML，避免 XSS）
 *
 * 仅覆盖本项目后端实际会产出的语法子集：
 *   标题(#~######)、引用(>)、无序列表(- *)、有序列表(1.)、分隔线(---)、
 *   加粗(**x**)、行内代码(`x`)、段落。
 *
 * 之所以不引入 marked：报告与话题文本均来自本项目 ReportService / AI，
 * 语法固定，自己实现可完全控制转义，且不给前端增加依赖与供应链面。
 */

function escapeHtml(s) {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

/** 行内元素：先转义，再替换加粗与行内代码 */
function inline(text) {
  let s = escapeHtml(text);
  s = s.replace(/`([^`]+)`/g, '<code>$1</code>');
  s = s.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
  s = s.replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>');
  return s;
}

export function renderMarkdown(md) {
  if (!md) return '';
  const lines = String(md).replace(/\r\n/g, '\n').split('\n');
  const out = [];
  let listType = null;   // 'ul' | 'ol'

  const closeList = () => {
    if (listType) { out.push(`</${listType}>`); listType = null; }
  };

  for (const raw of lines) {
    const line = raw.replace(/\s+$/, '');

    if (!line.trim()) { closeList(); continue; }

    // 分隔线
    if (/^\s*(---+|\*\*\*+)\s*$/.test(line)) {
      closeList();
      out.push('<hr/>');
      continue;
    }

    // 标题
    const h = line.match(/^(#{1,6})\s+(.*)$/);
    if (h) {
      closeList();
      const level = Math.min(h[1].length + 1, 6); // h1 -> h2，避免与页面标题冲突
      out.push(`<h${level}>${inline(h[2])}</h${level}>`);
      continue;
    }

    // 引用
    const q = line.match(/^\s*>\s?(.*)$/);
    if (q) { closeList(); out.push(`<blockquote>${inline(q[1])}</blockquote>`); continue; }

    // 无序列表
    const ul = line.match(/^\s*[-*]\s+(.*)$/);
    if (ul) {
      if (listType !== 'ul') { closeList(); out.push('<ul>'); listType = 'ul'; }
      out.push(`<li>${inline(ul[1])}</li>`);
      continue;
    }

    // 有序列表
    const ol = line.match(/^\s*\d+\.\s+(.*)$/);
    if (ol) {
      if (listType !== 'ol') { closeList(); out.push('<ol>'); listType = 'ol'; }
      out.push(`<li>${inline(ol[1])}</li>`);
      continue;
    }

    closeList();
    out.push(`<p>${inline(line)}</p>`);
  }
  closeList();
  return out.join('\n');
}
