# KubeOnCall RAG 重构方案

## 当前实施优先级（2026-07-15）

本方案中的历史阶段描述保留作架构参考；当前续作只按以下顺序实施：

1. **真实模型联调**：使用真实 embedding 与 cross-encoder 服务验证认证、请求契约、超时、维度和批量吞吐。
2. **通用知识生命周期（已完成代码与单测）**：稳定 `doc_id/file_hash` 幂等更新、`chunk_enable=false` 软删除与恢复，以及 JSONL 批量导入已实现。
3. **效果与治理**：待真实模型联调稳定后，再考虑知识增强和最小效果评估基线。

索引 alias 的 prepare/activate/rollback 代码与接口已具备，但真实 Elasticsearch 切换、旧 concrete index 迁移和回滚演练**明确延期**。本轮不新增 alias 能力，也不将该演练作为 RAG 主线的验收前提。

## 1. 背景与目标

本方案基于以下材料梳理：

- `kubeoncall/项目架构.md` 中的 RAG 架构设计与风险提示
- `kubeoncall/src/main/java/com/kubeoncall/rag`、`domain/rag`、`rag/repository` 下的当前实现
- `rag/6.4ragtest/看代码辅助/RAG相关问题.md`
- `rag/6.4ragtest/看代码辅助/RAG全流程.md`
- `rag/6.4ragtest/AgentSpace知识库检索接口规范.md`
- `rag/6.4ragtest/RAG评估/*` 中关于 embedding、rerank、MTEB/RAGAS 的评估资料

重构目标不是把 KubeOnCall 改成一个独立 RAG 平台，而是在保留当前 Spring Boot 单体、Elasticsearch、MinIO、工作流/Agent 复用方式的前提下，把 RAG 从“可演示链路”升级为“可稳定入库、可量化评估、可持续调优的运维知识检索能力”。

核心目标：

1. 统一知识数据格式与元数据契约，支持可追踪、可更新、可软删除的 chunk 生命周期。
2. 用 Markdown/结构感知分块替代固定字符截断，提高 SOP、手册、故障记录的语义完整性。
3. 补齐真实 embedding 生成、ES dense_vector/HNSW 映射、kNN 查询与 BM25 双路召回。
4. 使用客户端 RRF 融合向量与关键词结果，再接 cross-encoder rerank 精排。
5. 建立离线评估与线上 trace/metrics，让 RAG 效果能被比较和回归保护。

## 2. 当前实现判断

### 2.1 当前已经具备的好骨架

KubeOnCall 目前已经不是简单的 `ES match`：

- `KnowledgeIngestService` 串起了入库、MinIO 原文保存、切分、检索、rerank、父文档聚合。
- `HybridRetrievalService` 已经有 lexical/vector 双路召回、RRF 融合、fallback 和 diagnostics。
- `RerankService` 已经抽象出 `CrossEncoderReranker`，并有 HTTP adapter 和规则 fallback。
- `ElasticsearchKnowledgeRepository` 已经是独立 repository 边界。
- `/api/knowledge/ingest` 与 `/api/knowledge/query` 能作为外部入口，也能被告警工作流复用。

这些边界值得保留，重构应优先补内部能力，而不是推倒重写。

### 2.2 当前主要短板

1. `KnowledgeIngestService` 同时负责入库和检索，职责过重。后续增加 JSONL 导入、重建索引、批量 embedding、评估 trace 后会继续膨胀。

2. `KnowledgeChunker` 使用固定 `CHUNK_SIZE = 280`，无 overlap、无 Markdown 标题感知、无段落边界优先策略，容易切断 SOP 步骤、表格、代码块和告警上下文。

3. chunk 元数据不规范。当前只有 `chunk` 与 `parentDocumentId`，缺少 `doc_id`、`chunk_id`、`chunk_index`、`total_chunks`、`chunk_enable`、`dataset_version`、`source_type`、`file_hash`、`created_at`、`updated_at` 等字段，后续无法稳定增量更新、软删除和评估对齐。

