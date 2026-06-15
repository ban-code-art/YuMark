
### 8.1 大规模图谱优化

**问题**：当文档数量超过 500 时，图谱渲染变慢，交互卡顿。

**解决方案**：

#### 1. 分层渲染
```kotlin
// 仅渲染当前视口内的节点
fun filterVisibleNodes(
    graph: KnowledgeGraph,
    viewport: Rect
): KnowledgeGraph {
    val visibleNodes = graph.nodes.filter { node ->
        node.x in viewport.left..viewport.right &&
        node.y in viewport.top..viewport.bottom
    }
    val visibleNodeIds = visibleNodes.map { it.id }.toSet()
    val visibleEdges = graph.edges.filter {
        it.source in visibleNodeIds && it.target in visibleNodeIds
    }
    return KnowledgeGraph(visibleNodes, visibleEdges, emptyList())
}
```

#### 2. LOD（Level of Detail）策略
- **缩小时**：仅显示节点圆点，不显示标签
- **正常时**：显示节点 + 短标签
- **放大时**：显示完整标签 + 连线箭头

#### 3. 分批加载
```javascript
// 分批渲染节点，避免一次性渲染卡顿
function renderGraphInBatches(nodes, edges, batchSize = 50) {
    let index = 0;
    
    function renderBatch() {
        const batch = nodes.slice(index, index + batchSize);
        // 渲染当前批次
        renderNodes(batch);
        
        index += batchSize;
        if (index < nodes.length) {
            requestAnimationFrame(renderBatch);
        } else {
            renderEdges(edges);
        }
    }
    
    renderBatch();
}
```

#### 4. WebWorker 计算布局
```javascript
// 将力导向布局计算放到 Worker 线程
const worker = new Worker('layout-worker.js');
worker.postMessage({ nodes, edges });
worker.onmessage = function(e) {
    const positions = e.data;
    updateNodePositions(positions);
};
```

### 8.2 内存优化

#### 1. 缓存图谱数据
```kotlin
class GraphCache @Inject constructor() {
    private var cachedGraph: KnowledgeGraph? = null
    private var cacheTimestamp: Long = 0
    private val CACHE_DURATION = 5 * 60 * 1000L  // 5 分钟
    
    fun get(): KnowledgeGraph? {
        return if (System.currentTimeMillis() - cacheTimestamp < CACHE_DURATION) {
            cachedGraph
        } else {
            null
        }
    }
    
    fun put(graph: KnowledgeGraph) {
        cachedGraph = graph
        cacheTimestamp = System.currentTimeMillis()
    }
    
    fun invalidate() {
        cachedGraph = null
    }
}
```

#### 2. 增量更新
```kotlin
// 仅在文档变化时更新图谱
fun updateGraphIncremental(
    oldGraph: KnowledgeGraph,
    changedDocId: String,
    newLinks: List<String>
): KnowledgeGraph {
    // 删除旧边
    val edges = oldGraph.edges.filterNot { it.source == changedDocId }
    
    // 添加新边
    val newEdges = newLinks.mapNotNull { targetName ->
        findNodeByName(oldGraph.nodes, targetName)?.let {
            GraphEdge(changedDocId, it.id)
        }
    }
    
    return oldGraph.copy(edges = edges + newEdges)
}
```

---

## 9. 高级功能

### 9.1 中心节点高亮

**功能**：自动识别并高亮"中心文档"（被引用最多的文档）

```kotlin
fun findCentralNodes(graph: KnowledgeGraph, topN: Int = 5): List<GraphNode> {
    val inDegree = graph.edges.groupingBy { it.target }.eachCount()
    return graph.nodes
        .sortedByDescending { inDegree[it.id] ?: 0 }
        .take(topN)
}
```

**UI 显示**：
- 为中心节点添加光环效果
- 在侧边栏显示"核心文档"列表

### 9.2 路径高亮

**功能**：显示两个节点之间的最短路径

