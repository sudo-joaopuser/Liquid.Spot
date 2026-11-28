package app.liquidspot.xposed.features.pages

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RecordingCanvas
import android.graphics.RenderNode
import android.os.SystemClock
import android.util.SparseIntArray
import android.view.View
import app.liquidspot.xposed.HookContext
import app.liquidspot.xposed.ui.glass.BackdropSource
import app.liquidspot.xposed.util.Logger
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.IdentityHashMap
import java.util.WeakHashMap
import org.lsposed.hiddenapibypass.HiddenApiBypass
import kotlin.math.abs
import kotlin.math.max

object ComposeHooks {
    class Page {
        var back: Any? = null
        var menu: Any? = null
        var share: Any? = null
        var playTop = Float.NaN
        var playBottom = Float.NaN
        var playAt = 0L
        var playLeft = 0f
        var backLabel: String? = null
    }

    private var installed = false
    @Volatile
    private var ready = false
    private lateinit var lambdaType: Class<*>
    private lateinit var thenMethod: Method
    private lateinit var drawBehind: Constructor<*>
    private lateinit var actionLabel: Field
    private lateinit var actionClick: Field

    private val density = Resources.getSystem().displayMetrics.density
    private val paint = Paint().apply {
        color = LINE_COLOR
        strokeWidth = max(1f, LINE_DP * density)
    }
    private val inset = INSET_DP * density
    private val minRow = (MIN_ROW_DP * density).toInt()
    private val maxRow = (MAX_ROW_DP * density).toInt()
    private val matrix = Matrix()
    private val values = FloatArray(9)

    private val pages = WeakHashMap<View, Page>()
    private val tagged = WeakHashMap<Any, String>()
    private val separators = HashMap<Int, Any>()
    private var playProbe: Any? = null
    @Volatile
    private var playNode: RenderNode? = null
    @Volatile
    private var tracking = false

    private class Link(var parent: RenderNode? = null, var offset: Float = 0f, var offsetX: Float = 0f)

    private val links = WeakHashMap<RenderNode, Link>()
    private val nodeField: Field? by lazy { runCatching { HiddenApiBypass.getInstanceFields(RecordingCanvas::class.java).filterIsInstance<Field>().firstOrNull { it.name == "mNode" }?.apply { isAccessible = true } }.getOrNull() }
    private val heights = SparseIntArray()
    private val canvasPaths = HashMap<Class<*>, List<Field>>()
    private val ownerPaths = HashMap<Class<*>, List<Field>>()
    private val sizeMethods = HashMap<Class<*>, Method>()


    fun install(ctx: HookContext) {
        if (installed) return
        installed = true
        Thread({ ComposeSymbols.whenReady(ctx.appInfo) { installWith(ctx, it) } }, "LiquidSpotPages").start()
    }

