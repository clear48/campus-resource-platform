# 分类查询模块开发记录

## 0. 文档范围

本文档记录当前已经落地的“分类查询模块”，对应代码包路径为 `com.john.campus`。

当前模块只覆盖资料上传前需要使用的分类列表查询能力，不涉及资料上传、文件上传、资料审核、分类新增、分类编辑、分类删除或后台分类管理。

## 1. 模块目标

分类查询模块用于向前端提供可选择的启用分类列表。前端可以先请求一级分类，再根据某个分类 ID 查询其子分类，用于后续创建资料时选择 `categoryId`。

## 2. 本次实现功能

- 创建 `Category` 实体，对应 MySQL `category` 表。
- 创建 `CategoryMapper` 和 `CategoryMapper.xml`。
- 实现按 `parentId` 查询启用分类。
- 查询条件固定为 `parent_id = #{parentId}` 和 `status = 1`。
- 排序规则固定为 `sort_order ASC, id ASC`。
- 创建 `CategoryService` 和 `CategoryServiceImpl`。
- 创建 `CategoryController`。
- 实现公开接口 `GET /api/v1/categories?parentId=0`。
- 在 `WebMvcConfig` 中排除 JWT 拦截，该接口不需要登录。
- 返回 `CategoryVO`，不直接返回 `Category` Entity。
- 对 `parentId < 0` 返回 `40001 PARAM_ERROR`。
- 对 `parentId` 类型错误统一返回 `40001 PARAM_ERROR`。

本模块暂未涉及：

- 分类新增、编辑、删除。
- 分类树一次性递归查询。
- 分类缓存。
- 后台分类管理权限。
- 资料上传或资料创建。

## 3. 涉及接口

| 接口名称 | 方法 | 路径 | 是否登录 | 权限要求 | Controller 方法 |
| --- | --- | --- | --- | --- | --- |
| 获取分类列表 | `GET` | `/api/v1/categories?parentId=0` | 否 | 无 | `CategoryController.list` |

请求参数：

| 参数 | 类型 | 是否必填 | 说明 |
| --- | --- | --- | --- |
| `parentId` | long | 否 | 父分类 ID，默认 `0`；不能小于 `0` |

响应数据：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `categoryId` | long | 分类 ID |
| `parentId` | long | 父分类 ID |
| `categoryName` | string | 分类名称 |
| `description` | string | 分类说明 |
| `sortOrder` | int | 排序值 |

## 4. 涉及数据库表

### 4.1 `category` 分类表

本模块使用已有 `category` 表，没有新增表或新增索引。

涉及字段：

| 字段 | 使用场景 |
| --- | --- |
| `id` | 分类主键，作为 `categoryId` 返回 |
| `parent_id` | 查询指定父分类下的子分类 |
| `category_name` | 返回分类名称 |
| `description` | 返回分类说明 |
| `sort_order` | 控制分类展示顺序 |
| `status` | 只返回启用分类，固定过滤 `status = 1` |
| `created_at` | Entity 映射保留，接口暂不返回 |
| `updated_at` | Entity 映射保留，接口暂不返回 |

涉及索引：

| 索引 | 使用场景 |
| --- | --- |
| `idx_category_parent_status` | 支撑按父分类和启用状态查询分类列表 |
| `uk_category_parent_name` | 数据库层防止同一父分类下分类名称重复，本查询模块不直接写入数据 |

## 5. 涉及 Redis Key

本模块暂未使用 Redis。

分类数据当前直接查询 MySQL，后续如果分类访问量较高，可以增加分类列表缓存，例如：

```text
crp:cache:category:children:{parentId}
```

当前未实现该缓存，避免引入不必要的一致性维护成本。

## 6. 核心类与方法

| 文件 | 说明 |
| --- | --- |
| `campus-resource-platform/src/main/java/com/john/campus/entity/Category.java` | 分类实体，映射 `category` 表 |
| `campus-resource-platform/src/main/java/com/john/campus/mapper/CategoryMapper.java` | 分类 MyBatis Mapper 接口 |
| `campus-resource-platform/src/main/resources/mapper/CategoryMapper.xml` | 分类 SQL 映射 |
| `campus-resource-platform/src/main/java/com/john/campus/service/CategoryService.java` | 分类业务接口 |
| `campus-resource-platform/src/main/java/com/john/campus/service/impl/CategoryServiceImpl.java` | 分类查询业务实现 |
| `campus-resource-platform/src/main/java/com/john/campus/controller/CategoryController.java` | 分类查询接口入口 |
| `campus-resource-platform/src/main/java/com/john/campus/vo/CategoryVO.java` | 分类接口响应对象 |
| `campus-resource-platform/src/main/java/com/john/campus/config/WebMvcConfig.java` | JWT 拦截器排除 `/api/v1/categories` |

