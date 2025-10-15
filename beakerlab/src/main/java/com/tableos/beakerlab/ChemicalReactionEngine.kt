package com.tableos.beakerlab

import android.util.Log
import kotlin.math.sqrt

data class DetectedElement(
    val id: Int,
    val type: ChemicalType,
    val x: Float,
    val y: Float,
    val color: String
)

data class ChemicalReaction(
    val reactants: List<ChemicalType>,
    val products: List<ChemicalType>,
    val name: String,
    val description: String
)

class ChemicalReactionEngine {
    
    private val TAG = "ChemicalReactionEngine"
    
    // 反应距离阈值（像素）
    private var reactionDistance = 100f
    
    // 图像尺寸，用于自适应距离计算
    private var imageWidth = 1920f
    private var imageHeight = 1080f
    
    // 反应冷却时间管理
    private val reactionCooldownMs = 3000L // 3秒冷却时间
    private val lastReactionTimes = mutableMapOf<String, Long>()
    private val detectedReactionPairs = mutableSetOf<String>()
    
    // 定义颜色到化学元素的映射
    private val colorToChemical = mapOf(
        "Yellow" to ChemicalType.Na,   // 黄色 -> Na
        "Blue" to ChemicalType.H2O,    // 蓝色 -> H2O
        "Cyan" to ChemicalType.H2,     // 青色 -> H2
        "Green" to ChemicalType.O2,    // 绿色 -> O2
        "Black" to ChemicalType.C      // 黑色 -> C
    )
    
    // 定义化学反应规则
    private val reactions = listOf(
        // Na + H2O -> NaOH + H2 (钠与水的剧烈反应)
        ChemicalReaction(
            reactants = listOf(ChemicalType.Na, ChemicalType.H2O),
            products = listOf(ChemicalType.NaOH, ChemicalType.H2),
            name = "钠与水反应",
            description = "2Na + 2H₂O → 2NaOH + H₂ ↑"
        ),
        // H2 + O2 -> H2O (氢气燃烧)
        ChemicalReaction(
            reactants = listOf(ChemicalType.H2, ChemicalType.O2),
            products = listOf(ChemicalType.H2O),
            name = "氢气燃烧",
            description = "2H₂ + O₂ → 2H₂O"
        ),
        // Na + O2 -> Na2O (钠在氧气中燃烧)
        ChemicalReaction(
            reactants = listOf(ChemicalType.Na, ChemicalType.O2),
            products = listOf(ChemicalType.NaOH), // 简化产物
            name = "钠氧化反应",
            description = "4Na + O₂ → 2Na₂O"
        ),
        // C + O2 -> CO2 (碳燃烧)
        ChemicalReaction(
            reactants = listOf(ChemicalType.C, ChemicalType.O2),
            products = listOf(ChemicalType.CO2),
            name = "碳燃烧",
            description = "C + O₂ → CO₂"
        )
    )
    
    interface ReactionCallback {
        fun onReactionDetected(reaction: ChemicalReaction, reactantElements: List<DetectedElement>)
    }
    
    private var reactionCallback: ReactionCallback? = null
    
    fun setReactionCallback(callback: ReactionCallback) {
        this.reactionCallback = callback
    }
    
    /**
     * 设置图像尺寸，用于自适应距离计算
     */
    fun setImageSize(width: Int, height: Int) {
        this.imageWidth = width.toFloat()
        this.imageHeight = height.toFloat()
        // 根据图像尺寸调整反应距离阈值
        val diagonal = sqrt(width * width + height * height.toDouble()).toFloat()
        this.reactionDistance = diagonal * 0.25f // 约为对角线长度的25%
        Log.d(TAG, "Image size set to ${width}x${height}, reaction distance: $reactionDistance")
    }
    