    private fun installWith(ctx: HookContext, symbols: ComposeSymbols) {
        runCatching {
            nodeField
            val loader = ctx.classLoader
            fun type(name: String) = Class.forName(name, false, loader)
            val modifier = type(symbols.modifier)
            val action = type(symbols.action)
            val composer = type(symbols.composer)
            lambdaType = type(symbols.lambda)
            thenMethod = modifier.getMethod("then", modifier)
            drawBehind = type(symbols.drawBehind).getDeclaredConstructor(lambdaType).apply { isAccessible = true }
            val fields = action.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
            actionLabel = fields.single { it.type == String::class.java }.apply { isAccessible = true }
            val function0 = action.declaredConstructors.single { it.parameterTypes.size == 2 }.parameterTypes[1]
            actionClick = fields.single { it.type == function0 }.apply { isAccessible = true }
            require(lambdaType.isInterface)
            val testTag = type(symbols.testTagClass).getDeclaredMethod(symbols.testTagMethod, String::class.java, modifier)
            val actionButton = type(symbols.actionButtonClass).declaredMethods.single {
                it.name == symbols.actionButtonMethod && Modifier.isStatic(it.modifiers) && it.parameterTypes.size == 14 &&
                    it.parameterTypes[0] == action && it.parameterTypes[2] == modifier && it.parameterTypes[10] == composer
            }
            val navigationButton = type(symbols.navigationButtonClass).declaredMethods.single {
                it.name == symbols.navigationButtonMethod && Modifier.isStatic(it.modifiers) && it.parameterTypes.size == 10 &&
                    it.parameterTypes[0] == action && it.parameterTypes[1] == modifier && it.parameterTypes[7] == composer
            }

            ctx.xposed.hook(testTag).intercept { chain ->
                val result = chain.proceed()
                val tag = chain.args[0] as? String
                if (tag == null || result == null) result else runCatching { onTag(tag, result) }.getOrDefault(result)
            }
            ctx.xposed.hook(actionButton).intercept { chain ->
                val skip = runCatching {
                    val tag = chain.args[2]?.let { synchronized(tagged) { tagged[it] } }
                    val kind = when (tag) {
                        MENU_TAG -> Kind.MENU
                        SHARE_TAG -> Kind.SHARE
                        else -> null
                    }
                    kind != null && capture(kind, chain.args[0], chain.args[10])
                }.getOrDefault(false)
                if (!skip) runCatching { remember(chain.args[0], chain.args[10]) }
                if (skip) null else chain.proceed()
            }
            ctx.xposed.hook(navigationButton).intercept { chain ->
                val skip = runCatching {
                    val label = chain.args[0]?.let { actionLabel.get(it) as? String }
                    label != null && capture(Kind.BACK, chain.args[0], chain.args[7], label)
                }.getOrDefault(false)
                if (skip) null else chain.proceed()
            }
            symbols.iconClass?.let { iconClass ->
                val icon = type(iconClass).declaredMethods.single {
                    it.name == symbols.iconMethod && Modifier.isStatic(it.modifiers) && it.parameterTypes.size == 9 &&
                        it.parameterTypes[2] == modifier && it.parameterTypes[6] == composer
                }
                ctx.xposed.hook(icon).intercept { chain ->
                    val skip = runCatching { isBackIcon(chain.args[1], chain.args[6]) }.getOrDefault(false)
                    if (skip) null else chain.proceed()
                }
            }
            xposed = ctx.xposed
            ready = true
            Logger.i("Compose: ganchos das páginas instalados")
        }.onFailure { Logger.e("Compose: não foi possível instalar os ganchos das páginas", it) }
    }

    private var xposed: XposedInterface? = null
    private var nodeHook: XposedInterface.HookHandle? = null
    private var rectHook: XposedInterface.HookHandle? = null
    private var overlayDrawing = false

    private var barTop = -1
    private var barHeight = 0
    private var barMinWidth = 0
    private var barWidth = 0
    var toolbarNode: RenderNode? = null
        private set
    var toolbarX = 0f
        private set
    var toolbarY = 0f
        private set
    val currentPlayNode: RenderNode? get() = playNode

    fun configureBar(top: Int, height: Int, width: Int, minWidth: Int) {
        barTop = top
        barHeight = height
        barWidth = width
        barMinWidth = minWidth
    }

    fun beginOverlay() {
        overlayDrawing = true
    }

    fun endOverlay() {
        overlayDrawing = false
    }

    private fun isTopTint(args: List<Any?>): Boolean {
        if (barHeight <= 0 || args[0] != 0f || args[1] != 0f) return false
        val right = args[2] as? Float ?: return false
        val bottom = args[3] as? Float ?: return false
        if (abs(right - barWidth) > EDGE_TOLERANCE || abs(bottom - (barTop + barHeight)) > EDGE_TOLERANCE) return false
        return (args[4] as? Paint)?.shader == null
    }

    private fun checkToolbar(canvas: Canvas, node: RenderNode) {
        @Suppress("DEPRECATION")
        canvas.getMatrix(matrix)
        matrix.getValues(values)
        val y = values[Matrix.MTRANS_Y] + node.top + node.translationY
        if (abs(y - barTop) > 3f) return
        toolbarNode = node
        toolbarX = values[Matrix.MTRANS_X] + node.left + node.translationX
        toolbarY = y
    }

