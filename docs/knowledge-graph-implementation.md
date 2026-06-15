
#### BuildKnowledgeGraphUseCase

```kotlin
/**
 * 构建知识图谱
 * 
 * 职责：
 * 1. 遍历所有文档
 * 2. 解析每个文档的链接
 * 3. 构建节点和边
 * 4. 计算节点属性（大小、颜色）
 */
class BuildKnowledgeGraphUseCase @Inject constructor(
    private val documentRepository: DocumentRepository,
    private val parseDocumentLinks: ParseDocumentLinksUseCase,
    private val calculateNodeSize: CalculateNodeSizeUseCase
) {
    suspend operator fun invoke(): Result<KnowledgeGraph> = runCatching {
        // 1. 获取所有文档
        val documents = documentRepository.getAllDocuments().getOrThrow()
        
        // 2. 构建节点映射（ID → 文档）
        val docMap = documents.associateBy { it.id }
        
        // 3. 解析所有文档的链接
        val allLinks = documents.flatMap { doc ->
            parseDocumentLinks(doc).map { targetName ->
                doc.id to targetName
            }
        }
        
        // 4. 构建边列表
        val edges = allLinks.mapNotNull { (sourceId, targetName) ->
            val targetDoc = docMap.values.find { it.name == targetName }
            targetDoc?.let {
                GraphEdge(source = sourceId, target = it.id)
            }
        }
        
        // 5. 统计每个节点的入度（被引用次数）
        val inDegree = edges.groupingBy { it.target }.eachCount()
        
        // 6. 构建节点列表
        val nodes = documents.map { doc ->
            GraphNode(
                id = doc.id,
                label = doc.name,
                size = calculateNodeSize(inDegree[doc.id] ?: 0),
                color = getNodeColor(doc.folderId),
                group = doc.folderId
            )
        }
        
        // 7. 找出孤立节点
        val connectedIds = (edges.map { it.source } + edges.map { it.target }).toSet()
        val isolated = nodes.filter { it.id !in connectedIds }
        
        KnowledgeGraph(nodes, edges, isolated)
    }
}
```

#### ParseDocumentLinksUseCase

```kotlin
/**
 * 解析文档中的链接
 * 
 * 支持：
 * - [[Wiki 链接]]
 * - [Markdown](link.md)
 */
class ParseDocumentLinksUseCase @Inject constructor() {
    
    private val wikiLinkRegex = """\[\[([^\]]+)\]\]""".toRegex()
    private val mdLinkRegex = """\[([^\]]+)\]\(([^)]+\.md)\)""".toRegex()
    
    operator fun invoke(document: Document): List<String> {
        val content = document.content
        val links = mutableListOf<String>()
        
        // 解析 Wiki 链接
        wikiLinkRegex.findAll(content).forEach { match ->
            val linkText = match.groupValues[1].trim()
            links.add(linkText)
        }
        
        // 解析 Markdown 链接
        mdLinkRegex.findAll(content).forEach { match ->
            val linkPath = match.groupValues[2]
            // 提取文件名（去除路径和扩展名）
            val fileName = linkPath.substringAfterLast('/')
                .removeSuffix(".md")
            links.add(fileName)
        }
        
        return links.distinct()
    }
}
```

#### CalculateNodeSizeUseCase

```kotlin
/**
 * 根据引用次数计算节点大小
 * 
 * 规则：
 * - 0 次引用：基础大小 1
 * - 1-3 次：大小 2
 * - 4-10 次：大小 3
 * - 10+ 次：大小 4
 */
class CalculateNodeSizeUseCase @Inject constructor() {
    operator fun invoke(referenceCount: Int): Int = when (referenceCount) {
        0 -> 1
        in 1..3 -> 2
        in 4..10 -> 3
        else -> 4
    }
}
```

---

### 5.3 ViewModel 设计

