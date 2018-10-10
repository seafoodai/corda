package net.corda.node.internal.cordapp

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.*
import net.corda.node.VersionInfo
import net.corda.node.cordapp.CordappLoader
import net.corda.nodeapi.internal.DEV_CERTIFICATES
import net.corda.testing.node.internal.cordappsForPackages
import net.corda.testing.node.internal.getTimestampAsDirectoryName
import net.corda.testing.node.internal.packageInDirectory
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.nio.file.Path
import java.nio.file.Paths

@InitiatingFlow
class DummyFlow : FlowLogic<Unit>() {
    @Suspendable
    override fun call() = Unit
}

@InitiatedBy(DummyFlow::class)
class LoaderTestFlow(@Suppress("UNUSED_PARAMETER") unusedSession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() = Unit
}

@SchedulableFlow
class DummySchedulableFlow : FlowLogic<Unit>() {
    @Suspendable
    override fun call() = Unit
}

@StartableByRPC
class DummyRPCFlow : FlowLogic<Unit>() {
    @Suspendable
    override fun call() = Unit
}

class JarScanningCordappLoaderTest {
    private companion object {
        const val testScanPackage = "net.corda.node.internal.cordapp"
        const val isolatedContractId = "net.corda.finance.contracts.isolated.AnotherDummyContract"
        const val isolatedFlowName = "net.corda.finance.contracts.isolated.IsolatedDummyFlow\$Initiator"
    }

    @Test
    fun `classes that aren't in cordapps aren't loaded`() {
        // Basedir will not be a corda node directory so the dummy flow shouldn't be recognised as a part of a cordapp
        val loader = JarScanningCordappLoader.fromDirectories(listOf(Paths.get(".")))
        assertThat(loader.cordapps).isEmpty()
    }

    @Test
    fun `isolated JAR contains a CorDapp with a contract and plugin`() {
        val isolatedJAR = JarScanningCordappLoaderTest::class.java.getResource("isolated.jar")!!
        val loader = JarScanningCordappLoader.fromJarUrls(listOf(isolatedJAR))

        assertThat(loader.cordapps).hasSize(1)

        val actualCordapp = loader.cordapps.single()
        assertThat(actualCordapp.contractClassNames).isEqualTo(listOf(isolatedContractId))
        assertThat(actualCordapp.initiatedFlows.single().name).isEqualTo("net.corda.finance.contracts.isolated.IsolatedDummyFlow\$Acceptor")
        assertThat(actualCordapp.rpcFlows).isEmpty()
        assertThat(actualCordapp.schedulableFlows).isEmpty()
        assertThat(actualCordapp.services).isEmpty()
        assertThat(actualCordapp.serializationWhitelists).hasSize(1)
        assertThat(actualCordapp.serializationWhitelists.first().javaClass.name).isEqualTo("net.corda.serialization.internal.DefaultWhitelist")
        assertThat(actualCordapp.jarPath).isEqualTo(isolatedJAR)
    }

    @Test
    fun `flows are loaded by loader`() {
        val loader = cordappLoaderForPackages(listOf(testScanPackage))

        val actual = loader.cordapps.toTypedArray()
        // One cordapp from this source tree. In gradle it will also pick up the node jar.
        assertThat(actual.size == 0 || actual.size == 1).isTrue()

        val actualCordapp = actual.single { !it.initiatedFlows.isEmpty() }
        assertThat(actualCordapp.initiatedFlows).first().hasSameClassAs(DummyFlow::class.java)
        assertThat(actualCordapp.rpcFlows).first().hasSameClassAs(DummyRPCFlow::class.java)
        assertThat(actualCordapp.schedulableFlows).first().hasSameClassAs(DummySchedulableFlow::class.java)
    }

    @Test
    fun `duplicate packages are ignored`() {
        val loader = cordappLoaderForPackages(listOf(testScanPackage, testScanPackage))
        val cordapps = loader.cordapps.filter { LoaderTestFlow::class.java in it.initiatedFlows }
        assertThat(cordapps).hasSize(1)
    }

    @Test
    fun `sub-packages are ignored`() {
        val loader = cordappLoaderForPackages(listOf("net.corda.core", testScanPackage))
        val cordapps = loader.cordapps.filter { LoaderTestFlow::class.java in it.initiatedFlows }
        assertThat(cordapps).hasSize(1)
    }

    // This test exists because the appClassLoader is used by serialisation and we need to ensure it is the classloader
    // being used internally. Later iterations will use a classloader per cordapp and this test can be retired.
    @Test
    fun `cordapp classloader can load cordapp classes`() {
        val isolatedJAR = JarScanningCordappLoaderTest::class.java.getResource("isolated.jar")!!
        val loader = JarScanningCordappLoader.fromJarUrls(listOf(isolatedJAR), VersionInfo.UNKNOWN)

        loader.appClassLoader.loadClass(isolatedContractId)
        loader.appClassLoader.loadClass(isolatedFlowName)
    }

