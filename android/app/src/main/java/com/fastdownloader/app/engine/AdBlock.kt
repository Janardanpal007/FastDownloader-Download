package com.fastdownloader.app.engine

/**
 * Ad and tracker blocking for the in-app browser, using the same filter lists as uBlock Origin
 * (uBlock filters, EasyList, EasyPrivacy, Peter Lowe's list) in the Adblock Plus / uBlock syntax.
 *
 * What is supported:
 *   network rules   ||ads.example^  /banner/ad_  |https://x.com/ad.js|  with options $third-party,
 *                   $1p/$3p, $domain=, resource types ($script, $image, $xhr, $media, $popup…), $important
 *   exceptions      @@rule, and @@||site^$document / $elemhide / $generichide for whole pages
 *   element hiding  ##.ad  example.com##.banner  example.*##.ad  #@# exceptions, and the :has() / :not() CSS
 *   hosts files     0.0.0.0 ads.example.com
 * Rules needing uBlock's own runtime (scriptlets ##+js, procedural :has-text() etc., $removeparam,
 * $csp, $redirect-rule, regex patterns) are skipped, so nothing half-works.
 *
 * Plain Kotlin (no Android classes), so it can be unit-tested on the PC.
 */
class AdBlock private constructor() {

    /** Resource type of a request, for rules like $script or $image. */
    enum class Type(val bit: Int) {
        DOCUMENT(1), SUBDOCUMENT(2), SCRIPT(4), STYLESHEET(8), IMAGE(16), MEDIA(32), FONT(64),
        XHR(128), WEBSOCKET(256), PING(512), POPUP(1024), OTHER(2048), OBJECT(4096),
    }

    private class NetRule(
        val pattern: String,
        val types: Int, // 0 = every type except POPUP and DOCUMENT
        val notTypes: Int,
        val party: Int, // 0 any, 1 third-party only, 2 first-party only
        val domains: Array<String>?,
        val notDomains: Array<String>?,
        val important: Boolean,
    ) {
        private var compiled: Regex? = null
        private val pureHost = pattern.isEmpty() // matched by host index alone

        fun matchesUrl(url: String): Boolean {
            if (pureHost) return true
            val re = compiled ?: toRegex(pattern).also { compiled = it }
            return re.containsMatchIn(url)
        }

        fun matchesContext(type: Type, third: Boolean, pageHost: String): Boolean {
            if (types != 0) { if (types and type.bit == 0) return false }
            else if (type == Type.POPUP || type == Type.DOCUMENT) return false
            if (notTypes and type.bit != 0) return false
            if (party == 1 && !third) return false
            if (party == 2 && third) return false
            if (notDomains != null && notDomains.any { hostIn(pageHost, it) }) return false
            if (domains != null && domains.none { hostIn(pageHost, it) }) return false
            return true
        }
    }

    // Network rules: plain "||host^" rules by host; the rest by their most distinctive word.
    private val blockHosts = HashMap<String, MutableList<NetRule>>()
    private val allowHosts = HashMap<String, MutableList<NetRule>>()
    private val blockTokens = HashMap<String, MutableList<NetRule>>()
    private val allowTokens = HashMap<String, MutableList<NetRule>>()

    // Whole-page exceptions (@@…$document, $elemhide, $generichide).
    private val docAllow = mutableListOf<Pair<NetRule, Int>>()

    // Element hiding.
    private val genericByToken = HashMap<String, MutableList<String>>() // ".ad" / "#banner" → selectors
    private val genericHigh = mutableListOf<String>() // generic selectors not starting with an id or class
    private val genericAllow = HashSet<String>()
    private val specific = HashMap<String, MutableList<String>>() // host or "name.*" → selectors
    private val specificAllow = HashMap<String, MutableSet<String>>()

    var networkRules = 0
        private set
    var cosmeticRules = 0
        private set

    // ---------------- matching ----------------

    /** True if the request should be blocked. [pageUrl] is the page the request comes from. */
    fun shouldBlock(url: String, pageUrl: String, type: Type): Boolean {
        val u = url.lowercase()
        val host = hostOf(u) ?: return false
        val pageHost = hostOf(pageUrl.lowercase()) ?: host
        if (pageFlags(pageUrl) and FLAG_DOCUMENT != 0) return false
        val third = baseDomain(host) != baseDomain(pageHost)
        val block = find(blockHosts, blockTokens, u, host, type, third, pageHost) ?: return false
        if (block.important) return true
        return find(allowHosts, allowTokens, u, host, type, third, pageHost) == null
    }

