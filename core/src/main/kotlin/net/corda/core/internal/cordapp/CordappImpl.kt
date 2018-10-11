package net.corda.core.internal.cordapp

import net.corda.core.DeleteForDJVM
import net.corda.core.cordapp.Cordapp
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FlowLogic
import net.corda.core.internal.notary.NotaryService
import net.corda.core.internal.toPath
import net.corda.core.schemas.MappedSchema
import net.corda.core.serialization.SerializationCustomSerializer
import net.corda.core.serialization.SerializationWhitelist
import net.corda.core.serialization.SerializeAsToken
import java.net.URL

@DeleteForDJVM
data class CordappImpl(
        override val contractClassNames: List<String>,
        override val initiatedFlows: List<Class<out FlowLogic<*>>>,
        override val rpcFlows: List<Class<out FlowLogic<*>>>,
        override val serviceFlows: List<Class<out FlowLogic<*>>>,
        override val schedulableFlows: List<Class<out FlowLogic<*>>>,
        override val services: List<Class<out SerializeAsToken>>,
        override val serializationWhitelists: List<SerializationWhitelist>,
        override val serializationCustomSerializers: List<SerializationCustomSerializer<*, *>>,
        override val customSchemas: Set<MappedSchema>,
        override val allFlows: List<Class<out FlowLogic<*>>>,
        override val jarPath: URL,
        val info: Info,
        override val jarHash: SecureHash.SHA256,
        override val notaryService: Class<out NotaryService>? = null,
        /** Indicates whether the CorDapp is loaded from external sources, or generated on node startup (virtual). */
        val isLoaded: Boolean = true) : Cordapp {
    override val name: String = jarName(jarPath)

    companion object {
        fun jarName(url: URL): String = url.toPath().fileName.toString().removeSuffix(".jar")
    }

    /**
     * An exhaustive list of all classes relevant to the node within this CorDapp
     *
     * TODO: Also add [SchedulableFlow] as a Cordapp class
     */
    override val cordappClasses: List<String> = run {
        val classList = rpcFlows + initiatedFlows + services + serializationWhitelists.map { javaClass } + notaryService
         classList.mapNotNull { it?.name } + contractClassNames
    }

    // TODO Why a seperate Info class and not just have the fields directly in CordappImpl?
    data class Info(val shortName: String, val vendor: String, val version: String, val minimumPlatformVersion: Int, val targetPlatformVersion: Int) {
        companion object {
            const val UNKNOWN_VALUE = "Unknown"
            val UNKNOWN = Info(UNKNOWN_VALUE, UNKNOWN_VALUE, UNKNOWN_VALUE, 1, 1)
        }

        fun hasUnknownFields(): Boolean = arrayOf(shortName, vendor, version).any { it == UNKNOWN_VALUE }
    }
}