4. parent 聚合在真实链路中可能失效。当前入库只保存 chunks：

   ```java
   List<KnowledgeDocument> chunks = knowledgeChunker.chunk(document);
   chunks.forEach(knowledgeRepository::save);
   ```

   原始 parent document 没有持久化，而 `aggregateParents(...)` 又通过 `loadParents(parentIds)` 查询 parent。测试里 mock 了 parent，但真实 ES 中未必存在 parent 文档。

5. `ElasticsearchKnowledgeRepository.searchVector(...)` 仍是 `content matches` 形态，没有真正的向量字段、embedding 生成、dense_vector 映射和 kNN 查询。

6. ES 查询结果被二次按 `createdAt` 降序排序：

   ```java
   .sorted((left, right) -> right.createdAt().compareTo(left.createdAt()))
   ```

   这会覆盖 ES/BM25 的相关性排序，也会让 RRF 的 rank 输入失真。检索阶段应保留原始相关性 rank 和 score，新旧程度最多作为 tie-breaker 或业务 boost。

7. `EsKnowledgeDocumentEntity` 只有 `title/content/source/metadata/createdAtEpochMs`，缺少显式 mapping、向量字段、可过滤字段类型与 score 承载模型。

8. Rerank 已有接口，但默认 fallback 是 token overlap + metadata contains，适合作兜底，不应作为主精排能力。

9. API 请求模型过薄。`KnowledgeQueryRequest` 只支持 `question + filters`，无法指定 `topK`、检索方法、score threshold、是否 rerank、是否返回 trace。

10. 缺少效果评估闭环。当前测试主要验证链路和 fallback，不能回答“重构后检索质量是否变好”。

## 3. 可借鉴设计

### 3.1 JSONL 外壳 + Markdown content

`rag/6.4ragtest/看代码辅助/RAG相关问题.md` 中最值得借鉴的一点是：保留 JSONL 作为外壳，用 `content` 承载统一 Markdown 正文，用 `metadata` 承载结构化字段。

推荐在 KubeOnCall 中采用：

```jsonl
{"content":"# payment-service 告警处理\n## 现象\n...","metadata":{"title":"payment timeout SOP","source_type":"md","dataset_version":"prod-sop-v1"}}
```

这样兼顾：

- JSONL 的元数据扩展、批量导入、增量更新优势
- Markdown 的标题层级、列表、代码块、表格、链接保真能力
- 后续 Markdown-aware chunking 与人工调试可读性

### 3.2 标准 metadata 契约

参考项目中元数据字段设计比较成熟，KubeOnCall 应固定一组平台级字段：

| 字段 | 类型 | 作用 |
| --- | --- | --- |
| `doc_id` | keyword | 一篇原始文档的稳定 ID |
| `chunk_id` | keyword | 一个切片的稳定 ID |
| `chunk_index` | integer | 切片顺序，用于邻近 chunk 回填 |
| `total_chunks` | integer | 文档总切片数 |
| `parent_document_id` | keyword | parent 聚合与原文回源 |
| `dataset_version` | keyword | 多版本共存、灰度重建、评估对齐 |
| `source_type` | keyword | `md/docx/jsonl/manual/alert_runbook` 等 |
| `file_hash` | keyword | 去重和幂等入库 |
| `chunk_enable` | boolean | 软删除和检索过滤 |
| `created_at` | date | 创建时间 |
| `updated_at` | date | 更新时间 |

业务字段如 `env`、`service`、`namespace`、`severity`、`owner`、`runbook_type` 继续放在 `metadata` 中，但需要通过 mapping 或 flattened 类型保证过滤稳定。

### 3.3 分块策略

参考项目当前使用 `RecursiveCharacterTextSplitter`，并明确提出 Markdown content 后应优先使用 Markdown header splitter。KubeOnCall 可采用分层策略：