    private fun find(
        hosts: Map<String, List<NetRule>>, tokens: Map<String, List<NetRule>>,
        url: String, host: String, type: Type, third: Boolean, pageHost: String,
    ): NetRule? {
        var h = host
        while (true) {
            hosts[h]?.firstOrNull { it.matchesContext(type, third, pageHost) && it.matchesUrl(url) }?.let { return it }
            val dot = h.indexOf('.')
            if (dot < 0) break
            h = h.substring(dot + 1)
        }
        for (t in urlTokens(url)) {
            tokens[t]?.firstOrNull { it.matchesContext(type, third, pageHost) && it.matchesUrl(url) }?.let { return it }
        }
        tokens[""]?.firstOrNull { it.matchesContext(type, third, pageHost) && it.matchesUrl(url) }?.let { return it }
        return null
    }

    @Volatile private var lastPage: Pair<String, Int>? = null

    /** FLAG_DOCUMENT: no blocking at all on this page; FLAG_ELEMHIDE / FLAG_GENERICHIDE: less element hiding. */
    fun pageFlags(pageUrl: String): Int {
        lastPage?.let { if (it.first == pageUrl) return it.second }
        val u = pageUrl.lowercase()
        val host = hostOf(u) ?: return 0
        var flags = 0
        for ((rule, f) in docAllow) {
            if (flags and f == f) continue
            if (rule.matchesUrl(u) && rule.matchesContext(Type.OTHER, false, host)) flags = flags or f
        }
        lastPage = pageUrl to flags
        return flags
    }

    /** Site-specific hiding plus generic rules that don't depend on ids or classes. Newline-separated. */
    fun cosmeticBase(pageUrl: String): String {
        val flags = pageFlags(pageUrl)
        if (flags and (FLAG_DOCUMENT or FLAG_ELEMHIDE) != 0) return ""
        val host = hostOf(pageUrl.lowercase()) ?: return ""
        val out = LinkedHashSet<String>()
        val allowed = HashSet<String>()
        for (key in specificKeys(host)) {
            specific[key]?.let { out.addAll(it) }
            specificAllow[key]?.let { allowed.addAll(it) }
        }
        if (flags and FLAG_GENERICHIDE == 0) out.addAll(genericHigh)
        out.removeAll(allowed)
        out.removeAll(genericAllow)
        return out.joinToString("\n")
    }

    /** Generic rules for the ids and classes found on a page ("#main .ad-box …"). Newline-separated. */
    fun cosmeticFor(pageUrl: String, idsAndClasses: String): String {
        val flags = pageFlags(pageUrl)
        if (flags and (FLAG_DOCUMENT or FLAG_ELEMHIDE or FLAG_GENERICHIDE) != 0) return ""
        val host = hostOf(pageUrl.lowercase()) ?: return ""
        val allowed = HashSet<String>()
        for (key in specificKeys(host)) specificAllow[key]?.let { allowed.addAll(it) }
        val out = LinkedHashSet<String>()
        for (t in idsAndClasses.split(' ')) {
            if (t.length < 2) continue
            genericByToken[t]?.forEach { if (it !in allowed && it !in genericAllow) out.add(it) }
        }
        return out.joinToString("\n")
    }

    /** "www.news.example.co.uk" → itself, its parent domains, and "example.*" for entity rules. */
    private fun specificKeys(host: String): List<String> {
        val keys = mutableListOf<String>()
        var h = host
        while (true) {
            keys += h
            val dot = h.indexOf('.')
            if (dot < 0) break
            h = h.substring(dot + 1)
        }
        val base = baseDomain(host)
        val suffix = base.substringAfter('.')
        val name = base.substringBefore('.')
        keys += "$name.*"
        if (host != base) keys += host.removeSuffix(".$suffix") + ".*"
        return keys
    }

    // ---------------- parsing ----------------

