package ru.eventpass.data.fake.product

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Historical schema 5 appends profiles to unchanged schema-4 product payload. */
internal object LegacyV5Fixture {
    fun encode(state: FakeProductState): ByteArray {
        val v4=LegacyV4Fixture.encode(state)
        return ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { out ->
            out.writeInt(5);out.write(v4,4,v4.size-4)
            out.writeInt(state.profileDetails.size)
            state.profileDetails.forEach { (id,p) -> out.writeUTF(id.value);out.writeUTF(p.phone.orEmpty());out.writeUTF(p.organization.orEmpty());out.writeBoolean(p.notifications) }
        } }.toByteArray()
    }
}
