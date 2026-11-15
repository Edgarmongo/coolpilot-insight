# R1-05 · 数据库 ER 图

基于 R1-04 的 37 张表。为可读性按模块分图，虚线表示跨模块的逻辑关联（无物理外键）。

## 1. 系统与数据源

```mermaid
erDiagram
    SYS_USER ||--o{ SYS_USER_ROLE : "拥有"
    SYS_ROLE ||--o{ SYS_USER_ROLE : "被授予"
    SYS_USER ||--o{ SYS_AUDIT_LOG : "产生"
    SYS_USER ||--o{ SYS_FILE : "上传"

    DS_SOURCE ||--o{ DS_ACCOUNT : "拥有账号"
    DS_SOURCE ||--o{ DS_KEYWORD : "配置关键词"

    SYS_USER {
        bigint id PK
        varchar phone UK
        varchar password_hash
        tinyint status
    }
    SYS_ROLE {
        bigint id PK
        varchar code UK
        varchar name
    }
    SYS_USER_ROLE {
        bigint id PK
        bigint user_id FK
        bigint role_id FK
    }
    SYS_AUDIT_LOG {
        bigint id PK
        bigint user_id FK
        varchar action
        varchar resource_type
        bigint resource_id
    }
    SYS_FILE {
        bigint id PK
        varchar file_name
        varchar storage_path
        bigint size
    }
    DS_SOURCE {
        bigint id PK
        varchar code UK
        varchar adapter_code
        varchar status
        json capability_json
    }
    DS_ACCOUNT {
        bigint id PK
        bigint source_id FK
        varchar external_account_id
        varchar status
        datetime expires_at
    }
    DS_KEYWORD {
        bigint id PK
        bigint source_id FK
        varchar normalized
        bigint owner_user_id
    }
```

## 2. 采集（collection）

```mermaid
erDiagram
    DS_SOURCE ||--o{ COL_TASK : "作为采集目标"
    COL_TASK ||--o{ COL_TASK_ITEM : "包含"
    COL_TASK ||--o{ COL_TASK_LOG : "记录日志"
    COL_TASK_ITEM ||--o| COL_CHECKPOINT : "断点"

    COL_TASK {
        bigint id PK
        varchar task_no UK
        varchar task_type
        bigint source_id FK
        varchar status
        bigint owner_user_id
    }
    COL_TASK_ITEM {
        bigint id PK
        bigint task_id FK
        varchar external_content_id
        varchar status
        tinyint active_flag
        int retry_count
    }
    COL_TASK_LOG {
        bigint id PK
        bigint task_id FK
        varchar level
        text message
    }
    COL_CHECKPOINT {
        bigint id PK
        bigint task_item_id FK
        bigint persisted_count
        json cursor_json
    }
```

> `COL_TASK_ITEM` **不外键到内容表**：采集先于入库，content 依赖 collection（R1-03 方向约束）。

## 3. 内容（content）

```mermaid
erDiagram
    DS_SOURCE ||--o{ CT_CONTENT : "来源"
    CT_CONTENT ||--o{ CT_COMMENT : "包含评论"
    CT_CONTENT }o--|| CT_RAW_ARCHIVE : "原始归档"
    CT_AUTHOR ||--o{ CT_CONTENT : "发布"
    CT_AUTHOR ||--o{ CT_COMMENT : "评论"
    CT_CONTENT ||--o{ CT_GRANT : "读授权"
    SYS_USER ||--o{ CT_GRANT : "被授权"

    CT_RAW_ARCHIVE {
        bigint id PK
        bigint source_id
        varchar external_id
        mediumtext payload
        varchar payload_hash
    }
    CT_CONTENT {
        bigint id PK
        bigint source_id FK
        varchar external_content_id
        bigint author_id FK
        bigint raw_archive_id FK
        varchar normalize_status
        datetime published_at
    }
    CT_COMMENT {
        bigint id PK
        bigint content_id FK
        varchar external_comment_id
        varchar dedup_key UK
        varchar parent_external_id
        datetime published_at
    }
    CT_AUTHOR {
        bigint id PK
        bigint source_id
        varchar external_author_id
        varchar display_name
    }
    CT_GRANT {
        bigint id PK
        bigint grantee_user_id FK
        bigint content_id FK
        varchar permission
        datetime expires_at
    }
```

## 4. 分析（analysis）

