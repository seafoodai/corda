package net.corda.testing.node.internal

import net.corda.core.internal.createDirectories
import net.corda.core.internal.deleteRecursively
import net.corda.core.internal.div
import net.corda.core.utilities.debug
import net.corda.core.utilities.loggerFor
import net.corda.testing.node.TestCordapp
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.ConcurrentHashMap

object TestCordappDirectories {
    private val logger = loggerFor<TestCordappDirectories>()

    private val whitespace = "\\s".toRegex()
    private const val whitespaceReplacement = "_"

    private val testCordappsCache = ConcurrentHashMap<TestCordappImpl, Path>()

    fun getJarDirectory(cordapp: TestCordapp, cordappsDirectory: Path = defaultCordappsDirectory): Path {
        cordapp as TestCordappImpl
        return testCordappsCache.computeIfAbsent(cordapp) {
            val random = UUID.randomUUID().toString()
            val cordappDir = (cordappsDirectory / random).createDirectories()
            val jarFile = cordappDir / "${cordapp.name}_${cordapp.version}_$random.jar".replace(whitespace, whitespaceReplacement)
            cordapp.packageAsJar(jarFile)
            logger.debug { "$cordapp packaged into $jarFile" }
            cordappDir
        }
    }

    private val defaultCordappsDirectory: Path by lazy {
        val cordappsDirectory = (Paths.get("build") / "tmp" / getTimestampAsDirectoryName() / "generated-test-cordapps").toAbsolutePath()
        logger.info("Initialising generated test CorDapps directory in $cordappsDirectory")
        cordappsDirectory.toFile().deleteOnExit()
        cordappsDirectory.deleteRecursively()
        cordappsDirectory.createDirectories()
    }
}
