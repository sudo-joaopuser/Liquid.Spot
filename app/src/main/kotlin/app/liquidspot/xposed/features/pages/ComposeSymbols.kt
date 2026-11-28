package app.liquidspot.xposed.features.pages

import android.content.pm.ApplicationInfo
import android.os.SystemClock
import app.liquidspot.xposed.util.DexReader
import app.liquidspot.xposed.util.Logger
import java.io.File
import java.util.zip.ZipFile

class ComposeSymbols(private val values: Map<String, String>) {
    val modifier get() = values.getValue(MODIFIER)
    val testTagClass get() = values.getValue(TEST_TAG_CLASS)
    val testTagMethod get() = values.getValue(TEST_TAG_METHOD)
    val action get() = values.getValue(ACTION)
    val composer get() = values.getValue(COMPOSER)
    val actionButtonClass get() = values.getValue(ACTION_BUTTON_CLASS)
    val actionButtonMethod get() = values.getValue(ACTION_BUTTON_METHOD)
    val navigationButtonClass get() = values.getValue(NAVIGATION_BUTTON_CLASS)
    val navigationButtonMethod get() = values.getValue(NAVIGATION_BUTTON_METHOD)
    val lambda get() = values.getValue(LAMBDA)
    val drawBehind get() = values.getValue(DRAW_BEHIND)
    val navItem: String? get() = values[NAV_ITEM]
    val iconClass: String? get() = values[ICON_CLASS]
    val iconMethod: String? get() = values[ICON_METHOD]

    private class Search {
        val found = HashMap<String, String>()
        var drawNode: String? = null

        val required: Boolean get() = KEYS.all { it in found }
        val done: Boolean get() = required && NAV_ITEM in found && ICON_CLASS in found
    }

