// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.model

/** Bound parser recursion/allocation before Android's JSONTokener runs, not
 * after it has already built an untrusted object tree. Quoted/escaped text is
 * not counted as structure. JSON syntax remains the JSON parser's job. */
internal fun requireNativeJsonBudget(raw: String, code: ModelBrokerCode) {
    var quoted = false
    var escaped = false
    var depth = 0
    var structure = 0
    for (ch in raw) {
        if (quoted) {
            if (escaped) escaped = false
            else if (ch == '\\') escaped = true
            else if (ch == '"') quoted = false
            continue
        }
        when (ch) {
            '"' -> quoted = true
            '{', '[' -> { depth++; structure++ }
            '}', ']' -> { depth--; structure++ }
            ',', ':' -> structure++
        }
        if (depth < 0 || depth > 64 || structure > 16384) throw ModelBrokerFailure(code)
    }
}