    private fun updateNodeHook() {
        val active = synchronized(pages) { pages.isNotEmpty() }
        if (active && nodeHook == null) {
            nodeHook = xposed?.hook(RecordingCanvas::class.java.getMethod("drawRenderNode", RenderNode::class.java))?.intercept { chain ->
                if (overlayDrawing || BackdropSource.isBackdropCanvas(chain.thisObject)) return@intercept chain.proceed()
                val node = chain.args[0] as? RenderNode
                if (node != null && barHeight > 0 && abs(node.height - barHeight) <= 2 && node.width >= barMinWidth) runCatching { checkToolbar(chain.thisObject as Canvas, node) }
                if (tracking && node != null) links[node]?.let { link -> runCatching { onNodeDrawn(chain.thisObject as Canvas, node, link) } }
                chain.proceed()
            }
            val float = Float::class.javaPrimitiveType
            rectHook = xposed?.hook(RecordingCanvas::class.java.getMethod("drawRect", float, float, float, float, Paint::class.java))?.intercept { chain ->
                if (!overlayDrawing && runCatching { isTopTint(chain.args) }.getOrDefault(false)) null else chain.proceed()
            }
        } else if (!active) {
            nodeHook?.unhook()
            nodeHook = null
            rectHook?.unhook()
            rectHook = null
        }
    }

    val isReady: Boolean get() = ready

    fun register(compose: View): Page = synchronized(pages) {
        pages[compose] ?: Page().also {
            pages[compose] = it
            updateNodeHook()
        }
    }

    fun unregister(compose: View) {
        if (synchronized(pages) { pages.remove(compose) } != null) updateNodeHook()
    }

    fun click(action: Any?): Boolean {
        action ?: return false
        return runCatching {
            action.javaClass.getMethod("invoke").invoke(action)
            true
        }.onFailure { Logger.w("Compose: ação do Spotify falhou", it) }.getOrDefault(false)
    }

    private enum class Kind { BACK, MENU, SHARE }

    @Volatile
    private var backText: String? = null
    private val descriptionFields = HashMap<Class<*>, Field?>()

    private fun isBackIcon(description: Any?, composer: Any?): Boolean {
        description ?: return false
        val wanted = backText ?: run {
            val view = composeOf(composer ?: return false) ?: return false
            val id = view.resources.getIdentifier("back_button_content_description", "string", view.context.packageName)
            if (id == 0) return false
            view.resources.getString(id).also { backText = it }
        }
        val field = descriptionFields.getOrPut(description.javaClass) {
            description.javaClass.declaredFields.firstOrNull { !Modifier.isStatic(it.modifiers) && it.type == String::class.java }?.apply { isAccessible = true }
        } ?: return false
        if (field.get(description) != wanted) return false
        val compose = composeOf(composer ?: return false) ?: return false
        return isListPage(compose)
    }

    private val labeled = WeakHashMap<View, HashMap<String, Any>>()

    private fun remember(action: Any?, composer: Any?) {
        action ?: return
        val label = actionLabel.get(action) as? String ?: return
        val click = actionClick.get(action) ?: return
        val compose = composeOf(composer ?: return) ?: return
        synchronized(labeled) { labeled.getOrPut(compose) { HashMap() }[label] = click }
    }

    fun actionIn(root: View, accept: (String) -> Boolean): Any? = synchronized(labeled) {
        for ((compose, actions) in labeled) {
            if (!compose.isAttachedToWindow || !isInside(compose, root)) continue
            actions.entries.firstOrNull { accept(it.key) }?.let { return@synchronized it.value }
        }
        null
    }

    private fun isInside(view: View, root: View): Boolean {
        var current: Any? = view
        while (current is View) {
            if (current === root) return true
            current = current.parent
        }
        return false
    }

    private fun capture(kind: Kind, action: Any?, composer: Any?, label: String? = null): Boolean {
        val compose = composeOf(composer ?: return false) ?: return false
        if (!isListPage(compose)) return false
        if (kind == Kind.BACK) {
            val id = compose.resources.getIdentifier("back_button_content_description", "string", compose.context.packageName)
            if (id == 0 || label != compose.resources.getString(id)) return false
        }
        val click = action?.let { actionClick.get(it) } ?: return false
        val page = register(compose)
        when (kind) {
            Kind.BACK -> page.back = click
            Kind.MENU -> page.menu = click
            Kind.SHARE -> page.share = click
        }
        return true
    }

