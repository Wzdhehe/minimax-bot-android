// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

import android.os.Bundle

/** Message codes and Bundle keys shared by the broker and the isolated Agent process. */
object IsolationWire {
    const val MSG_INIT = 1
    const val MSG_READY = 2
    const val MSG_TASK = 3
    const val MSG_BROKER_REQUEST = 4
    const val MSG_BROKER_RESPONSE = 5
    const val MSG_TASK_RESULT = 6
    const val MSG_REGISTER = 7
    const val MSG_REGISTERED = 8
    const val MSG_REVOKE = 9

    const val KEY_OP = "op"
    const val KEY_PATH = "path"
    const val KEY_TEXT = "text"
    const val KEY_ID = "id"
    const val KEY_OK = "ok"
    const val KEY_CODE = "code"
    const val KEY_ENTRIES = "entries"
    const val KEY_BROKER = "broker"
    const val KEY_SESSION = "session"
    const val KEY_AGENT_UID = "agent_uid"
}

/**
 * Bundle encoding for the broker protocol. Only the known keys are read, so a request
 * carrying extra fields (a credential, for instance) simply drops them instead of
 * forwarding anything to the broker.
 */
object BrokerCodec {

    fun decodeRequest(bundle: Bundle?): BrokerRequest? {
        if (bundle == null) return null
        val op = BrokerOp.fromWire(bundle.getString(IsolationWire.KEY_OP)) ?: return null
        return BrokerRequest(
            op = op,
            path = bundle.getString(IsolationWire.KEY_PATH) ?: "",
            text = bundle.getString(IsolationWire.KEY_TEXT),
        )
    }

    fun encodeResponse(response: BrokerResponse): Bundle = Bundle().apply {
        putBoolean(IsolationWire.KEY_OK, response.ok)
        putString(IsolationWire.KEY_CODE, response.code)
        putString(IsolationWire.KEY_TEXT, response.text)
        putStringArrayList(
            IsolationWire.KEY_ENTRIES,
            ArrayList(response.entries ?: emptyList()),
        )
    }

    fun decodeResponse(bundle: Bundle?): BrokerResponse {
        if (bundle == null) return BrokerResponse.error(BrokerResponse.IO_ERROR)
        val entries = bundle.getStringArrayList(IsolationWire.KEY_ENTRIES)
        return BrokerResponse(
            ok = bundle.getBoolean(IsolationWire.KEY_OK, false),
            code = bundle.getString(IsolationWire.KEY_CODE) ?: BrokerResponse.IO_ERROR,
            text = bundle.getString(IsolationWire.KEY_TEXT),
            entries = entries?.toList(),
        )
    }
}