```kotlin
fun findShortestPath(
    graph: KnowledgeGraph,
    startId: String,
    endId: String
): List<GraphEdge>? {
    // 使用广度优先搜索（BFS）
    val queue = ArrayDeque<Pair<String, List<GraphEdge>>>()
    queue.add(startId to emptyList())
    val visited = mutableSetOf<String>()
    
    while (queue.isNotEmpty()) {
        val (current, path) = queue.removeFirst()
        
        if (current == endId) return path
        if (current in visited) continue
        visited.add(current)
        
        graph.edges.filter { it.source == current }.forEach { edge ->
            queue.add(edge.target to path + edge)
        }
    }
    
    return null  // 无路径
}
```

**交互**：
- 用户选择两个节点
- 高亮它们之间的连接路径
- 显示路径长度和途经节点

### 9.3 社区检测

**功能**：自动识别文档集群（相关主题的文档群）

```kotlin
// 使用 Louvain 算法检测社区
fun detectCommunities(graph: KnowledgeGraph): Map<String, Int> {
    // 简化版：基于连接密度分组
    val communities = mutableMapOf<String, Int>()
    var communityId = 0
    
    val adjacency = buildAdjacencyList(graph)
    val unvisited = graph.nodes.map { it.id }.toMutableSet()
    
    while (unvisited.isNotEmpty()) {
        val start = unvisited.first()
        val community = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(start)
        
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node in community) continue
            community.add(node)
            unvisited.remove(node)
            
            adjacency[node]?.forEach { neighbor ->
                if (neighbor in unvisited) queue.add(neighbor)
            }
        }
        
        community.forEach { communities[it] = communityId }
        communityId++
    }
    
    return communities
}
```

**UI 显示**：
- 为不同社区分配不同颜色
- 用虚线圈出每个社区范围

### 9.4 时间轴动画

**功能**：按时间顺序播放图谱演变过程

```kotlin
data class GraphSnapshot(
    val timestamp: Long,
    val graph: KnowledgeGraph
)

fun generateTimelineSnapshots(
    documents: List<Document>
): List<GraphSnapshot> {
    val sortedDocs = documents.sortedBy { it.createdAt }
    val snapshots = mutableListOf<GraphSnapshot>()
    
    var currentGraph = KnowledgeGraph(emptyList(), emptyList(), emptyList())
    sortedDocs.forEach { doc ->
        currentGraph = addDocumentToGraph(currentGraph, doc)
        snapshots.add(GraphSnapshot(doc.createdAt, currentGraph))
    }
    
    return snapshots
}
```

**交互**：
- 底部时间轴滑块
- 播放/暂停按钮
- 观察知识网络如何随时间增长

---

## 10. 用户引导

### 10.1 首次使用引导

```
┌──────────────────────────────────────┐
│   🌐 欢迎使用知识图谱                  │
├──────────────────────────────────────┤
│                                      │
│  这里展示了你所有文档之间的连接关系      │
│                                      │
│  💡 小提示：                          │
│  • 单击节点：查看文档信息              │
│  • 双击节点：打开文档编辑              │
│  • 拖拽节点：调整位置                  │
│  • 双指缩放：放大/缩小图谱             │
│                                      │
│         [开始探索]                     │
└──────────────────────────────────────┘
```

### 10.2 空状态提示

**场景 1：无任何文档**
```
┌──────────────────────────────────────┐
│                                      │
│         📄                           │
│    还没有任何文档                      │
│                                      │
│  创建你的第一个文档，开始构建知识网络    │
│                                      │
│         [创建文档]                     │
│                                      │
└──────────────────────────────────────┘
```

**场景 2：文档过少（< 3 个）**
```
┌──────────────────────────────────────┐
│                                      │
│      ●                               │
│                                      │
│  当前文档较少，图谱效果不明显          │
│                                      │
│  💡 建议：创建更多文档并使用 [[链接]]   │
│     语法连接相关内容                   │
│                                      │
└──────────────────────────────────────┘
```

**场景 3：无链接（所有文档孤立）**
```
┌──────────────────────────────────────┐
│                                      │
│   ●    ●    ●    ●                   │
│                                      │
│  所有文档都是孤立的，没有任何链接       │
│                                      │
│  💡 尝试使用 [[文档名]] 创建文档间链接  │
│                                      │
│         [查看帮助]                     │
│                                      │
└──────────────────────────────────────┘
```

---

## 11. 开发计划

### 11.1 MVP（最小可行产品）

