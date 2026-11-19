# SOURCE-01 · CoolPilot Insight 数据源模块现状基线

日期：2026-11-16　Flyway 30　方法：读源码 + 查库，不引用设计文档

## 1. 一句话结论

**数据源"能列、能启停"，但能力是手写 JSON、与 Python 实现零联动。**
这是本模块最大的真实缺口，也是 SOURCE-03/05 要解决的核心问题。

## 2. 能力矩阵

| 审计项 | 结论 | 证据 |
|---|---|---|
| 数据源列表 | ✅ 已实现 | `GET /api/v1/source/sources` |
| 数据源详情 | ❌ 未实现 | 无 `GET /sources/{code}` |
| 新增/编辑数据源 | ❌ 未实现 | 控制器无 POST/PUT |
| 启停数据源 | ⚠️ 部分 | 有 `PUT /sources/{code}/status`，但**不校验是否有运行中的 Run** |
| 能力管理 | ⚠️ 部分 | `capability_json` 手工写入，无接口可改、无校验 |
| Adapter 元数据 | ❌ 未实现 | `adapter_code` 只是字符串，无版本、无加载状态 |
| Adapter Registry（Python） | ❌ **未实现** | 见 §4 |
| Capability Registry（Python） | ❌ **未实现** | `/v1/capabilities` 只返回 StubAdapter |
| 健康检查（分层） | ❌ 未实现 | 仅 `/health` 返回 `{"code":0,"msg":"ok"}` |
| 账号管理 | ✅ 已实现 | 6 接口 + 页面 + 验证（CONFIG_ONLY） |
| 能力与任务联动 | ❌ 未实现 | 创建任务不校验能力，见 §5 |
| 前端数据源页面 | ❌ 未实现 | 菜单 `/sources` 为 PLANNED |
| 操作审计 | ⚠️ 部分 | 启停/账号/关键词有审计，能力变更无 |

## 3. 数据库现状（实测）

`ds_source`（11 列）：`code, name, source_type, adapter_code, status, capability_json, remark, ...`

现有 1 行数据：

```json
{"comments": true, "commentStatus": "AVAILABLE",
 "contentDetail": true, "keywordSearch": false, "keywordStatus": "PENDING"}
```

问题：
- 字段名与目标契约不一致（目标用 `capabilities[].code`）
- 无 `schemaVersion`，未来加字段无法判断兼容
- 无 `verifiedAt` / `adapterVersion` / `limits`
- **值来自人工填写**，与 `comment.py` 的实际能力无任何程序化关联

## 4. Python 侧真实情况（关键）

```
collection-service/app/
├── server.py                  health / capabilities / jobs
└── adapters/
    ├── base.py                Capability 数据类 + 抽象 Adapter（契约已定）
    ├── stub.py                只有 StubAdapter
    └── douyin/
        ├── comment.py         312 行，DOM 滚动采评论（实际可用）
        ├── search.py          146 行，从未验证成功
        └── browser.py         浏览器启动
```

三条硬事实：

1. **`base.py` 定义了契约，但抖音没有实现它。** 文件里自己写着：
   > 抖音采集以脚本形式存在，尚未改为实现本接口。
2. **`/health` 返回 `"adapters": ["stub"]`——硬编码。**
   实际在干活的 `comment.py` 是一次性脚本子进程，不经过 server.py。
3. **`/v1/capabilities` 只返回 StubAdapter 的声明**，与真实能力无关。

也就是说：**Java 里那份 `capability_json` 是唯一"看起来像能力清单"的东西，
但它不来自任何运行时探测。**

## 5. 能力校验缺失的影响（可验证）

创建采集任务（`CollectServiceImpl.submit`）时只做：

```
视频 id 解析 → videoService.requireOrCreate → countActiveByVideo 预检
```

**没有**：Source 是否存在/启用、能力是否实现、访问模式是否匹配。

实测证据：任务 5（E2E）创建时，`collection_task.source_id` 为 NULL 也能创建成功。

## 6. 与任务卡描述的差异（避免误报为已实现）

| 任务卡假设 | 实际 |
|---|---|
| "数据源管理页面" | 无页面，菜单 PLANNED |
| "能力管理" | 只有一列 JSON，无接口 |
| "Adapter Registry" | 不存在，`base.py` 只是未被实现的契约 |
| "健康检查" | 仅服务存活，无 Adapter/能力/平台/授权分层 |
| "能力与任务联动" | 完全没有 |

## 7. 建议的补齐顺序（供 SOURCE-02 参考）

| 优先 | 项 | 理由 |
|---|---|---|
| 1 | Python Adapter/Capability Registry | 一切"能力可信"的前提；没有它，前端展示什么都是猜 |
| 2 | 能力契约 v1（含 schemaVersion） | 两端共用；现在字段名两边不一致 |
| 3 | 任务创建校验（Source/能力/访问模式） | 当前会创建注定失败的任务 |
| 4 | 数据源 CRUD + 页面 | 展示层，依赖 1、2 |
| 5 | 分层健康检查 | 依赖 1 |

## 8. 不确定项

- `search.py` 是否可用：从未成功，不能计入能力矩阵
- `ds_source.status` 现有取值 `ENABLED`，与 `ds_account.status` 的 `ACTIVE` **命名不一致**，
  统一与否需 SOURCE-02 裁决（我倾向保留各自语义，避免无收益迁移）
