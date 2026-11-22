package io.anytype.apibridge

import android.os.Parcel
import android.os.Parcelable

/**
 * Reply of [IApiBridge.connect]. [code] is [IApiBridge.OK], with [baseUrl] and [apiKey] set,
 * or one of the error codes on [IApiBridge].
 */
class ApiEndpoint(
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
        val CREATOR: Parcelable.Creator<ApiEndpoint> = object : Parcelable.Creator<ApiEndpoint> {
            override fun createFromParcel(source: Parcel): ApiEndpoint =
                ApiEndpoint(source.readInt(), source.readString(), source.readString())

            override fun newArray(size: Int): Array<ApiEndpoint?> = arrayOfNulls(size)
        }
    }
}
