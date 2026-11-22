package io.anytype.agentbridge

import android.os.Parcel
import android.os.Parcelable

/**
 * Reply of [IAgentBridge.connect]. [code] is [IAgentBridge.OK], with [baseUrl] and [apiKey] set,
 * or one of the error codes on [IAgentBridge].
 */
class AgentEndpoint(
    val code: Int,
    val baseUrl: String?,
    val apiKey: String?
) : Parcelable {

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(code)
        dest.writeString(baseUrl)
        dest.writeString(apiKey)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<AgentEndpoint> = object : Parcelable.Creator<AgentEndpoint> {
            override fun createFromParcel(source: Parcel): AgentEndpoint =
                AgentEndpoint(source.readInt(), source.readString(), source.readString())

            override fun newArray(size: Int): Array<AgentEndpoint?> = arrayOfNulls(size)
        }
    }
}