1. Markdown 文档：先按 `#`、`##`、`###` 标题切分，标题路径写入 metadata。
2. 超长章节：再按段落、列表、代码块边界递归切分。
3. 普通纯文本：使用递归字符分割，默认 `chunk_size = 1000`、`chunk_overlap = 200`。
4. 已预分块数据：尊重源数据 `chunk_id/chunk_index`，不重复切分。

### 3.4 增强内容不污染 BM25

参考项目的数据增强有亮点：摘要、潜在问题、主题标签能显著扩展语义召回，尤其“可能用户提问”很适合运维问法。

但参考文档也指出了一个关键风险：把增强内容直接拼进 `page_content` 会污染 BM25 的倒排索引。KubeOnCall 应采用“双字段”：

- `content` 或 `text`: 原文 chunk，供 BM25 和最终上下文使用。
- `embedding_text`: 原文 + 摘要 + 潜在问题 + 标签，供 embedding 和向量检索使用。

这样增强只帮助向量召回，不改变关键词检索的词频和 IDF。

### 3.5 双路召回 + 客户端 RRF + Rerank

参考项目最终沉淀出的在线流程很适合 KubeOnCall：

```text
query
  -> embedding
  -> vector topN
  -> BM25 topN
  -> RRF fusion topM
  -> cross-encoder rerank topK
  -> parent/neighbor context assembly
```

候选数建议参考 AgentSpace 规范：

```text
candidate_top_n = min(max(50, 10 * final_top_k), 200)
rrf_k = 60
```

不要再使用 `0.7 * cosine + 0.3 * _score` 这类线性分数融合。向量相似度和 BM25 分数不在同一量纲上，RRF 对 rank 更鲁棒，也不依赖 ES 原生 RRF license。

### 3.6 后续效果治理（非本轮范围）

本轮只完成 RAG 架构边界、真实向量链路、排序和 fallback 设计。检索/生成评估属于后续效果治理，不新增评估数据集、指标脚本或 baseline 交付物，不阻塞架构重构验收。

## 4. 目标架构

### 4.1 模块拆分

建议将当前 `rag` 模块拆成以下职责边界：

```text
com.kubeoncall.rag.ingest
  KnowledgeIngestionFacade
  KnowledgeRecordNormalizer
  KnowledgeMetadataFactory
  MarkdownAwareChunker
  KnowledgeAugmentationService
  EmbeddingService
  KnowledgeIndexWriter

com.kubeoncall.rag.retrieve
  KnowledgeRetrievalFacade
  LexicalRetriever
  VectorRetriever
  RrfFusionService
  RetrievalPolicy
  RetrievalTraceBuilder
  ParentContextAssembler

com.kubeoncall.rag.rerank
  RerankService
  CrossEncoderReranker
  HttpCrossEncoderReranker

com.kubeoncall.rag.repository
  KnowledgeRepository
  ElasticsearchKnowledgeRepository
  KnowledgeIndexAdmin

com.kubeoncall.domain.rag
  KnowledgeDocument
  KnowledgeChunk
  RetrievalRequest
  RetrievalHit
  RetrievalResult
  IngestionResult
```

`KnowledgeIngestService` 可以保留为兼容 facade，但内部委托给 `KnowledgeIngestionFacade` 和 `KnowledgeRetrievalFacade`，逐步迁移调用方。

### 4.2 入库链路

```text
API/JSONL/manual content
  -> normalize to Markdown content
  -> normalize metadata
  -> store raw object to MinIO
  -> chunk with Markdown/recursive strategy
  -> optional augmentation
  -> embedding over embedding_text
  -> save parent + chunks to ES
  -> return doc_id, chunk_count, storage/index status
```

关键点：

- parent document 必须可回源。可以选择保存 parent 到 ES，也可以通过 `parent_document_id -> MinIO objectKey` 回源。第一阶段建议直接保存一个 `document_type = parent` 的 ES 文档，最小化改动。
- chunk ID 应稳定。推荐 `doc_id + chunk_index + content_hash` 或源数据自带 `chunk_id`，避免重复入库生成大量新 UUID。
- `chunk_enable = true` 默认参与检索，删除时先软删除，物理清理异步做。