    /**
     * 将形状检测结果转换为化学元素
     */
    fun parseDetectedElements(shapeJson: String): List<DetectedElement> {
        val elements = mutableListOf<DetectedElement>()
        
        try {
            // 简单的JSON解析（实际应用中应使用JSON库）
            val lines = shapeJson.split("\n")
            var currentId = 0
            var currentX = 0f
            var currentY = 0f
            var currentColor = ""
            var inPosition = false
            
            for (line in lines.map { it.trim() }) {
                when {
                    line.contains("\"id\":") -> {
                        currentId = line.substringAfter(":").replace(",", "").trim().toIntOrNull() ?: 0
                    }
                    line.contains("\"position\":") -> {
                        inPosition = true
                    }
                    inPosition && line.contains("\"x\":") -> {
                        currentX = line.substringAfter(":").replace(",", "").trim().toFloatOrNull() ?: 0f
                    }
                    inPosition && line.contains("\"y\":") -> {
                        currentY = line.substringAfter(":").replace(",", "").trim().toFloatOrNull() ?: 0f
                        inPosition = false // 结束position解析
                    }
                    line.contains("\"color\":") -> {
                        currentColor = line.substringAfter(":").replace("\"", "").replace(",", "").trim()
                        
                        // 当我们有了所有必要信息时，创建元素
                        colorToChemical[currentColor]?.let { chemicalType ->
                            elements.add(DetectedElement(currentId, chemicalType, currentX, currentY, currentColor))
                            Log.d(TAG, "Detected element: $chemicalType at ($currentX, $currentY) with color $currentColor")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing detected elements", e)
        }
        
        return elements
    }
    
    /**
     * 检测化学反应
     */
    fun detectReactions(elements: List<DetectedElement>) {
        if (elements.size < 2) return
        
        val currentTime = System.currentTimeMillis()
        
        // 检查每种可能的反应
        for (reaction in reactions) {
            val reactantPairs = findReactantPairs(elements, reaction.reactants)
            
            for (pair in reactantPairs) {
                if (areElementsClose(pair.first, pair.second)) {
                    val reactionKey = generateReactionKey(reaction, pair)
                    
                    // 检查冷却时间
                    val lastReactionTime = lastReactionTimes[reactionKey] ?: 0L
                    if (currentTime - lastReactionTime < reactionCooldownMs) {
                        Log.d(TAG, "Reaction ${reaction.name} still in cooldown")
                        continue
                    }
                    
                    // 检查是否是重复的反应对
                    val pairKey = generatePairKey(pair)
                    if (detectedReactionPairs.contains(pairKey)) {
                        Log.d(TAG, "Reaction pair already detected: $pairKey")
                        continue
                    }
                    
                    Log.i(TAG, "New reaction detected: ${reaction.name}")
                    lastReactionTimes[reactionKey] = currentTime
                    detectedReactionPairs.add(pairKey)
                    
                    reactionCallback?.onReactionDetected(reaction, pair.toList())
                    
                    // 清理过期的反应对记录
                    cleanupExpiredReactions(currentTime)
                }
            }
        }
    }
    
    /**
     * 查找反应物对
     */
    private fun findReactantPairs(elements: List<DetectedElement>, reactants: List<ChemicalType>): List<Pair<DetectedElement, DetectedElement>> {
        val pairs = mutableListOf<Pair<DetectedElement, DetectedElement>>()
        
        if (reactants.size != 2) return pairs
        
        val type1 = reactants[0]
        val type2 = reactants[1]
        
        val elements1 = elements.filter { it.type == type1 }
        val elements2 = elements.filter { it.type == type2 }
        
        for (e1 in elements1) {
            for (e2 in elements2) {
                pairs.add(Pair(e1, e2))
            }
        }
        
        return pairs
    }
    
    /**
     * 检查两个元素是否足够接近
     */
    private fun areElementsClose(element1: DetectedElement, element2: DetectedElement): Boolean {
        val distance = calculateDistance(element1.x, element1.y, element2.x, element2.y)
        val normalizedDistance = normalizeDistance(distance)
        
        Log.d(TAG, "Distance between ${element1.type} and ${element2.type}: $distance (normalized: $normalizedDistance, threshold: $reactionDistance)")
        
        return normalizedDistance <= reactionDistance
    }
    
    /**
     * 计算两点之间的距离
     */
    private fun calculateDistance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return sqrt(dx * dx + dy * dy)
    }
    
    /**
     * 标准化距离，考虑图像尺寸
     */
    private fun normalizeDistance(distance: Float): Float {
        // 将距离标准化到标准分辨率 (1920x1080)
        val scaleFactor = sqrt((imageWidth * imageHeight) / (1920f * 1080f))
        return distance / scaleFactor
    }
    
    /**
     * 生成反应键，用于冷却时间管理
     */
    private fun generateReactionKey(reaction: ChemicalReaction, pair: Pair<DetectedElement, DetectedElement>): String {
        return "${reaction.name}_${pair.first.id}_${pair.second.id}"
    }
    
    /**
     * 生成反应对键，用于重复检测防护
     */
    private fun generatePairKey(pair: Pair<DetectedElement, DetectedElement>): String {
        val id1 = pair.first.id
        val id2 = pair.second.id
        // 确保键的一致性，较小的ID在前
        return if (id1 < id2) "${id1}_${id2}" else "${id2}_${id1}"
    }
    
    /**
     * 清理过期的反应记录
     */
    private fun cleanupExpiredReactions(currentTime: Long) {
        // 清理过期的冷却时间记录
        val expiredKeys = lastReactionTimes.filter { (_, time) ->
            currentTime - time > reactionCooldownMs * 2 // 保留2倍冷却时间的记录
        }.keys
        
        expiredKeys.forEach { lastReactionTimes.remove(it) }
        
        // 定期清理反应对记录（每10秒清理一次）
        if (currentTime % 10000 < 100) { // 大约每10秒
            detectedReactionPairs.clear()
            Log.d(TAG, "Cleared detected reaction pairs cache")
        }
    }
    
    /**
     * 获取所有支持的反应
     */
    fun getSupportedReactions(): List<ChemicalReaction> {
        return reactions
    }
    
    /**
     * 获取颜色映射
     */
    fun getColorMapping(): Map<String, ChemicalType> {
        return colorToChemical
    }
}