核心方法：

| 类 | 方法 | 作用 |
| --- | --- | --- |
| `CategoryController` | `list(Long parentId)` | 接收查询参数并返回统一响应 |
| `CategoryService` | `listEnabledCategoriesByParentId(Long parentId)` | 查询指定父分类下的启用分类 |
| `CategoryServiceImpl` | `listEnabledCategoriesByParentId(Long parentId)` | 校验 `parentId`，调用 Mapper，并转换为 VO |
| `CategoryMapper` | `selectEnabledByParentId(Long parentId)` | 执行分类列表 SQL |

## 7. 查询 SQL

当前 XML 中的核心查询如下：

```sql
SELECT
  id,
  parent_id,
  category_name,
  description,
  sort_order,
  status,
  created_at,
  updated_at
FROM category
WHERE parent_id = #{parentId}
  AND status = 1
ORDER BY sort_order ASC, id ASC
```

说明：

- `status = 1` 保证上传前只能选择启用分类。
- `sort_order ASC, id ASC` 保证展示顺序稳定。
- 接口返回 `CategoryVO`，不暴露 Entity 中的 `status`、`createdAt`、`updatedAt`。

## 8. 请求处理流程

1. 客户端请求 `GET /api/v1/categories?parentId=0`。
2. `WebMvcConfig` 命中排除路径，不进入 JWT 拦截器。
3. `CategoryController.list` 接收 `parentId`，默认值为 `0`。
4. `CategoryServiceImpl` 校验 `parentId` 不能小于 `0`。
5. `CategoryMapper.selectEnabledByParentId` 查询 `category` 表中启用分类。
6. Service 将 `Category` 转换为 `CategoryVO`。
7. Controller 返回 `ApiResponse<List<CategoryVO>>`。

## 9. 权限控制

- `/api/v1/categories`：不需要登录。
- 本接口只读启用分类，不返回用户隐私数据，也不暴露资料上传、审核等受保护能力。
- 后续如果增加分类管理接口，应放在管理员路径下，并增加管理员权限校验。

## 10. 异常处理

| 异常场景 | 抛出位置 | 错误码 |
| --- | --- | --- |
| `parentId < 0` | `CategoryServiceImpl` | `40001 PARAM_ERROR` |
| `parentId` 类型错误 | `GlobalExceptionHandler` | `40001 PARAM_ERROR` |

## 11. 测试与验证

已执行验证：

| 测试项 | 命令 | 结果 |
| --- | --- | --- |
| 编译验证 | `.\mvnw.cmd -DskipTests compile` | 通过 |

建议接口测试用例：

| 用例 | 请求 | 预期结果 |
| --- | --- | --- |
| 查询一级分类 | `GET /api/v1/categories?parentId=0` | 返回 `code = 0` 和启用分类列表 |
| 查询子分类 | `GET /api/v1/categories?parentId=10` | 返回指定父分类下启用子分类 |
| 不传 `parentId` | `GET /api/v1/categories` | 按 `parentId = 0` 查询 |
| 负数 `parentId` | `GET /api/v1/categories?parentId=-1` | 返回 `40001 PARAM_ERROR` |
| 非数字 `parentId` | `GET /api/v1/categories?parentId=abc` | 返回 `40001 PARAM_ERROR` |

## 12. 面试可讲点

- 分类查询是资料上传前置能力，但本模块只实现只读查询，没有提前做上传或审核模块。
- 接口不需要登录，因为分类本身是公开枚举数据，适合上传页或搜索筛选页提前加载。
- 查询固定过滤 `status = 1`，避免禁用分类继续被新资料引用。
- 返回 VO 而不是 Entity，避免把数据库状态、创建时间、更新时间等内部字段暴露给前端。
- 排序使用 `sort_order ASC, id ASC`，既支持人工配置顺序，也保证相同排序值下结果稳定。

## 13. Git Commit Message 建议

```text
feat(category): add public category query API

- add category entity, mapper, service and controller
- query enabled categories by parentId with stable ordering
- exclude category endpoint from JWT interceptor
- document category module and database change status
```