### 4.3 检索链路

```text
KnowledgeQueryRequest
  -> QueryRewriteService
  -> RetrievalPolicy(method, topK, candidateTopN, filters)
  -> LexicalRetriever.search()
  -> VectorRetriever.search()
  -> RrfFusionService.fuse()
  -> RerankService.rerank()
  -> ParentContextAssembler.aggregate()
  -> RetrievalResult(trace + documents)
```

检索阶段要保留三类分数：

- `lexical_score`: BM25 原始分数或归一化分数
- `vector_score`: kNN 原始分数或归一化分数
- `rrf_score`: 融合分数
- `rerank_score`: cross-encoder 分数

最终排序优先级：

1. rerank score
2. rrf score
3. 原始召回 rank
4. created_at 仅作为 tie-breaker 或可配置时效 boost

## 5. 数据模型与索引设计

### 5.1 领域模型建议

现有 `KnowledgeDocument` 可以渐进扩展，或新增 `KnowledgeChunk` 后再适配旧模型。推荐目标字段：

```java
public record KnowledgeDocument(
        String id,
        String docId,
        String chunkId,
        Integer chunkIndex,
        Integer totalChunks,
        String parentDocumentId,
        String documentType,     // parent 或 chunk
        String title,
        String content,          // BM25 与上下文使用
        String embeddingText,    // 向量化使用
        List<Double> embedding,
        String source,
        String sourceType,
        String datasetVersion,
        String fileHash,
        boolean chunkEnable,
        Map<String, String> metadata,
        Instant createdAt,
        Instant updatedAt
) {}
```

如果不想一次性改所有调用方，可以先新增 `RetrievalHit` 承载 score/rank：

```java
public record RetrievalHit(
        KnowledgeDocument document,
        String channel,          // lexical/vector/fused/rerank
        int rank,
        double score,
        Map<String, Object> details
) {}
```

这样 repository 不再丢掉 ES score，RRF 也有可靠输入。

### 5.2 ES mapping 建议

不要继续完全依赖动态 mapping。建议由 `KnowledgeIndexAdmin` 在应用启动或管理接口中显式创建索引。

核心字段：

```yaml
title:
  type: text
  fields:
    keyword:
      type: keyword
content:
  type: text
embedding_text:
  type: text
  index: false
embedding:
  type: dense_vector
  dims: ${kubeoncall.rag.embedding.dimension}
  index: true
  similarity: cosine
doc_id:
  type: keyword
chunk_id:
  type: keyword
chunk_index:
  type: integer
total_chunks:
  type: integer
parent_document_id:
  type: keyword
document_type:
  type: keyword
dataset_version:
  type: keyword
source:
  type: keyword
source_type:
  type: keyword
file_hash:
  type: keyword
chunk_enable:
  type: boolean
created_at:
  type: date
updated_at:
  type: date
metadata:
  type: flattened
```

中文分词可以后续接 IK。第一阶段如果本地 ES 没装 IK，可以先用 standard analyzer，保留 mapping 扩展点。

## 6. API 与配置调整

### 6.1 查询 API

`POST /api/knowledge/query` 建议向后兼容扩展：

```json
{
  "question": "payment-service timeout 怎么处理",
  "filters": {
    "env": "lab",
    "service": "payment-service"
  },
  "topK": 5,
  "retrieveMethod": "hybrid",
  "rerankEnabled": true,
  "scoreThreshold": 0.0,
  "includeTrace": true
}
```

`retrieveMethod` 支持：

- `keyword`
- `vector`
- `hybrid`

默认仍为 `hybrid`，但当 `vectorEnabled=false` 时自动降级到 `keyword` 并在 trace 中说明。

### 6.2 入库 API

现有 `POST /api/knowledge/ingest` 保持可用，增加可选字段：