    @Test
    fun `cordapp classloader sets target and min version to 1 if not specified`() {
        val jar = JarScanningCordappLoaderTest::class.java.getResource("versions/no-min-or-target-version.jar")!!
        val loader = JarScanningCordappLoader.fromJarUrls(listOf(jar), VersionInfo.UNKNOWN)
        loader.cordapps.forEach {
            assertThat(it.info.targetPlatformVersion).isEqualTo(1)
            assertThat(it.info.minimumPlatformVersion).isEqualTo(1)
        }
    }

    @Test
    fun `cordapp classloader returns correct values for minPlatformVersion and targetVersion`() {
        // load jar with min and target version in manifest
        // make sure classloader extracts correct values
        val jar = JarScanningCordappLoaderTest::class.java.getResource("versions/min-2-target-3.jar")!!
        val loader = JarScanningCordappLoader.fromJarUrls(listOf(jar), VersionInfo.UNKNOWN)
        val cordapp = loader.cordapps.first()
        assertThat(cordapp.info.targetPlatformVersion).isEqualTo(3)
        assertThat(cordapp.info.minimumPlatformVersion).isEqualTo(2)
    }

    @Test
    fun `cordapp classloader sets target version to min version if target version is not specified`() {
        // load jar with minVersion but not targetVersion in manifest
        val jar = JarScanningCordappLoaderTest::class.java.getResource("versions/min-2-no-target.jar")!!
        val loader = JarScanningCordappLoader.fromJarUrls(listOf(jar), VersionInfo.UNKNOWN)
        // exclude the core cordapp
        val cordapp = loader.cordapps.single { it.cordappClasses.contains("net.corda.core.internal.cordapp.CordappImpl") }
        assertThat(cordapp.info.targetPlatformVersion).isEqualTo(2)
        assertThat(cordapp.info.minimumPlatformVersion).isEqualTo(2)
    }

    @Test
    fun `cordapp classloader does not load apps when their min platform version is greater than the node platform version`() {
        val jar = JarScanningCordappLoaderTest::class.java.getResource("versions/min-2-no-target.jar")!!
        val loader = JarScanningCordappLoader.fromJarUrls(listOf(jar), VersionInfo.UNKNOWN.copy(platformVersion = 1))
        assertThat(loader.cordapps).hasSize(0)
    }

    @Test
    fun `cordapp classloader does load apps when their min platform version is less than the platform version`() {
        val jar = JarScanningCordappLoaderTest::class.java.getResource("versions/min-2-target-3.jar")!!
        val loader = JarScanningCordappLoader.fromJarUrls(listOf(jar), VersionInfo.UNKNOWN.copy(platformVersion = 1000))
        assertThat(loader.cordapps).hasSize(1)
    }

    @Test
    fun `cordapp classloader does load apps when their min platform version is equal to the platform version`() {
        val jar = JarScanningCordappLoaderTest::class.java.getResource("versions/min-2-target-3.jar")!!
        val loader = JarScanningCordappLoader.fromJarUrls(listOf(jar), VersionInfo.UNKNOWN.copy(platformVersion = 2))
        assertThat(loader.cordapps).hasSize(1)
    }

    @Test
    fun `cordapp classloader loads app signed by allowed certificate`() {
        val jar = JarScanningCordappLoaderTest::class.java.getResource("signed/signed-by-dev-key.jar")!!
        val loader = JarScanningCordappLoader.fromJarUrls(listOf(jar), blacklistedCerts = emptyList())
        assertThat(loader.cordapps).hasSize(1)
    }

    @Test
    fun `cordapp classloader does not load app signed by blacklisted certificate`() {
        val jar = JarScanningCordappLoaderTest::class.java.getResource("signed/signed-by-dev-key.jar")!!
        val loader = JarScanningCordappLoader.fromJarUrls(listOf(jar), blacklistedCerts = DEV_CERTIFICATES)
        assertThat(loader.cordapps).hasSize(0)
    }

    @Test
    fun `cordapp classloader loads app signed by both allowed and non-blacklisted certificate`() {
        val jar = JarScanningCordappLoaderTest::class.java.getResource("signed/signed-by-two-keys.jar")!!
        val loader = JarScanningCordappLoader.fromJarUrls(listOf(jar), blacklistedCerts = DEV_CERTIFICATES)
        assertThat(loader.cordapps).hasSize(1)
    }

    private fun cordappLoaderForPackages(packages: Iterable<String>): CordappLoader {
        val cordapps = cordappsForPackages(packages)
        return testDirectory().let { directory ->
            cordapps.packageInDirectory(directory)
            JarScanningCordappLoader.fromDirectories(listOf(directory))
        }
    }

    private fun testDirectory(): Path {
        return Paths.get("build", getTimestampAsDirectoryName())
    }
}