    private fun composeOf(composer: Any): View? {
        val path = ownerPaths[composer.javaClass] ?: findPath(composer) { it is View && isComposeOwner(it) }
            ?.also { ownerPaths[composer.javaClass] = it } ?: return null
        var current: Any? = composer
        for (field in path) current = current?.let { field.get(it) }
        val owner = current as? View ?: return null
        return owner.parent as? View
    }

    private fun isListPage(compose: View): Boolean {
        val res = compose.resources
        val pkg = compose.context.packageName
        if (compose.id == 0 || compose.id != res.getIdentifier("compose_view", "id", pkg)) return false
        val root = res.getIdentifier("list_entity_root", "id", pkg)
        var parent = compose.parent
        while (parent is View) {
            if (parent.id == root) return true
            parent = parent.parent
        }
        return false
    }

    private fun isComposeOwner(view: View): Boolean = view.parent?.javaClass?.name == COMPOSE_VIEW

    private fun onTag(tag: String, modifier: Any): Any {
        when {
            tag.startsWith(ITEM_TAG) -> {
                val index = tag.substring(ITEM_TAG.length).trim().toIntOrNull() ?: return modifier
                return thenMethod.invoke(modifier, separatorFor(index)) ?: modifier
            }
            tag == MENU_TAG || tag == SHARE_TAG -> synchronized(tagged) { tagged[modifier] = tag }
            tag == PLAY_TAG -> return thenMethod.invoke(modifier, playProbe()) ?: modifier
        }
        return modifier
    }