```json
{
  "title": "payment timeout SOP",
  "content": "# payment timeout SOP\n...",
  "source": "manual",
  "contentFormat": "markdown",
  "sourceType": "md",
  "datasetVersion": "lab-sop-v1",
  "metadata": {
    "env": "lab",
    "service": "payment-service"
  }
}
```

响应建议从 `KnowledgeDocument` 升级为 `IngestionResult`：

```json
{
  "docId": "doc_xxx",
  "chunkCount": 8,
  "storageStatus": "stored",
  "indexStatus": "indexed",
  "datasetVersion": "lab-sop-v1",
  "warnings": []
}
```

### 6.3 管理 API

可分阶段增加，不必首期全部实现：

- `POST /api/knowledge/import/jsonl`: 批量导入 JSONL。
- `POST /api/knowledge/reindex`: 按 `doc_id`、`title + dataset_version` 或 `file_hash` 重建。
- `POST /api/knowledge/delete`: 设置 `chunk_enable=false`。
- `POST /api/knowledge/index/create`: 创建或校验 ES mapping。

### 6.4 配置建议

```yaml
kubeoncall:
  rag:
    default-top-k: 5
    default-retrieve-method: hybrid
    knowledge-index: kubeoncall-knowledge-v2

    chunk:
      size: 1000
      overlap: 200
      markdown-aware: true
      preserve-heading-path: true

    embedding:
      enabled: true
      base-url: http://localhost:18086/v1
      model: qwen3-embedding-0.6b
      dimension: 1024
      batch-size: 64
      timeout-millis: 10000

    retrieval:
      candidate-min: 50
      candidate-multiplier: 10
      candidate-max: 200
      rrf-k: 60

    rerank:
      enabled: true
      endpoint: http://localhost:18086/v1/rerank
      model: qwen3-reranker-0.6b
      candidate-top-n: 30
      final-top-n: 5
      timeout-millis: 10000

    augment:
      enabled: false
      store-separately: true
      generate-summary: true
      generate-questions: true
      generate-tags: true
```

可先把配置字段落在现有 `KubeOnCallProperties.Rag` 下，后续再拆成 nested classes。

## 7. 分阶段重构计划

### 阶段 1：拆分入库与检索职责

目标：让代码边界支撑后续扩展。

任务：

- 新增 `KnowledgeIngestionFacade`，承接 `ingest(...)`。
- 新增 `KnowledgeRetrievalFacade`，承接 `retrieve(...)`。
- `KnowledgeIngestService` 暂时保留为兼容层，只做委托。
- 引入 `IngestionResult`，内部先保留旧返回适配。
- 新增 `KnowledgeMetadataFactory`，集中生成 `doc_id/chunk_id/chunk_index/chunk_enable/dataset_version`。

验收：

- 现有 `KnowledgeController` 行为不破坏。
- 原有单元测试通过。
- 新增测试覆盖 metadata 生成与空 metadata 行为。

### 阶段 2：分块与 parent 持久化重构

目标：修掉 fixed 280 字符切分和 parent 查不到的问题。

任务：

- 用 `MarkdownAwareChunker` 替换 `KnowledgeChunker`。
- 默认 `chunk_size=1000`、`chunk_overlap=200`，保留配置项。
- chunk metadata 写入 `doc_id/chunk_id/chunk_index/total_chunks/parent_document_id/heading_path`。
- 入库时保存 parent 文档，字段 `document_type=parent`、`chunk_enable=false`，或保存 parent 引用到 MinIO 并保证 `loadParents` 能回源。
- `ParentContextAssembler` 支持 parent 聚合失败时回退到 chunk，不中断检索。

验收：

- 600 字符文本不再机械生成 3 个 280 字符 chunk，而按配置和段落边界切分。
- parent aggregation 在真实 repository 测试中可以查到 parent。
- chunk 顺序可通过 `chunk_index` 恢复。

### 阶段 3：ES mapping 与 embedding 入库

目标：让 vector retrieval 变成真实能力，而不是兼容占位。

任务：