    private fun addLine(raw: String) {
        val line = raw.trim()
        if (line.isEmpty() || line[0] == '!' || line[0] == '[') return
        // Hosts-file lines (Peter Lowe's list): "0.0.0.0 ads.example.com".
        if (line[0] == '#' && !line.startsWith("##") && !line.startsWith("#@#")) return
        if (line.startsWith("127.0.0.1 ") || line.startsWith("0.0.0.0 ")) {
            val h = line.substringAfter(' ').trim().substringBefore(' ').lowercase()
            if (h.isNotEmpty() && h != "localhost" && h.contains('.')) addNet("||$h^")
            return
        }
        // Scriptlets, procedural and HTML filters (#?# #$# #%# ##+js ##^) need uBlock's own runtime.
        if (UNSUPPORTED_MARKERS.any { line.contains(it) }) return
        val cosmetic = cosmeticSplit(line)
        if (cosmetic != null) addCosmetic(cosmetic.first, cosmetic.second, cosmetic.third)
        else addNet(line)
    }

    /** "domains##selector" → (domains, selector, isException), or null for network rules. */
    private fun cosmeticSplit(line: String): Triple<String, String, Boolean>? {
        val i = line.indexOf('#')
        if (i < 0) return null
        if (line.startsWith("##", i) && !line.startsWith("##+", i) && !line.startsWith("##^", i)) {
            return Triple(line.substring(0, i), line.substring(i + 2), false)
        }
        if (line.startsWith("#@#", i) && !line.startsWith("#@#+", i) && !line.startsWith("#@#^", i)) {
            return Triple(line.substring(0, i), line.substring(i + 3), true)
        }
        return null
    }

    private fun addCosmetic(domainsPart: String, selector: String, exception: Boolean) {
        if (selector.isEmpty() || UNSUPPORTED_CSS.containsMatchIn(selector)) return
        val sel = selector.trim()
        val domains = domainsPart.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val positive = domains.filter { !it.startsWith('~') }
        if (exception) {
            if (positive.isEmpty()) genericAllow += sel
            else positive.forEach { specificAllow.getOrPut(it) { HashSet() } += sel }
            return
        }
        cosmeticRules++
        if (positive.isEmpty()) {
            // Generic rule (domains with only "~x" exclusions are treated as generic minus those sites).
            domains.filter { it.startsWith('~') }.forEach { specificAllow.getOrPut(it.substring(1)) { HashSet() } += sel }
            val key = genericKey(sel)
            if (key != null) genericByToken.getOrPut(key) { ArrayList(1) } += sel else genericHigh += sel
        } else {
            positive.forEach { specific.getOrPut(it) { ArrayList(2) } += sel }
            domains.filter { it.startsWith('~') }.forEach { specificAllow.getOrPut(it.substring(1)) { HashSet() } += sel }
        }
    }

    /** ".ad-box > img" → ".ad-box"; "#banner" → "#banner"; anything else → null. */
    private fun genericKey(sel: String): String? {
        if (sel.length < 2 || (sel[0] != '.' && sel[0] != '#')) return null
        var end = 1
        while (end < sel.length && (sel[end].isLetterOrDigit() || sel[end] == '-' || sel[end] == '_')) end++
        if (end == 1) return null
        if (end < sel.length && sel[end] == '\\') return null
        return sel.substring(0, end)
    }

