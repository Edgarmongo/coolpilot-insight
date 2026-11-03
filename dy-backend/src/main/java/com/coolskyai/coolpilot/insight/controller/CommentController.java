package com.coolskyai.coolpilot.insight.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.coolskyai.coolpilot.insight.annotation.RequireRole;
import com.coolskyai.coolpilot.insight.dto.Result;
import com.coolskyai.coolpilot.insight.entity.Comment;
import com.coolskyai.coolpilot.insight.service.CommentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/comment")
public class CommentController {

    @Autowired
    private CommentService commentService;

    @RequireRole
    @GetMapping("/list")
    public Result<Page<Comment>> list(@RequestParam String videoId,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size,
                                       @RequestParam(required = false) String region,
                                       @RequestParam(required = false) Integer sentiment,
                                       @RequestParam(required = false) String sort) {
        return Result.ok(commentService.listByVideo(videoId, page, size, region, sentiment, sort));
    }

    @RequireRole
    @GetMapping("/search")
    public Result<Page<Comment>> search(@RequestParam String keyword,
                                         @RequestParam(required = false) String videoId,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return Result.ok(commentService.search(keyword, videoId, page, size));
    }
}