- 新增 `EmbeddingClient`，兼容 OpenAI `/v1/embeddings` 格式。
- 新增 `EmbeddingService`，支持批量 embedding、空文本处理、失败重试、模型名写入 metadata。
- 新增 `KnowledgeIndexAdmin` 创建/校验 ES mapping。
- 扩展 `EsKnowledgeDocumentEntity`，加入 `content`、`embeddingText`、`embedding`、标准元数据字段。
- 入库时对 `embedding_text` 生成向量并写 ES。
- `HttpVectorRetrievalClient` 保留为外部向量服务 adapter，但默认优先使用本地 ES kNN。

验收：

- ES 中存在 dense_vector 字段。
- `searchVector(...)` 使用 kNN 查询，不再是 `content matches`。
- embedding 模型、维度、失败信息进入 diagnostics 或 metadata。

### 阶段 4：检索排序重构

目标：保留相关性排序，完成 keyword/vector/hybrid 三种可控检索。

任务：

- repository 返回 `RetrievalHit`，保留 ES score、rank、channel。
- 删除或改造 `searchByQuery(...).sorted(createdAt)`，不再覆盖相关性排序。
- `LexicalRetriever` 只查 `content`，默认过滤 `chunk_enable=true`。
- `VectorRetriever` 对 query embedding 后查 `embedding` 字段。
- `RetrievalPolicy` 根据 `topK` 计算 `candidateTopN = min(max(50, 10 * topK), 200)`。
- `RrfFusionService` 对 lexical/vector rank 做 RRF，`rrfK=60`。
- `RetrievalResult.diagnostics` 输出 lexical/vector/fused/rerank 的 doc IDs、scores、latency、fallback reason。

验收：

- `keyword`、`vector`、`hybrid` 三种方法都可通过 API 指定。
- vector 不可用时 hybrid 明确 fallback 到 keyword。
- RRF 单元测试覆盖：同一 doc 双路命中分数更高；不同 rank 顺序可预期。

### 阶段 5：Rerank 主路径接入

目标：用 cross-encoder 精排候选，规则 rerank 只做兜底。

任务：

- 对齐 `/v1/rerank` 请求响应格式，支持 `model/query/documents/top_n`。
- `RerankService` 接收 `RetrievalHit`，返回带 `rerank_score` 的结果。
- 默认对 fused top 30 rerank，最终返回 topK。
- rerank 超时或返回异常时，保留 RRF 排序并记录 `crossEncoderFallback=true`。

验收：

- mock HTTP rerank 测试能按外部 score 排序。
- fallback 不影响 query 成功返回。
- diagnostics 能看出 rerank 是否生效。

### 阶段 6：数据增强与双字段策略

目标：借鉴参考项目的摘要/潜在问题/标签，但避免污染 BM25。

任务：

- 新增 `KnowledgeAugmentationService`，可配置开启。
- 增强输出结构化 JSON：`summary`、`questions`、`topics`、`difficulty`、`content_type`。
- `content` 保留原文 chunk。
- `embedding_text` 使用原文 + 摘要 + 可能用户提问 + 标签。
- topics 等字段写 metadata，用于过滤和 trace 展示。
- 增强失败不阻断入库，记录 warning。

验收：

- 开启增强后 BM25 查询仍只匹配原文 content。
- vector embedding 使用增强文本。
- 评估报告能对比 `augment=false` 与 `augment=true`。

### 阶段 7：重建、软删除与版本管理

目标：让知识库可维护。

任务：

- 支持按 `doc_id`、`title + dataset_version`、`file_hash` 删除旧 chunks。
- 删除默认改 `chunk_enable=false`，必要时提供物理删除接口。
- reindex 时同一 title 或 doc_id 只删一次，参考 `rag/6.4ragtest` 的 `removed` 去重逻辑。
- 支持 `dataset_version` 多版本共存，查询默认只查 active version。
- 可选引入 index alias：`kubeoncall-knowledge-active -> kubeoncall-knowledge-v2`，便于平滑切换；真实切换与回滚演练已延期，不阻塞当前知识生命周期建设。

