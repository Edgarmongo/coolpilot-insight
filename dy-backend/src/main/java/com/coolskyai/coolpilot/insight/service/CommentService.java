package com.coolskyai.coolpilot.insight.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.coolskyai.coolpilot.insight.entity.Comment;

public interface CommentService {
    Page<Comment> listByVideo(String videoId, int page, int size, String region, Integer sentiment, String sort);
    Page<Comment> search(String keyword, String videoId, int page, int size);
}
