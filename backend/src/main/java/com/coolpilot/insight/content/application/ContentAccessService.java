package com.coolpilot.insight.content.application;

import com.coolpilot.insight.mapper.CollectionTaskItemMapper;
import com.coolpilot.insight.mapper.CollectionTaskMapper;
import com.coolpilot.insight.mapper.VideoGrantMapper;
import com.coolpilot.insight.system.application.PermissionService;
import com.coolpilot.insight.common.exception.BizException;
import com.coolpilot.insight.common.exception.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 内容可见性判定（L2：评论与分析结果）。
 *
 * <p>从 {@code GovernanceService} 拆出的业务部分。判定规则：
 * 管理员 / 显式授权且未过期 / 本人发起过包含该内容的采集任务，三者之一即可读。
 *
 * <p>依赖方向：content → system（权限基础），符合 R1-03 的依赖矩阵。
 *
 * <p><b>已知接缝</b>：本类直接使用 collection 侧的 Mapper（任务/任务项）判断"是否本人采集"。
 * 当前这些 Mapper 尚未归属模块，故未构成模块级越界；R3 引入 collection 模块后，
 * 应改为调用 {@code collection.CollectionQueryService.hasVideoInOwnTasks(userId, videoId)}。
 */
@Service
public class ContentAccessService {

    private final PermissionService permissionService;
    private final VideoGrantMapper grantMapper;
    private final CollectionTaskMapper taskMapper;
    private final CollectionTaskItemMapper itemMapper;

    public ContentAccessService(PermissionService permissionService,
                                VideoGrantMapper grantMapper,
                                CollectionTaskMapper taskMapper,
                                CollectionTaskItemMapper itemMapper) {
        this.permissionService = permissionService;
        this.grantMapper = grantMapper;
        this.taskMapper = taskMapper;
        this.itemMapper = itemMapper;
    }

    public boolean canRead(Long contentId) {
        if (contentId == null) {
            return false;
        }
        if (permissionService.isAdmin()) {
            return true;
        }
        Long userId = permissionService.currentUserId();
        if (userId == null) {
            return false;
        }
        // 1) 显式授权且未过期
        if (grantMapper.countActiveGrant(userId, contentId) > 0) {
            return true;
        }
        // 2) 本人发起过包含该内容的采集任务
        List<Long> taskIds = taskMapper.selectIdsByOwner(userId, 500);
        if (taskIds.isEmpty()) {
            return false;
        }
        return itemMapper.countByVideoAndTaskIds(contentId, taskIds) > 0;
    }

    /** 越权时抛出，由 GlobalExceptionHandler 转为 403。 */
    public void requireRead(Long contentId) {
        if (!canRead(contentId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权访问该内容的评论数据");
        }
    }

    /**
     * 当前用户可读的内容 id 集合。
     *
     * @return {@code null} 表示无限制（管理员）
     */
    public List<Long> readableContentIds() {
        if (permissionService.isAdmin()) {
            return null;
        }
        Long userId = permissionService.currentUserId();
        if (userId == null) {
            return Collections.emptyList();
        }
        Set<Long> ids = new HashSet<>();
        List<Long> taskIds = taskMapper.selectAllIdsByOwner(userId);
        if (!taskIds.isEmpty()) {
            itemMapper.selectByTaskIds(taskIds).forEach(i -> ids.add(i.getVideoId()));
        }
        grantMapper.selectByGrantee(userId).forEach(g -> ids.add(g.getVideoId()));
        return new ArrayList<>(ids);
    }
}