    private fun addNet(rule: String) {
        var r = rule
        val exception = r.startsWith("@@")
        if (exception) r = r.substring(2)
        var opts = ""
        val dollar = r.lastIndexOf('$')
        if (dollar > 0 && OPTIONS.matches(r.substring(dollar + 1))) {
            opts = r.substring(dollar + 1)
            r = r.substring(0, dollar)
        }
        if (r.length > 1 && r.startsWith('/') && r.endsWith('/')) return // regex rules: skipped
        var types = 0
        var notTypes = 0
        var party = 0
        var domains: Array<String>? = null
        var notDomains: Array<String>? = null
        var important = false
        var pageFlag = 0
        if (opts.isNotEmpty()) {
            for (o in opts.lowercase().split(',')) {
                val neg = o.startsWith('~')
                val name = o.removePrefix("~").substringBefore('=')
                val type = TYPES[name]
                when {
                    type != null -> if (neg) notTypes = notTypes or type.bit else types = types or type.bit
                    name == "third-party" || name == "3p" || name == "strict3p" -> party = if (neg) 2 else 1
                    name == "first-party" || name == "1p" || name == "strict1p" -> party = if (neg) 1 else 2
                    name == "domain" || name == "from" -> {
                        val list = o.substringAfter('=').split('|').filter { it.isNotEmpty() }
                        domains = list.filter { !it.startsWith('~') }.toTypedArray().takeIf { it.isNotEmpty() }
                        notDomains = list.filter { it.startsWith('~') }.map { it.substring(1) }.toTypedArray().takeIf { it.isNotEmpty() }
                    }
                    name == "important" -> important = true
                    name == "all" -> types = 0
                    name == "redirect" -> Unit // block instead of redirecting
                    name == "document" || name == "doc" -> if (exception) pageFlag = pageFlag or FLAG_DOCUMENT else types = types or Type.DOCUMENT.bit
                    name == "elemhide" || name == "ehide" -> if (exception) pageFlag = pageFlag or FLAG_ELEMHIDE else return
                    name == "generichide" || name == "ghide" -> if (exception) pageFlag = pageFlag or FLAG_GENERICHIDE else return
                    else -> return // $removeparam, $csp, $badfilter, $match-case…: not supported, skip the rule
                }
            }
        }
        var pattern = r.lowercase()
        if (pattern == "*" || pattern.isEmpty()) {
            if (pageFlag == 0 && domains == null) return // would block everything
            pattern = "*"
        }
        val netRule = NetRule(pattern.takeUnless { isPureHost(it) } ?: "", types, notTypes, party, domains, notDomains, important)
        if (pageFlag != 0) {
            docAllow += NetRule(pattern, 0, 0, 0, domains, notDomains, false) to pageFlag
            if (types == 0 && pageFlag and FLAG_DOCUMENT == 0) return
        }
        networkRules++
        if (isPureHost(pattern)) {
            val host = pattern.removePrefix("||").removeSuffix("^").removeSuffix("^|")
            (if (exception) allowHosts else blockHosts).getOrPut(host) { ArrayList(1) } += netRule
            return
        }
        val withHost = if (pattern.startsWith("||")) hostPart(pattern) else null
        if (withHost != null) {
            (if (exception) allowHosts else blockHosts).getOrPut(withHost) { ArrayList(1) } +=
                NetRule(pattern, types, notTypes, party, domains, notDomains, important)
            return
        }
        (if (exception) allowTokens else blockTokens).getOrPut(bestToken(pattern)) { ArrayList(1) } +=
            NetRule(pattern, types, notTypes, party, domains, notDomains, important)
    }

    private fun isPureHost(p: String) = p.startsWith("||") && HOST_ONLY.matches(p.substring(2))

    /** "||ads.example.com/banner" → "ads.example.com" (only when the host part has no wildcards). */
    private fun hostPart(p: String): String? {
        val rest = p.substring(2)
        val end = rest.indexOfFirst { it == '/' || it == '^' || it == '*' || it == '|' || it == ':' || it == '?' }
        val host = if (end < 0) rest else rest.substring(0, end)
        if (host.isEmpty() || !host.contains('.') || (end >= 0 && rest[end] == '*')) return null
        return host
    }

    /** The longest word in the pattern that also appears as a whole word in matching URLs. */
    private fun bestToken(p: String): String {
        var best = ""
        var i = 0
        while (i < p.length) {
            if (!isTokenChar(p[i])) { i++; continue }
            val start = i
            while (i < p.length && isTokenChar(p[i])) i++
            val before = if (start == 0) null else p[start - 1]
            val after = if (i >= p.length) null else p[i]
            // A word next to '*', or at an unanchored end, may be part of a longer word in the URL.
            val okBefore = before != null && before != '*'
            val okAfter = after != null && after != '*'
            if (okBefore && okAfter && i - start > best.length && i - start >= 3) best = p.substring(start, i)
        }
        return best
    }

