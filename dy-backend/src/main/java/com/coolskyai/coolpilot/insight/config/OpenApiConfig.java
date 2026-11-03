package com.coolskyai.coolpilot.insight.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI dyCommentOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("DY-Comment 抖音评论分析平台 API")
                        .version("1.0")
                        .description("抖音多视频评论分析与可视化平台接口文档"));
    }
}