**阶段 1：基础功能（2 周）**
- ✅ 数据模型设计
- ✅ Wiki 链接解析
- ✅ 图谱构建 UseCase
- ✅ WebView + D3.js 渲染
- ✅ 基础交互（点击、拖拽、缩放）

**交付物**：
- 能显示所有文档和链接关系
- 双击节点打开文档
- 力导向自动布局

### 11.2 完整版（4 周）

**阶段 2：增强功能（1 周）**
- ✅ 搜索高亮
- ✅ 过滤选项（孤立文档、标签、文件夹）
- ✅ 节点样式（大小、颜色分组）
- ✅ 多种布局算法

**阶段 3：性能优化（1 周）**
- ✅ 大规模图谱优化（500+ 节点）
- ✅ 分层渲染
- ✅ 缓存机制

**阶段 4：高级功能（1 周）**
- ✅ 中心节点识别
- ✅ 最短路径
- ✅ 社区检测

**阶段 5：抛光与测试（1 周）**
- ✅ 首次引导
- ✅ 空状态设计
- ✅ 单元测试
- ✅ 性能测试

---

## 12. 测试计划

### 12.1 功能测试

| 测试项 | 测试步骤 | 预期结果 |
|--------|---------|---------|
| **链接解析** | 创建文档 A 包含 `[[文档 B]]` | 图谱中 A → B 有连线 |
| **双向链接** | A 引用 B，B 引用 A | 显示双向箭头或加粗连线 |
| **节点大小** | 多个文档引用同一文档 | 被引用文档节点更大 |
| **孤立节点** | 创建无任何链接的文档 | 显示在图谱边缘或单独区域 |
| **点击交互** | 单击节点 | 高亮该节点及其连接 |
| **双击打开** | 双击节点 | 跳转到编辑器 |
| **搜索** | 输入文档名搜索 | 高亮匹配节点，自动聚焦 |
| **过滤** | 隐藏孤立节点 | 孤立节点从图谱中消失 |

### 12.2 性能测试

| 场景 | 文档数量 | 链接数量 | 预期性能 |
|------|---------|---------|---------|
| 小规模 | 50 | 100 | 渲染 < 1s，交互流畅 |
| 中规模 | 200 | 500 | 渲染 < 3s，交互流畅 |
| 大规模 | 500 | 1500 | 渲染 < 5s，交互可接受 |
| 超大规模 | 1000+ | 3000+ | 需要分层渲染优化 |

### 12.3 边界测试

- **空图谱**：无文档时显示空状态
- **单节点**：仅 1 个文档
- **无链接**：所有文档孤立
- **循环引用**：A → B → C → A
- **自引用**：文档引用自己
- **无效链接**：`[[不存在的文档]]`

---

## 13. 未来扩展

### 13.1 反向链接面板

在编辑器侧边栏显示"被哪些文档引用"

```
┌────────────────────┐
│  反向链接           │
├────────────────────┤
│  📄 项目计划        │
│  📄 每周总结        │
│  📄 读书笔记        │
└────────────────────┘
```

### 13.2 本地图谱

仅显示当前文档及其直接关联（1 度邻居）

```
        ●───────●
       ╱         ╲
      ●    [当前]  ●
       ╲         ╱
        ●───────●
```

### 13.3 导出图谱

- 导出为图片（PNG/SVG）
- 导出为 JSON（供其他工具分析）
- 导出为 HTML（独立可查看）

### 13.4 3D 图谱

使用 Three.js 渲染 3D 知识网络

---

## 14. 总结

### 14.1 核心价值

✅ **可视化知识结构** - 直观理解文档关系  
✅ **发现知识盲点** - 识别孤立文档和薄弱环节  
✅ **快速导航** - 双击节点即达  
✅ **启发灵感** - 偶然发现意想不到的联系

### 14.2 技术亮点

- WebView + D3.js 快速实现
- 力导向布局自动优化
- 高性能分层渲染
- 丰富的交互体验

### 14.3 推荐实施

**优先级**：⭐⭐⭐⭐ 高  
**开发周期**：2-4 周  
**技术风险**：低（成熟技术栈）  
**用户价值**：高（差异化功能）

---

**文档版本**: v1.0  
**最后更新**: 2026-06-15  
**作者**: Claude Code