    companion object {
        const val FLAG_DOCUMENT = 1
        const val FLAG_ELEMHIDE = 2
        const val FLAG_GENERICHIDE = 4

        private val UNSUPPORTED_MARKERS = listOf("#?#", "#$#", "#%#", "#@?#", "#@$#", "#@%#", "##+js", "#@#+js", "##^", "#@#^")
        private val OPTIONS = Regex("""[\w~,=|.\-:*+/]+""")
        private val HOST_ONLY = Regex("""[a-z0-9.\-_]+\^?\|?""")
        private val UNSUPPORTED_CSS = Regex(
            """:(has-text|-abp-|xpath|matches-css|upward|remove|style|matches-path|min-text-length|watch-attr|others|""" +
                """matches-attr|if|if-not|nth-ancestor|matches-prop|matches-media|shadow|remove-attr|remove-class)\b|^\+js|^\^""",
        )
        private val TYPES = mapOf(
            "script" to Type.SCRIPT, "image" to Type.IMAGE, "stylesheet" to Type.STYLESHEET, "css" to Type.STYLESHEET,
            "xmlhttprequest" to Type.XHR, "xhr" to Type.XHR, "subdocument" to Type.SUBDOCUMENT, "frame" to Type.SUBDOCUMENT,
            "media" to Type.MEDIA, "font" to Type.FONT, "websocket" to Type.WEBSOCKET, "ping" to Type.PING,
            "beacon" to Type.PING, "other" to Type.OTHER, "object" to Type.OBJECT, "popup" to Type.POPUP,
        )
        // Two-part public suffixes common enough to matter for "same site" checks.
        private val TWO_PART = setOf(
            "co.uk", "org.uk", "ac.uk", "gov.uk", "com.au", "net.au", "org.au", "co.in", "net.in", "org.in", "gov.in",
            "co.jp", "ne.jp", "or.jp", "com.br", "com.cn", "com.mx", "co.za", "com.tr", "co.kr", "com.sg", "com.hk",
            "com.tw", "co.id", "com.my", "com.ph", "com.pk", "com.ar", "co.nz", "com.vn", "com.ua", "com.eg", "com.sa",
            "github.io", "blogspot.com", "appspot.com", "herokuapp.com", "netlify.app", "vercel.app", "pages.dev",
        )

        private fun isTokenChar(c: Char) = c in 'a'..'z' || c in '0'..'9' || c == '%'

        fun urlTokens(url: String): Set<String> {
            val out = HashSet<String>()
            var i = 0
            while (i < url.length) {
                if (!isTokenChar(url[i])) { i++; continue }
                val s = i
                while (i < url.length && isTokenChar(url[i])) i++
                if (i - s >= 3) out += url.substring(s, i)
            }
            return out
        }

        fun hostOf(url: String): String? {
            val start = url.indexOf("://")
            if (start < 0) return null
            var h = url.substring(start + 3)
            val end = h.indexOfFirst { it == '/' || it == '?' || it == '#' }
            if (end >= 0) h = h.substring(0, end)
            h = h.substringAfterLast('@')
            if (h.startsWith('[')) return h.substringBefore(']') + "]"
            return h.substringBefore(':').trimEnd('.').ifEmpty { null }
        }

        fun baseDomain(host: String): String {
            val parts = host.split('.')
            if (parts.size <= 2) return host
            val lastTwo = parts.takeLast(2).joinToString(".")
            return if (lastTwo in TWO_PART) parts.takeLast(3).joinToString(".") else lastTwo
        }

        private fun hostIn(host: String, domain: String): Boolean {
            if (domain.endsWith(".*")) {
                val name = domain.removeSuffix(".*")
                return baseDomain(host).substringBefore('.') == name || host.startsWith("$name.") || host.contains(".$name.")
            }
            return host == domain || host.endsWith(".$domain")
        }

        /** Adblock pattern → regular expression. */
        fun toRegex(p: String): Regex {
            val sb = StringBuilder()
            var s = p
            when {
                s.startsWith("||") -> { sb.append("^[a-z][a-z0-9+.-]*://(?:[^/?#]*\\.)?"); s = s.substring(2) }
                s.startsWith("|") -> { sb.append('^'); s = s.substring(1) }
            }
            val endAnchor = s.endsWith("|")
            if (endAnchor) s = s.dropLast(1)
            for (c in s) when (c) {
                '*' -> sb.append(".*")
                '^' -> sb.append("(?:[^a-z0-9_.%-]|$)")
                else -> if (c.isLetterOrDigit()) sb.append(c) else sb.append('\\').append(c)
            }
            if (endAnchor) sb.append('$')
            return Regex(sb.toString())
        }

        /** Builds the blocker from the text of one or more filter lists. */
        fun parse(lists: List<String>): AdBlock {
            val ab = AdBlock()
            for (text in lists) text.lineSequence().forEach { runCatching { ab.addLine(it) } }
            return ab
        }
    }
}
