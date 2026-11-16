# SOURCE-04-07 实施报告 · 数据源能力页（三态展示 + Desired 配置）

> 状态：**已实施**，待评审
> 前置：SOURCE-04-06（`GET/PUT /api/v1/source/sources/{code}/capabilities`）
> 关联：`SOURCE-04-TASKCARD.md` §SOURCE-04-07、`ADR-006`、`ADR-008`

---

## 一、为什么现在做

本卡原定推迟，理由是"本轮不做前端"。推迟期间页面读的是 `capability` 字段——
即 `ds_source.capability_json` 的旧结构，**不反映运行时**。
那意味着运营看到"评论：支持"就会当成事实，而实际上：

- 配置说支持 ≠ 采集服务真的加载了；
- 采集服务加载了 ≠ 证据有效（未验证的能力不可执行）；
- 证据有效 ≠ 业务允许执行（Desired 可能已禁用）。

三态接口（04-06）落地后，这个误导有了消除的条件。

---

## 二、交付

| 文件 | 内容 |
|---|---|
| `frontend/src/views/source/SourceManageView.vue` | 新增页面：列表（含快照状态徽标）+ 三态明细抽屉 + Desired 编辑 |
| `frontend/src/router/index.js` | 新增 `/sources` 路由 |
| `frontend/src/api/index.js` | `sourceApi.capabilities()` / `sourceApi.saveCapabilities()` |
| `V33__source_manage_menu.sql` | `/sources` 菜单 PLANNED → ENABLED，并同步授予 ADMIN 菜单 |

> `/sources` 菜单项在 V25 就已建好（状态 PLANNED）。V33 **只改状态不新建**：
> 新建会出现两条同路径菜单，前端按 path 匹配的行为将不可预期。

---

## 三、页面的四条硬规则

1. **三态平行展示，不合并成一列**
   同一行给出「配置（Desired）/ 运行时（Observed）/ 结论（Effective）」。
   合并等于让"配置说支持"冒充"运行时可用"（ADR-006 明令禁止）。

2. **快照不是 FRESH 时必须显式警告**
   顶部与抽屉内都提示"此时任何能力都为 UNKNOWN，请不要据此认定能力不可用"。
   `STALE` / `UNAVAILABLE` 不是"平台不支持"，把它读成不支持是最容易犯的错。

3. **结论必须同时显示 status 与 reason**
   只显示 `UNREGISTERED` 会误导：它可能是"没注册"，也可能是"函数解析不到"；
   只显示 `UNKNOWN` 会掩盖"服务挂了"和"已注册未验证"的区别（ADR-008 注意 3）。

4. **配置无法解析时不能显示成"没有能力"**
   `desiredError` 非空时给红色告警，并说明"该数据源下所有能力都不会被允许执行"。
   否则空表会被读成"业务没配"。

---

## 四、编辑器的边界

- 前端只做**必填提醒**（接入/能力码/访问模式）；真正的判据在后端：
  接口逐字段判类型与枚举，前端放过去的东西后端一样会拒。
- `connectorCode` **只能从已知接入里选**（Observed 的接入 + 现有声明里的接入）。
  列表中为空时**不允许添加能力**，并提示先确认采集服务已加载对应接入——
  前端拼一个 `<CODE>_PUBLIC` 就是凭契约猜命名，拼错会让核对永远判 `ADAPTER_MISSING`。
- 能力码 → 分类的联动是**契约固定搭配**（`SOURCE-02-capability-schema.json`），
  不是推断。
- 无 `source:capability:manage` 权限时整块只读，并显式说明缺哪个权限码。

---

## 五、验证（按证据类别分开）

| 类别 | 结论 |
|---|---|
| 构建 | `npm run build` 通过，产物含 `SourceManageView-*.js` / `.css` |
| 部署 | `cpi-frontend` 容器内 `/usr/share/nginx/html/assets/SourceManageView-BBF_5aB8.js` 存在，`GET /` 返回 200 |
| 数据链路 | 页面调用的两个接口已在真实容器验证（见 `SOURCE-04-06-IMPLEMENTATION.md` §4.3）：401/403、FRESH 快照三态、写 v1 → 400、写合法 v2 → 200 |
| 视觉/交互 | ❌ **未做**：自动化浏览器工具在本机不可用（`agent-browser` 启动挂起，已终止），未截图，未做点击级验证 |

> 最后一行是本次交付的**已知缺口**：页面能编译、能部署、接口能通，
> 但"渲染是否正确、交互是否顺手"没有实证。这一项需要人工过一遍。

---

## 六、遗留

| 项 | 说明 |
|---|---|
| 视觉验证 | 见 §五最后一行 |
| 前端单测 | 项目当前无前端测试基建（`vitest` 未引入），本卡未新建 |
| `adapterVersion` 锁定 | 接口支持，页面未暴露输入框（避免误填把能力锁死在错误版本）；需要在管理端显式配置时再补 |
| 多 Connector 展示 | 对照表按能力码归并，同一能力在多个接入下的差异会被折叠；当前只有一个接入，先不做区分 |