```mermaid
erDiagram
    AN_PROFILE ||--o{ AN_TASK : "使用版本"
    AN_TASK ||--o{ AN_TASK_ITEM : "包含"
    AN_TASK_ITEM }o--|| CT_COMMENT : "分析对象"
    AN_PROFILE ||--o{ AN_RESULT : "产生"
    CT_COMMENT ||--o{ AN_RESULT : "被分析"
    AN_RESULT ||--o{ AN_EVIDENCE : "证据"
    AN_INSIGHT ||--o{ AN_EVIDENCE : "证据"
    CT_CONTENT ||--o{ AN_INSIGHT : "视频级洞察"
    AN_RESULT ||--o{ AN_REVIEW : "人工复核"
    AN_TASK ||--o{ AN_USAGE : "用量"

    AN_PROFILE {
        bigint id PK
        varchar profile_code
        int profile_version
        varchar prompt_version
        varchar model_config
        varchar status
    }
    AN_RESULT {
        bigint id PK
        bigint comment_id FK
        bigint profile_id FK
        varchar demand_category
        varchar buying_stage
        int intent_score
        decimal confidence
    }
    AN_INSIGHT {
        bigint id PK
        bigint content_id FK
        bigint profile_id FK
        json result_json
    }
    AN_EVIDENCE {
        bigint id PK
        bigint result_id FK
        bigint insight_id FK
        varchar source_level
        text evidence_text
    }
    AN_REVIEW {
        bigint id PK
        bigint result_id FK
        bigint reviewer_user_id
        varchar status
    }
    AN_USAGE {
        bigint id PK
        bigint task_id FK
        varchar model_name
        int prompt_tokens
        int completion_tokens
        decimal cost_amount
    }
```

## 5. 线索与转化（lead / conversion）

```mermaid
erDiagram
    AN_RESULT ||--o{ LD_SIGNAL : "提取信号"
    LD_SIGNAL ||--o{ LD_EVIDENCE : "作为证据"
    LD_LEAD ||--o{ LD_EVIDENCE : "拥有证据"
    LD_LEAD ||--o{ LD_SCORE_HISTORY : "评分变化"
    LD_LEAD ||--o{ LD_REVIEW : "人工审核"
    LD_LEAD ||--o{ LD_FOLLOWUP : "跟进"
    CT_AUTHOR ||--o{ LD_LEAD : "来源作者"
    LD_LEAD ||--o{ CV_MATCH : "匹配"
    CV_PRODUCT ||--o{ CV_MATCH : "被匹配"
    LD_LEAD ||--o| CV_CONVERSION : "成交"

    LD_SIGNAL {
        bigint id PK
        bigint result_id FK
        varchar signal_type
        varchar intent_level
    }
    LD_LEAD {
        bigint id PK
        varchar lead_code UK
        bigint source_author_id FK
        int lead_score
        varchar status
        varchar review_status
    }
    LD_EVIDENCE {
        bigint id PK
        bigint lead_id FK
        bigint signal_id FK
        text evidence_text
    }
    LD_SCORE_HISTORY {
        bigint id PK
        bigint lead_id FK
        int old_score
        int new_score
        json score_detail_json
    }
    LD_REVIEW {
        bigint id PK
        bigint lead_id FK
        varchar review_result
    }
    LD_FOLLOWUP {
        bigint id PK
        bigint lead_id FK
        varchar followup_type
        varchar consent_status
    }
    CV_PRODUCT {
        bigint id PK
        varchar external_product_id UK
        varchar product_name
        varchar status
    }
    CV_MATCH {
        bigint id PK
        bigint lead_id FK
        bigint product_id FK
        int match_score
    }
    CV_CONVERSION {
        bigint id PK
        bigint lead_id FK
        varchar external_order_id
        decimal amount
        varchar attribution_source
    }
```

## 6. 分析（analytics）

```mermaid
erDiagram
    AG_REPORT ||--o{ AG_REPORT_VERSION : "版本"
    AG_REPORT_VERSION }o--o| SYS_FILE : "导出文件"

    AG_REPORT {
        bigint id PK
        varchar report_no UK
        bigint owner_user_id
        varchar status
    }
    AG_REPORT_VERSION {
        bigint id PK
        bigint report_id FK
        int version_no
        text content_md
        bigint file_id
    }
```

## 7. 追溯链（关键验收项）

```
原始响应 ct_raw_archive
      ↓ raw_archive_id
标准化内容 ct_content ← ct_comment
      ↓ comment_id              ↓ content_id
   an_result ────────────→ an_insight
      ↓ result_id               ↓ insight_id
   ld_signal              an_evidence
      ↓ signal_id
   ld_evidence ←── lead_id ──→ ld_lead
                                  ↓ lead_id
                            cv_match / cv_conversion
                                  ↓ external_order_id
                            （CoolSkyAI 订单，不入库）
```

任一条线索都能沿此链回溯到：哪条评论/视频 → 哪次分析（哪个 Profile 版本）→ 哪段原文证据。