```kotlin
@HiltViewModel
class GraphViewModel @Inject constructor(
    private val buildKnowledgeGraph: BuildKnowledgeGraphUseCase
) : ViewModel() {
    
    private val _uiState = MutableStateFlow<GraphUiState>(GraphUiState.Loading)
    val uiState: StateFlow<GraphUiState> = _uiState.asStateFlow()
    
    private val _filterOptions = MutableStateFlow(GraphFilterOptions())
    val filterOptions: StateFlow<GraphFilterOptions> = _filterOptions.asStateFlow()
    
    init {
        loadGraph()
    }
    
    fun loadGraph() {
        viewModelScope.launch {
            _uiState.value = GraphUiState.Loading
            buildKnowledgeGraph().onSuccess { graph ->
                _uiState.value = GraphUiState.Success(graph)
            }.onFailure { e ->
                _uiState.value = GraphUiState.Error(e.message ?: "构建图谱失败")
            }
        }
    }
    
    fun updateFilter(options: GraphFilterOptions) {
        _filterOptions.value = options
        // 根据过滤选项重新渲染图谱
    }
    
    fun searchNode(query: String) {
        // 搜索并高亮节点
    }
}

sealed class GraphUiState {
    object Loading : GraphUiState()
    data class Success(val graph: KnowledgeGraph) : GraphUiState()
    data class Error(val message: String) : GraphUiState()
}

data class GraphFilterOptions(
    val showIsolated: Boolean = true,
    val minWordCount: Int = 0,
    val tags: Set<String> = emptySet(),
    val folderIds: Set<String> = emptySet()
)
```

---

## 6. WebView 渲染方案

### 6.1 HTML 模板

```html
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <script src="file:///android_asset/graph/d3.min.js"></script>
    <style>
        body {
            margin: 0;
            padding: 0;
            overflow: hidden;
            background: #1e1e1e; /* 深色背景 */
        }
        #graph-container {
            width: 100vw;
            height: 100vh;
        }
        .node {
            cursor: pointer;
            stroke: #fff;
            stroke-width: 1.5px;
        }
        .node.highlighted {
            stroke: #ff6b6b;
            stroke-width: 3px;
        }
        .link {
            stroke: #999;
            stroke-opacity: 0.6;
        }
        .link.highlighted {
            stroke: #ff6b6b;
            stroke-width: 2px;
        }
        .label {
            font-family: Arial, sans-serif;
            font-size: 12px;
            fill: #fff;
            text-anchor: middle;
            pointer-events: none;
        }
    </style>
</head>
<body>
    <svg id="graph-container"></svg>
    <script src="file:///android_asset/graph/graph-render.js"></script>
    <script>
        // Android 接口
        window.Android = window.Android || {
            onNodeClick: function(nodeId) { console.log('Node clicked:', nodeId); },
            onNodeDoubleClick: function(nodeId) { console.log('Node double clicked:', nodeId); }
        };
        
        // 初始化完成回调
        window.onGraphReady = function() {
            Android.onGraphReady && Android.onGraphReady();
        };
    </script>
</body>
</html>
```

### 6.2 D3.js 渲染逻辑