验收：

- 同一文档重复入库不会产生不可控重复 chunk。
- 可按版本过滤检索。
- 软删除 chunk 不再参与 keyword/vector 检索。

### 阶段 8：可观测性

目标：把 RAG 改造成可调系统。

任务：

- 线上 diagnostics 结构化输出：
  - rewrite query
  - retrieve method
  - filters
  - candidate counts
  - latency by stage
  - fallback reasons
  - top docs and scores
- 接入 actuator metrics：
  - `rag.ingest.count`
  - `rag.retrieve.latency`
  - `rag.vector.fallback.count`
  - `rag.rerank.fallback.count`
  - `rag.result.empty.count`

验收：

- 每次改 chunk/embedding/rerank 后有报告可比。
- 线上空召回、vector fallback、rerank fallback 可被观察。

## 8. 推荐优先级

第一优先级，建议立刻做：

1. 拆分 `KnowledgeIngestService` 职责。
2. 修复 parent 不持久化与 ES 结果按 createdAt 重排的问题。
3. 规范 chunk metadata。
4. 替换固定 280 字符切分。

第二优先级，决定 RAG 效果上限：

1. ES 显式 mapping。
2. embedding 入库与 kNN 查询。
3. RRF 融合保留 rank/score。
4. cross-encoder rerank 主路径。

第三优先级，当前续作主线：

1. 通用知识按 `doc_id/file_hash` 幂等更新、`chunk_enable=false` 软删除与恢复。
2. JSONL 批量导入。
3. 真实 embedding/cross-encoder 服务联调与失败边界验证。

索引 alias 的真实切换、旧索引迁移和回滚演练留待上述主线稳定后单独实施。
4. metrics 与 trace 看板。

## 9. 最小可落地版本

如果希望先做一个小步快跑版本，建议范围如下：

1. `KnowledgeChunker` 改成配置化 recursive chunker，加入 overlap 和标准 metadata。
2. 入库保存 parent 文档，或让 parent 聚合从 MinIO 回源。
3. repository 不再按 `createdAt` 覆盖 ES 排序。
4. `RetrievalRequest` 增加 `topK/retrieveMethod/includeTrace`。
5. `HybridRetrievalService` 用公式计算候选数，并保留完整 RRF trace。
6. 加一个 mock embedding/kNN adapter 的单元测试，为后续真实 embedding 接入留接口。

这个版本不一定马上接真实 embedding，但能先把当前链路中会伤害效果和可维护性的点修掉。

## 10. 风险与注意事项

- ES dense_vector mapping 一旦创建，维度不能随意变更。embedding 模型、维度和索引版本必须绑定。
- 增强内容不要直接拼进 BM25 字段，否则会出现参考文档中提到的索引侧污染。
- Rerank 候选数过大会增加延迟，建议先固定 fused top 30，再根据压测调参。
- `metadata` 如果继续使用 `Object` 动态映射，过滤字段类型可能不稳定。建议改为 `flattened` 或明确列出常用过滤字段。
- Query rewrite 对关键词检索可能有副作用，尤其添加同义词后会改变 BM25 查询词。后续应在 diagnostics 中同时保留 raw query 与 rewritten query，并评估 keyword 是否使用改写后的 query。
- 当前 `HttpVectorRetrievalClient` 和未来 ES kNN retriever 要避免双重向量召回混淆。建议通过 `vector.backend=es|external` 明确选择。

## 11. 结论

KubeOnCall 的 RAG 现在已经有不错的编排骨架，问题主要集中在“知识数据工程”和“检索效果工程”两端。重构时应保留现有 API、MinIO、ES、HybridRetrievalService、RerankService 的方向，把参考项目中成熟的 JSONL + Markdown、metadata 契约、RRF、rerank、评估闭环吸收进来。

建议先修 parent、chunk、metadata、排序这几个低风险高收益点，再进入 embedding/kNN/rerank/评估的效果提升阶段。这样改动路径短，收益也能很快被测试和评估报告看见。
