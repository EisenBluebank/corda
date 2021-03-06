package net.corda.demobench.model

import javafx.beans.binding.IntegerExpression
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.copyToDirectory
import net.corda.core.internal.createDirectories
import net.corda.core.internal.div
import net.corda.core.internal.noneOrSingle
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.demobench.plugin.CordappController
import net.corda.demobench.pty.R3Pty
import tornadofx.*
import java.io.IOException
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level

class NodeController(check: atRuntime = ::checkExists) : Controller() {
    companion object {
        const val firstPort = 10000
        const val minPort = 1024
        const val maxPort = 65535
    }

    private val jvm by inject<JVMConfig>()
    private val cordappController by inject<CordappController>()
    private val nodeInfoFilesCopier by inject<NodeInfoFilesCopier>()

    private var baseDir: Path = baseDirFor(ManagementFactory.getRuntimeMXBean().startTime)
    private val cordaPath: Path = jvm.applicationDir.resolve("corda").resolve("corda.jar")
    private val command = jvm.commandFor(cordaPath).toTypedArray()

    private val nodes = LinkedHashMap<String, NodeConfigWrapper>()
    private val port = AtomicInteger(firstPort)

    private var networkMapConfig: NetworkMapConfig? = null

    val activeNodes: List<NodeConfigWrapper>
        get() = nodes.values.filter {
            (it.state == NodeState.RUNNING) || (it.state == NodeState.STARTING)
        }

    init {
        log.info("Base directory: $baseDir")
        log.info("Corda JAR: $cordaPath")

        // Check that the Corda capsule is available.
        // We do NOT want to do this during unit testing!
        check(cordaPath, "Cannot find Corda JAR.")
    }

    /**
     * Validate a Node configuration provided by [net.corda.demobench.views.NodeTabView].
     */
    fun validate(nodeData: NodeData): NodeConfigWrapper? {
        fun IntegerExpression.toLocalAddress() = NetworkHostAndPort("localhost", value)

        val location = nodeData.nearestCity.value
        val nodeConfig = NodeConfig(
                myLegalName = CordaX500Name(
                        organisation = nodeData.legalName.value.trim(),
                        locality = location.description,
                        country = location.countryCode
                ),
                p2pAddress = nodeData.p2pPort.toLocalAddress(),
                rpcAddress = nodeData.rpcPort.toLocalAddress(),
                webAddress = nodeData.webPort.toLocalAddress(),
                notary = nodeData.extraServices.filterIsInstance<NotaryService>().noneOrSingle(),
                networkMapService = networkMapConfig,  // The first node becomes the network map
                h2port = nodeData.h2Port.value,
                issuableCurrencies = nodeData.extraServices.filterIsInstance<CurrencyIssuer>().map { it.currency.toString() }
        )

        val wrapper = NodeConfigWrapper(baseDir, nodeConfig)

        if (nodes.putIfAbsent(wrapper.key, wrapper) != null) {
            log.warning("Node with key '${wrapper.key}' already exists.")
            return null
        }

        if (nodeConfig.isNetworkMap) {
            networkMapConfig = nodeConfig.let { NetworkMapConfig(it.myLegalName, it.p2pAddress) }
            log.info("Network map provided by: ${nodeConfig.myLegalName}")
        }

        nodeInfoFilesCopier.addConfig(wrapper)

        return wrapper
    }

    fun dispose(config: NodeConfigWrapper) {
        config.state = NodeState.DEAD

        nodeInfoFilesCopier.removeConfig(config)

        if (config.nodeConfig.isNetworkMap) {
            log.warning("Network map service (Node '${config.nodeConfig.myLegalName}') has exited.")
        }
    }

    val nextPort: Int get() = port.andIncrement

    fun isPortValid(port: Int) = (port >= minPort) && (port <= maxPort)

    fun keyExists(key: String) = nodes.keys.contains(key)

    fun nameExists(name: String) = keyExists(name.toKey())

    fun hasNetworkMap(): Boolean = networkMapConfig != null

    fun runCorda(pty: R3Pty, config: NodeConfigWrapper): Boolean {
        try {
            config.nodeDir.createDirectories()

            // Install any built-in plugins into the working directory.
            cordappController.populate(config)

            // Write this node's configuration file into its working directory.
            val confFile = config.nodeDir / "node.conf"
            Files.write(confFile, config.nodeConfig.toText().toByteArray())

            // Execute the Corda node
            val cordaEnv = System.getenv().toMutableMap().apply {
                jvm.setCapsuleCacheDir(this)
            }
            pty.run(command, cordaEnv, config.nodeDir.toString())
            log.info("Launched node: ${config.nodeConfig.myLegalName}")
            return true
        } catch (e: Exception) {
            log.log(Level.SEVERE, "Failed to launch Corda: ${e.message}", e)
            return false
        }
    }

    fun reset() {
        baseDir = baseDirFor(System.currentTimeMillis())
        log.info("Changed base directory: $baseDir")

        // Wipe out any knowledge of previous nodes.
        networkMapConfig = null
        nodes.clear()
        nodeInfoFilesCopier.reset()
    }

    /**
     * Add a [NodeConfig] object that has been loaded from a profile.
     */
    fun register(config: NodeConfigWrapper): Boolean {
        if (nodes.putIfAbsent(config.key, config) != null) {
            return false
        }
        nodeInfoFilesCopier.addConfig(config)

        updatePort(config.nodeConfig)

        if (networkMapConfig == null && config.nodeConfig.isNetworkMap) {
            networkMapConfig = config.nodeConfig.let { NetworkMapConfig(it.myLegalName, it.p2pAddress) }
        }

        return true
    }

    /**
     * Creates a node directory that can host a running instance of Corda.
     */
    @Throws(IOException::class)
    fun install(config: InstallConfig): NodeConfigWrapper {
        val installed = config.installTo(baseDir)

        cordappController.useCordappsFor(config).forEach {
            installed.cordappsDir.createDirectories()
            val plugin = it.copyToDirectory(installed.cordappsDir)
            log.info("Installed: $plugin")
        }

        if (!config.deleteBaseDir()) {
            log.warning("Failed to remove '${config.baseDir}'")
        }

        return installed
    }

    private fun updatePort(config: NodeConfig) {
        val nextPort = 1 + arrayOf(config.p2pAddress.port, config.rpcAddress.port, config.webAddress.port, config.h2port).max() as Int
        port.getAndUpdate { Math.max(nextPort, it) }
    }

    private fun baseDirFor(time: Long): Path = jvm.dataHome.resolve(localFor(time))
    private fun localFor(time: Long) = SimpleDateFormat("yyyyMMddHHmmss").format(Date(time))

}
