// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

/**
 * Wire protocol between the isolated Agent process and the main-process broker.
 *
 * Pure Kotlin: the Android glue (Messenger/Bundle) lives in `BrokerCodec.kt`, so the
 * protocol rules stay unit-testable on the host JVM.
 *
 * Security note: there is deliberately **no credential operation** here. The isolated UID has
 * no network access and no app permissions, and the broker never forwards an API key, so an
 * Agent running in the isolated process cannot obtain the provider credential.
 */
enum class BrokerOp(val wire: String) {
    READ_FILE("read_file"),
    WRITE_FILE("write_file"),
    LIST_DIR("list_dir");

    companion object {
        /** Unknown wire names are refused, so a request for a credential is not routable. */
        fun fromWire(name: String?): BrokerOp? = values().firstOrNull { it.wire == name }
    }
}

/** A request the isolated side may send to the broker. */
data class BrokerRequest(
    val op: BrokerOp,
    val path: String,
    val text: String? = null,
)

/** The broker's answer. Never carries credentials or absolute paths outside the workspace. */
data class BrokerResponse(
    val ok: Boolean,
    val code: String,
    val text: String? = null,
    val entries: List<String>? = null,
) {
    companion object {
        const val OK = "OK"
        const val NOT_FOUND = "NOT_FOUND"
        const val NOT_A_FILE = "NOT_A_FILE"
        const val NOT_A_DIRECTORY = "NOT_A_DIRECTORY"
        const val TOO_LARGE = "TOO_LARGE"
        const val IO_ERROR = "IO_ERROR"
        const val UNSUPPORTED_OP = "UNSUPPORTED_OP"
        const val BROKER_UNAVAILABLE = "BROKER_UNAVAILABLE"
        const val UNAUTHORIZED = "UNAUTHORIZED"
        const val BUSY = "BUSY"
        const val TIMEOUT = "TIMEOUT"
        const val OUTCOME_UNKNOWN = "OUTCOME_UNKNOWN"

        fun error(code: String) = BrokerResponse(ok = false, code = code)
        fun text(value: String) = BrokerResponse(ok = true, code = OK, text = value)
        fun listing(entries: List<String>) = BrokerResponse(ok = true, code = OK, entries = entries)
    }
}