    private fun drawLambda(name: String, block: (Any) -> Unit): Any {
        val handler = InvocationHandler { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> name
                else -> {
                    args?.getOrNull(0)?.let { scope -> runCatching { block(scope) } }
                    null
                }
            }
        }
        return drawBehind.newInstance(Proxy.newProxyInstance(lambdaType.classLoader, arrayOf(lambdaType), handler))
    }

    private fun separatorFor(index: Int): Any = separators.getOrPut(index) {
        drawLambda("LiquidSpotSeparator($index)") { drawSeparator(index, it) }
    }

    private fun playProbe(): Any = playProbe ?: drawLambda("LiquidSpotPlayProbe") { probePlay(it) }.also { playProbe = it }

    private val scopeOwnerPaths = HashMap<Class<*>, List<Field>>()

    private fun onListPage(scope: Any): Boolean {
        val path = scopeOwnerPaths[scope.javaClass] ?: findPath(scope) { it is View && isComposeOwner(it) }?.also { scopeOwnerPaths[scope.javaClass] = it } ?: return false
        var current: Any? = scope
        for (field in path) current = current?.let { field.get(it) }
        val compose = (current as? View)?.parent as? View ?: return false
        return synchronized(pages) { pages.containsKey(compose) }
    }

    private fun drawSeparator(index: Int, scope: Any) {
        if (!onListPage(scope)) return
        val (width, height) = sizeOf(scope) ?: return
        val h = height.toInt()
        heights.put(index, h)
        if (h < minRow || h > maxRow) return
        val previous = heights.get(index - 1, -1)
        if (previous < 0 || abs(previous - h) > TOLERANCE) return
        val canvas = canvasOf(scope) ?: return
        canvas.drawLine(inset, 0f, width - inset, 0f, paint)
    }

    private fun probePlay(scope: Any) {
        val canvas = canvasOf(scope) as? RecordingCanvas ?: return
        val node = nodeField?.get(canvas) as? RenderNode ?: return
        if (node === playNode) return
        links.clear()
        links[node] = Link()
        playNode = node
        tracking = true
    }

    private fun onNodeDrawn(canvas: Canvas, node: RenderNode, link: Link) {
        @Suppress("DEPRECATION")
        canvas.getMatrix(matrix)
        matrix.getValues(values)
        link.offset = values[Matrix.MTRANS_Y]
        link.offsetX = values[Matrix.MTRANS_X]
        val parent = nodeField?.get(canvas) as? RenderNode
        link.parent = parent
        if (parent != null && links[parent] == null && links.size < MAX_CHAIN) links[parent] = Link()
    }

    fun playBounds(page: Page): Boolean {
        val play = playNode ?: return false
        if (!tracking) return false
        var y = 0f
        var x = 0f
        var current: RenderNode? = play
        var depth = 0
        while (current != null && depth++ < MAX_CHAIN) {
            val step = links[current] ?: break
            y += step.offset + current.top + current.translationY
            x += step.offsetX + current.left + current.translationX
            current = step.parent
        }
        page.playTop = y
        page.playBottom = y + play.height
        page.playLeft = x
        page.playAt = SystemClock.uptimeMillis()
        return true
    }

    private fun sizeOf(scope: Any): Pair<Float, Float>? {
        val type = scope.javaClass
        val method = sizeMethods[type] ?: run {
            val candidates = allInterfaces(type).filter { it.name == DRAW_SCOPE }
                .flatMap { it.declaredMethods.toList() }
                .filter { it.parameterCount == 0 && it.returnType == Long::class.javaPrimitiveType && !Modifier.isStatic(it.modifiers) }
            candidates.maxByOrNull { decode(it.invoke(scope) as Long).first }?.also { sizeMethods[type] = it }
        } ?: return null
        return decode(method.invoke(scope) as Long)
    }

    private fun decode(packed: Long): Pair<Float, Float> =
        java.lang.Float.intBitsToFloat((packed ushr 32).toInt()) to java.lang.Float.intBitsToFloat((packed and 0xFFFFFFFFL).toInt())

    private fun allInterfaces(type: Class<*>): Set<Class<*>> {
        val out = LinkedHashSet<Class<*>>()
        fun walk(c: Class<*>?) {
            if (c == null) return
            for (i in c.interfaces) if (out.add(i)) walk(i)
            walk(c.superclass)
        }
        walk(type)
        return out
    }

    private fun canvasOf(scope: Any): Canvas? {
        val path = canvasPaths[scope.javaClass] ?: findPath(scope) { it is Canvas }?.also { canvasPaths[scope.javaClass] = it } ?: return null
        var current: Any? = scope
        for (field in path) current = current?.let { field.get(it) }
        return current as? Canvas
    }

    private fun findPath(root: Any, target: (Any) -> Boolean): List<Field>? {
        val queue = ArrayDeque<Pair<Any, List<Field>>>()
        queue.add(root to emptyList())
        val seen = IdentityHashMap<Any, Boolean>()
        while (queue.isNotEmpty()) {
            val (value, path) = queue.removeFirst()
            if (seen.put(value, true) != null || path.size > MAX_DEPTH) continue
            var type: Class<*>? = value.javaClass
            while (type != null && type != Any::class.java) {
                for (field in type.declaredFields) {
                    if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
                    field.isAccessible = true
                    val next = field.get(value) ?: continue
                    if (target(next)) return path + field
                    val name = next.javaClass.name
                    if (name.startsWith("p.") || name.startsWith("androidx.")) queue.add(next to path + field)
                }
                type = type.superclass
            }
        }
        return null
    }

    private const val ITEM_TAG = "list_ux_platform_item_with_index:"
    private const val MENU_TAG = "standard_header_action_context_menu"
    private const val SHARE_TAG = "share.header.action.element.share.button"
    private const val PLAY_TAG = "mixing_play_button"
    private const val COMPOSE_VIEW = "androidx.compose.ui.platform.ComposeView"
    private const val DRAW_SCOPE = "androidx.compose.ui.graphics.drawscope.DrawScope"
    private const val LINE_COLOR = 0x26FFFFFF
    private const val LINE_DP = 0.6f
    private const val INSET_DP = 16f
    private const val MIN_ROW_DP = 44f
    private const val MAX_ROW_DP = 96f
    private const val TOLERANCE = 2
    private const val EDGE_TOLERANCE = 2f
    private const val MAX_DEPTH = 5
    private const val MAX_CHAIN = 40
}
