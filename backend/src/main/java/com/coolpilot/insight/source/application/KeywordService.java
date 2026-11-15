package com.coolpilot.insight.source.application;

import com.coolpilot.insight.common.exception.BizException;
import com.coolpilot.insight.common.exception.ErrorCode;
import com.coolpilot.insight.source.domain.DataSource;
import com.coolpilot.insight.source.domain.SourceKeyword;
import com.coolpilot.insight.source.dto.KeywordView;
import com.coolpilot.insight.source.infrastructure.persistence.DataSourceMapper;
import com.coolpilot.insight.source.infrastructure.persistence.SourceKeywordMapper;
import com.coolpilot.insight.system.application.PermissionService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 关键词管理。
 *
 * <p>权限规则：普通用户只能看到/操作自己的关键词；管理员可见全部。
 * 去重键是 {@code (source_id, owner_user_id, normalized)}——同一用户在同一平台
 * 重复添加" RAG "和"rag"应当只留一条。
 */
@Service
public class KeywordService {

    private static final int MAX_LENGTH = 128;

    private final SourceKeywordMapper keywordMapper;
    private final DataSourceMapper dataSourceMapper;
    private final PermissionService permissionService;

    public KeywordService(SourceKeywordMapper keywordMapper,
                          DataSourceMapper dataSourceMapper,
                          PermissionService permissionService) {
        this.keywordMapper = keywordMapper;
        this.dataSourceMapper = dataSourceMapper;
        this.permissionService = permissionService;
    }

    public List<KeywordView> list(Long sourceId) {
        Long owner = permissionService.isAdmin() ? null : permissionService.currentUserId();
        // 一次取全部数据源做 code 映射，避免每条关键词回查造成 N+1
        java.util.Map<Long, String> codeById = new java.util.HashMap<>();
        dataSourceMapper.selectAll().forEach(ds -> codeById.put(ds.getId(), ds.getCode()));

        return keywordMapper.selectList(owner, sourceId).stream()
                .map(k -> toView(k, codeById.get(k.getSourceId())))
                .toList();
    }

    public KeywordView create(String sourceCode, String rawKeyword) {
        Long userId = permissionService.requireLogin();
        String keyword = rawKeyword == null ? "" : rawKeyword.trim();
        if (keyword.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "关键词不能为空");
        }
        if (keyword.length() > MAX_LENGTH) {
            throw new BizException(ErrorCode.PARAM_INVALID, "关键词最长 " + MAX_LENGTH + " 字符");
        }

        String code = (sourceCode == null || sourceCode.isBlank()) ? "DOUYIN" : sourceCode.trim().toUpperCase();
        DataSource source = dataSourceMapper.selectByCode(code);
        if (source == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "数据源不存在: " + code);
        }

        String normalized = normalize(keyword);
        if (keywordMapper.countByUnique(source.getId(), userId, normalized) > 0) {
            throw new BizException(ErrorCode.DUPLICATE, "关键词已存在: " + keyword);
        }

        SourceKeyword k = new SourceKeyword();
        k.setSourceId(source.getId());
        k.setKeyword(keyword);
        k.setNormalized(normalized);
        k.setOwnerUserId(userId);
        k.setStatus("ENABLED");
        k.setDiscoverPolicy("MANUAL");
        keywordMapper.insert(k);

        return toView(keywordMapper.selectById(k.getId()), source.getCode());
    }

    public void disable(Long id) {
        requireOwnership(id);
        keywordMapper.updateStatus(id, "DISABLED");
    }

    public void delete(Long id) {
        requireOwnership(id);
        keywordMapper.deleteById(id);
    }

    /** 记录一次发现触发时间（供 collection 模块在 R3-03 调用）。 */
    public void markDiscovered(Long id) {
        keywordMapper.markDiscovered(id);
    }

    private void requireOwnership(Long id) {
        SourceKeyword existing = keywordMapper.selectById(id);
        if (existing == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "关键词不存在");
        }
        if (!permissionService.isAdmin()) {
            Long userId = permissionService.currentUserId();
            if (userId == null || !userId.equals(existing.getOwnerUserId())) {
                throw new BizException(ErrorCode.FORBIDDEN, "无权操作该关键词");
            }
        }
    }

    private static String normalize(String keyword) {
        return keyword.replaceAll("\\s+", "").toLowerCase();
    }

    private KeywordView toView(SourceKeyword k, String sourceCode) {
        KeywordView v = new KeywordView();
        v.setId(k.getId());
        v.setSourceId(k.getSourceId());
        v.setSourceCode(sourceCode);
        v.setKeyword(k.getKeyword());
        v.setStatus(k.getStatus());
        v.setDiscoverPolicy(k.getDiscoverPolicy());
        v.setLastDiscoveredAt(k.getLastDiscoveredAt());
        v.setCreatedAt(k.getCreatedAt());
        return v;
    }
}