    companion object {
        private const val MODIFIER = "modifier"
        private const val TEST_TAG_CLASS = "testTagClass"
        private const val TEST_TAG_METHOD = "testTagMethod"
        private const val ACTION = "action"
        private const val COMPOSER = "composer"
        private const val ACTION_BUTTON_CLASS = "actionButtonClass"
        private const val ACTION_BUTTON_METHOD = "actionButtonMethod"
        private const val NAVIGATION_BUTTON_CLASS = "navigationButtonClass"
        private const val NAVIGATION_BUTTON_METHOD = "navigationButtonMethod"
        private const val LAMBDA = "lambda"
        private const val DRAW_BEHIND = "drawBehind"
        private const val NAV_ITEM = "navItem"
        private const val ICON_CLASS = "iconClass"
        private const val ICON_METHOD = "iconMethod"
        private const val ICON_PARAMS = 9
        private const val LONG = "J"
        private val KEYS = listOf(
            MODIFIER, TEST_TAG_CLASS, TEST_TAG_METHOD, ACTION, COMPOSER, ACTION_BUTTON_CLASS, ACTION_BUTTON_METHOD,
            NAVIGATION_BUTTON_CLASS, NAVIGATION_BUTTON_METHOD, LAMBDA, DRAW_BEHIND,
        )
        private const val FORMAT = 4
        private const val MENU_TAG = "standard_header_action_context_menu"
        private const val STRING = "Ljava/lang/String;"
        private const val OBJECT = "Ljava/lang/Object;"
        private const val INT = "I"
        private const val VOID = "V"
        private const val ACTION_BUTTON_PARAMS = 14
        private const val NAVIGATION_BUTTON_PARAMS = 10
        private const val NAV_ITEM_PARAMS = 8
        private const val BOOLEAN = "Z"
        private const val DRAW_NODE_UNITS = 9
        private const val ELEMENT_MAX_UNITS = 40
        private const val OP_CONST_STRING = 0x1A
        private const val OP_CONST_STRING_JUMBO = 0x1B
        private const val OP_NEW_INSTANCE = 0x22
        private const val OP_IGET_OBJECT = 0x54
        private const val OP_INVOKE_INTERFACE = 0x72
        private const val OP_RETURN_VOID = 0x0E

        private fun isInvoke(op: Int) = op in 0x6E..0x72 || op in 0x74..0x78

        private fun isStaticInvoke(op: Int) = op == 0x71 || op == 0x77

        private val lock = Any()
        private var loading = false
        private var loaded: ComposeSymbols? = null
        private var failed = false
        private val waiting = ArrayList<(ComposeSymbols) -> Unit>()

        fun whenReady(app: ApplicationInfo, block: (ComposeSymbols) -> Unit) {
            val ready = synchronized(lock) {
                loaded ?: run {
                    if (!failed) waiting += block
                    null
                }
            }
            if (ready != null) {
                block(ready)
                return
            }
            val start = synchronized(lock) { (!loading && !failed).also { if (it) loading = true } }
            if (!start) return
            val cached = runCatching { readCache(cacheFile(app)) }.getOrNull()
            if (cached != null) {
                publish(ComposeSymbols(cached))
            } else {
                Thread({ publish(load(app)) }, "LiquidSpotCompose").apply { priority = Thread.MIN_PRIORITY }.start()
            }
        }

        val isFailed: Boolean get() = synchronized(lock) { failed }

        private fun publish(symbols: ComposeSymbols?) {
            val blocks = synchronized(lock) {
                loading = false
                if (symbols == null) failed = true else loaded = symbols
                waiting.toList().also { waiting.clear() }
            }
            if (symbols != null) blocks.forEach { runCatching { it(symbols) }.onFailure { e -> Logger.e("Compose: erro ao instalar ganchos", e) } }
        }

        private fun cacheFile(app: ApplicationInfo): File {
            val apk = File(app.sourceDir)
            return File(app.dataDir, "cache/liquidspot/compose-${apk.length()}-${apk.lastModified()}-$FORMAT.txt")
        }

        private fun load(app: ApplicationInfo): ComposeSymbols? {
            val apk = File(app.sourceDir)
            val cache = cacheFile(app)
            val started = SystemClock.uptimeMillis()
            val found = runCatching { search(apk) }.onFailure { Logger.e("Compose: falha ao ler o código do Spotify", it) }.getOrNull()
            if (found == null) {
                Logger.w("Compose: classes do Spotify não encontradas pelo formato")
                return null
            }
            Logger.i("Compose: classes do Spotify encontradas em ${SystemClock.uptimeMillis() - started} ms")
            runCatching {
                cache.parentFile?.listFiles()?.filter { it.name.startsWith("compose-") }?.forEach { it.delete() }
                cache.parentFile?.mkdirs()
                cache.writeText(found.entries.joinToString("\n") { "${it.key}=${it.value}" })
            }
            return ComposeSymbols(found)
        }

        private fun readCache(file: File): Map<String, String>? {
            if (!file.isFile) return null
            val values = runCatching {
                file.readLines().mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            }.getOrNull() ?: return null
            return values.takeIf { map -> KEYS.all { it in map } }
        }

        private fun search(apk: File): Map<String, String>? {
            val state = Search()
            ZipFile(apk).use { zip ->
                val entries = zip.entries().toList()
                    .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                    .sortedBy { it.name.removePrefix("classes").removeSuffix(".dex").toIntOrNull() ?: 1 }
                for (entry in entries) {
                    val dex = DexReader(zip.getInputStream(entry).use { it.readBytes() })
                    scan(dex, state)
                    if (state.done) return state.found
                }
                for (entry in entries) {
                    if (state.done || ACTION !in state.found) break
                    findNavigation(DexReader(zip.getInputStream(entry).use { it.readBytes() }), state)
                }
            }
            return state.found.takeIf { state.required }
        }

        private fun scan(dex: DexReader, state: Search) {
            val menu = if (ACTION_BUTTON_CLASS in state.found) -1 else dex.findString(MENU_TAG)
            if (menu >= 0 || state.drawNode == null) {
                dex.forEachCode { code ->
                    if (menu >= 0 && ACTION_BUTTON_CLASS !in state.found) findMenuSite(code, menu, state)
                    if (state.drawNode == null && code.units == DRAW_NODE_UNITS) findDrawNode(code, state)
                }
            }
            val node = state.drawNode
            if (node != null && DRAW_BEHIND !in state.found) {
                dex.forEachCode { code -> if (DRAW_BEHIND !in state.found && code.units <= ELEMENT_MAX_UNITS) findElement(code, node, state) }
            }
            if (ACTION in state.found && (NAVIGATION_BUTTON_CLASS !in state.found || NAV_ITEM !in state.found || ICON_CLASS !in state.found)) findNavigation(dex, state)
        }

        private fun findMenuSite(code: DexReader.Code, menu: Int, state: Search) {
            val dex = code.reader
            var seen = false
            var modifier: String? = null
            code.forEach { op, unit ->
                if (!seen) {
                    if ((op == OP_CONST_STRING && code.index16(unit) == menu) || (op == OP_CONST_STRING_JUMBO && code.index32(unit) == menu)) seen = true
                    return@forEach true
                }
                if (!isInvoke(op)) return@forEach true
                val target = dex.method(code.index16(unit))
                if (modifier == null) {
                    if (isStaticInvoke(op) && target.params.size == 2 && target.params[0] == STRING && target.params[1] == target.returns) {
                        modifier = target.returns
                        state.found[MODIFIER] = DexReader.className(target.returns)
                        state.found[TEST_TAG_CLASS] = DexReader.className(target.owner)
                        state.found[TEST_TAG_METHOD] = target.name
                    }
                    return@forEach true
                }
                if (target.params.size == ACTION_BUTTON_PARAMS && target.params[2] == modifier && target.returns == VOID) {
                    state.found[ACTION] = DexReader.className(target.params[0])
                    state.found[COMPOSER] = DexReader.className(target.params[10])
                    state.found[ACTION_BUTTON_CLASS] = DexReader.className(target.owner)
                    state.found[ACTION_BUTTON_METHOD] = target.name
                    return@forEach false
                }
                true
            }
        }

        private fun findDrawNode(code: DexReader.Code, state: Search) {
            val dex = code.reader
            val ops = IntArray(4)
            val units = IntArray(4)
            var count = 0
            code.forEach { op, unit ->
                if (count < 4) {
                    ops[count] = op
                    units[count] = unit
                }
                count++
                count <= 4
            }
            if (count != 4 || ops[0] != OP_IGET_OBJECT || ops[1] != OP_INVOKE_INTERFACE || ops[2] != OP_INVOKE_INTERFACE || ops[3] != OP_RETURN_VOID) return
            val self = dex.method(code.method)
            if (self.params.size != 1) return
            val lambda = dex.method(code.index16(units[1]))
            val content = dex.method(code.index16(units[2]))
            if (lambda.name != "invoke" || lambda.params != listOf(OBJECT) || lambda.returns != OBJECT) return
            if (content.params.isNotEmpty() || content.returns != VOID || content.owner != self.params[0]) return
            state.drawNode = self.owner
            state.found[LAMBDA] = DexReader.className(lambda.owner)
        }

        private fun findElement(code: DexReader.Code, node: String, state: Search) {
            val dex = code.reader
            code.forEach { op, unit ->
                if (op == OP_NEW_INSTANCE && dex.type(code.index16(unit)) == node) {
                    val owner = dex.method(code.method).owner
                    if (owner != node) state.found[DRAW_BEHIND] = DexReader.className(owner)
                    return@forEach false
                }
                true
            }
        }

        private fun findNavigation(dex: DexReader, state: Search) {
            val action = "L" + state.found.getValue(ACTION).replace('.', '/') + ";"
            val modifier = "L" + state.found.getValue(MODIFIER).replace('.', '/') + ";"
            val composer = "L" + state.found.getValue(COMPOSER).replace('.', '/') + ";"
            for (i in 0 until dex.methodCount) {
                val count = dex.paramCount(i)
                if (count != NAVIGATION_BUTTON_PARAMS && count != NAV_ITEM_PARAMS && count != ICON_PARAMS) continue
                val m = dex.method(i)
                val p = m.params
                if (count == NAVIGATION_BUTTON_PARAMS && p[0] == action && p[1] == modifier && p[7] == composer && p[8] == INT && p[9] == INT && m.returns == VOID) {
                    state.found[NAVIGATION_BUTTON_CLASS] = DexReader.className(m.owner)
                    state.found[NAVIGATION_BUTTON_METHOD] = m.name
                }
                if (count == NAV_ITEM_PARAMS && m.name == "<init>" && p[1] == INT && p[2] == INT && p[4] == INT && p[5] == BOOLEAN && p[6] == BOOLEAN && p[7] == action) {
                    state.found[NAV_ITEM] = DexReader.className(m.owner)
                }
                if (count == ICON_PARAMS && p[2] == modifier && p[3] == LONG && p[4] == LONG && p[5] == BOOLEAN && p[6] == composer && p[7] == INT && p[8] == INT && m.returns == VOID) {
                    state.found[ICON_CLASS] = DexReader.className(m.owner)
                    state.found[ICON_METHOD] = m.name
                }
                if (NAVIGATION_BUTTON_CLASS in state.found && NAV_ITEM in state.found && ICON_CLASS in state.found) return
            }
        }
    }
}