```javascript
// graph-render.js
(function() {
    const width = window.innerWidth;
    const height = window.innerHeight;
    
    // 创建 SVG
    const svg = d3.select("#graph-container")
        .attr("width", width)
        .attr("height", height);
    
    // 创建力导向模拟
    const simulation = d3.forceSimulation()
        .force("link", d3.forceLink().id(d => d.id).distance(100))
        .force("charge", d3.forceManyBody().strength(-300))
        .force("center", d3.forceCenter(width / 2, height / 2))
        .force("collision", d3.forceCollide().radius(30));
    
    // 渲染图谱
    window.renderGraph = function(graphData) {
        const { nodes, edges } = JSON.parse(graphData);
        
        // 绘制连线
        const link = svg.append("g")
            .selectAll("line")
            .data(edges)
            .enter()
            .append("line")
            .attr("class", "link")
            .attr("stroke-width", d => Math.sqrt(d.weight || 1));
        
        // 绘制节点
        const node = svg.append("g")
            .selectAll("circle")
            .data(nodes)
            .enter()
            .append("circle")
            .attr("class", "node")
            .attr("r", d => 5 + d.size * 3)
            .attr("fill", d => d.color || "#69b3a2")
            .call(d3.drag()
                .on("start", dragStarted)
                .on("drag", dragged)
                .on("end", dragEnded))
            .on("click", function(event, d) {
                window.Android.onNodeClick(d.id);
            })
            .on("dblclick", function(event, d) {
                window.Android.onNodeDoubleClick(d.id);
            });
        
        // 绘制标签
        const label = svg.append("g")
            .selectAll("text")
            .data(nodes)
            .enter()
            .append("text")
            .attr("class", "label")
            .text(d => d.label);
        
        // 更新位置
        simulation.nodes(nodes).on("tick", () => {
            link
                .attr("x1", d => d.source.x)
                .attr("y1", d => d.source.y)
                .attr("x2", d => d.target.x)
                .attr("y2", d => d.target.y);
            
            node
                .attr("cx", d => d.x)
                .attr("cy", d => d.y);
            
            label
                .attr("x", d => d.x)
                .attr("y", d => d.y + 25);
        });
        
        simulation.force("link").links(edges);
        simulation.alpha(1).restart();
        
        window.onGraphReady();
    };
    
    // 拖拽处理
    function dragStarted(event, d) {
        if (!event.active) simulation.alphaTarget(0.3).restart();
        d.fx = d.x;
        d.fy = d.y;
    }
    
    function dragged(event, d) {
        d.fx = event.x;
        d.fy = event.y;
    }
    
    function dragEnded(event, d) {
        if (!event.active) simulation.alphaTarget(0);
        d.fx = null;
        d.fy = null;
    }
})();
```

---

## 7. UI 实现

### 7.1 GraphScreen.kt

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraphScreen(
    navController: NavController,
    viewModel: GraphViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("知识图谱") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { /* 打开搜索 */ }) {
                        Icon(Icons.Default.Search, "搜索")
                    }
                    IconButton(onClick = { /* 打开设置 */ }) {
                        Icon(Icons.Default.Settings, "设置")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val state = uiState) {
                is GraphUiState.Loading -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                is GraphUiState.Success -> {
                    GraphWebView(
                        graph = state.graph,
                        onNodeClick = { nodeId ->
                            // 高亮节点
                        },
                        onNodeDoubleClick = { nodeId ->
                            // 打开文档
                            navController.navigate(Screen.Editor.createRoute(nodeId))
                        }
                    )
                }
                is GraphUiState.Error -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(state.message, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { viewModel.loadGraph() }) {
                            Text("重试")
                        }
                    }
                }
            }
        }
    }
}
```

### 7.2 GraphWebView.kt

```kotlin
@Composable
fun GraphWebView(
    graph: KnowledgeGraph,
    onNodeClick: (String) -> Unit,
    onNodeDoubleClick: (String) -> Unit
) {
    val context = LocalContext.current
    
    AndroidView(
        factory = {
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.javaScriptEnabled = true
                
                // 添加 Android 接口
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onNodeClick(nodeId: String) {
                        onNodeClick(nodeId)
                    }
                    
                    @JavascriptInterface
                    fun onNodeDoubleClick(nodeId: String) {
                        onNodeDoubleClick(nodeId)
                    }
                    
                    @JavascriptInterface
                    fun onGraphReady() {
                        android.util.Log.d("GraphWebView", "图谱渲染完成")
                    }
                }, "Android")
                
                // 加载 HTML 模板
                val template = context.assets.open("graph/graph.html")
                    .bufferedReader().use { it.readText() }
                loadDataWithBaseURL(
                    "file:///android_asset/",
                    template,
                    "text/html",
                    "UTF-8",
                    null
                )
            }
        },
        update = { webView ->
            // 将图谱数据转为 JSON 并传给 WebView
            val graphJson = Json.encodeToString(
                KnowledgeGraph.serializer(),
                graph
            )
            webView.evaluateJavascript(
                "window.renderGraph('${graphJson.replace("'", "\\'")}')",
                null
            )
        }
    )
}
```

---

## 8. 性能优化
