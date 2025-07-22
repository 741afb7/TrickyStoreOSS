package io.github.a13e300.tricky_store

import android.content.pm.IPackageManager
import android.os.Build
import android.os.FileObserver
import android.os.ServiceManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec;
import java.security.SecureRandom
import io.github.a13e300.tricky_store.keystore.CertHack
import java.io.File

object Config {
    private val hackPackages = mutableSetOf<String>()
    private val generatePackages = mutableSetOf<String>()
    private val packageModes = mutableMapOf<String, Mode>()

    enum class Mode {
        AUTO, LEAF_HACK, GENERATE
    }

    private fun updateTargetPackages(f: File?) = runCatching {
        hackPackages.clear()
        generatePackages.clear()
        packageModes.clear()
        // Default: always generate for these
        listOf("com.google.android.gsf", "com.google.android.gms", "com.android.vending").forEach {
            generatePackages.add(it)
            packageModes[it] = Mode.GENERATE
        }
        f?.readLines()?.forEach {
            if (it.isNotBlank() && !it.startsWith("#")) {
                val n = it.trim()
                when {
                    n.endsWith("!") -> {
                        val pkg = n.removeSuffix("!").trim()
                        generatePackages.add(pkg)
                        packageModes[pkg] = Mode.GENERATE
                    }
                    n.endsWith("?") -> {
                        val pkg = n.removeSuffix("?").trim()
                        hackPackages.add(pkg)
                        packageModes[pkg] = Mode.LEAF_HACK
                    }
                    else -> {
                        // Auto mode
                        packageModes[n] = Mode.AUTO
                    }
                }
            }
        }
        Logger.i("update hack packages: $hackPackages, generate packages=$generatePackages, packageModes=$packageModes")
    }.onFailure {
        Logger.e("failed to update target files", it)
    }

    private fun updateKeyBox(f: File?) = runCatching {
        CertHack.readFromXml(f?.readText())
    }.onFailure {
        Logger.e("failed to update keybox", it)
    }

    private const val CONFIG_PATH = "/data/adb/tricky_store"
    private const val TARGET_FILE = "target.txt"
    private const val KEYBOX_FILE = "keybox.xml"
    private const val TEE_STATUS_FILE = "tee_status"
    private const val PATCHLEVEL_FILE = "security_patch.txt"
    private val root = File(CONFIG_PATH)

    @Volatile
    private var teeBroken: Boolean? = null

    private fun isTEEWorking(): Boolean {
        val alias = "tee_attest_test_key"
        return try {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.app.ActivityThread.initializeMainlineModules();
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.security.keystore2.AndroidKeyStoreProvider.install();
            } else {
                android.security.keystore.AndroidKeyStoreProvider.install();
            }

            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)

            val keyPairGenerator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")

            val challenge = ByteArray(16).apply {
                SecureRandom().nextBytes(this)
            }

            val parameterSpec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAttestationChallenge(challenge)
                .setIsStrongBoxBacked(false)
                .build()

            keyPairGenerator.initialize(parameterSpec)
            keyPairGenerator.generateKeyPair()

            keyStore.deleteEntry(alias)
            true
        } catch (e: Exception) {
            Logger.e("TEE check failure: ${e.message}")
            false
        }
    }


    private fun storeTEEStatus(root: File) {
        val statusFile = File(root, TEE_STATUS_FILE)
        val status = isTEEWorking()
        teeBroken = !status
        try {
            statusFile.writeText("teeBroken=${!status}")
        } catch (e: Exception) {
            Logger.e("Failed to write TEE status: ${e.message}")
        }
    }

    private fun loadTEEStatus(root: File) {
        val statusFile = File(root, TEE_STATUS_FILE)
        if (statusFile.exists()) {
            val line = statusFile.readText().trim()
            teeBroken = line == "teeBroken=true"
        } else {
            teeBroken = null
        }
    }

    object ConfigObserver : FileObserver(root, CLOSE_WRITE or DELETE or MOVED_FROM or MOVED_TO) {
        override fun onEvent(event: Int, path: String?) {
            path ?: return
            val f = when (event) {
                CLOSE_WRITE, MOVED_TO -> File(root, path)
                DELETE, MOVED_FROM -> null
                else -> return
            }
            when (path) {
                TARGET_FILE -> updateTargetPackages(f)
                KEYBOX_FILE -> updateKeyBox(f)
                PATCHLEVEL_FILE -> updatePatchLevel(f)
            }
        }
    }

    fun initialize() {
        root.mkdirs()
        val scope = File(root, TARGET_FILE)
        if (scope.exists()) {
            updateTargetPackages(scope)
        } else {
            Logger.e("target.txt file not found, please put it to $scope !")
        }
        val keybox = File(root, KEYBOX_FILE)
        if (!keybox.exists()) {
            Logger.e("keybox file not found, please put it to $keybox !")
        } else {
            updateKeyBox(keybox)
        }
        storeTEEStatus(root)
        val patchFile = File(root, PATCHLEVEL_FILE)
        updatePatchLevel(if (patchFile.exists()) patchFile else null)
        ConfigObserver.startWatching()
    }

    private var iPm: IPackageManager? = null

    fun getPm(): IPackageManager? {
        if (iPm == null) {
            iPm = IPackageManager.Stub.asInterface(ServiceManager.getService("package"))
        }
        return iPm
    }

    fun needHack(callingUid: Int): Boolean = kotlin.runCatching {
        val ps = getPm()?.getPackagesForUid(callingUid) ?: return false
        if (teeBroken == null) loadTEEStatus(root)
        for (pkg in ps) {
            when (packageModes[pkg]) {
                Mode.LEAF_HACK -> return true
                Mode.AUTO -> {
                    if (teeBroken == false) return true
                }
                else -> {}
            }
        }
        return false
    }.onFailure { Logger.e("failed to get packages", it) }.getOrNull() ?: false

    fun needGenerate(callingUid: Int): Boolean = kotlin.runCatching {
        val ps = getPm()?.getPackagesForUid(callingUid) ?: return false
        if (teeBroken == null) loadTEEStatus(root)
        for (pkg in ps) {
            when (packageModes[pkg]) {
                Mode.GENERATE -> return true
                Mode.AUTO -> {
                    if (teeBroken == true) return true
                }
                else -> {}
            }
        }
        return false
    }.onFailure { Logger.e("failed to get packages", it) }.getOrNull() ?: false

    @Volatile
    var _customPatchLevel: CustomPatchLevel? = null

    fun updatePatchLevel(f: File?) = runCatching {
        if (f == null || !f.exists()) {
            _customPatchLevel = null
            return@runCatching
        }
        val lines = f.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        if (lines.isEmpty()) {
            _customPatchLevel = null
            return@runCatching
        }
        if (lines.size == 1 && !lines[0].contains("=")) {
            _customPatchLevel = CustomPatchLevel(all = lines[0])
            return@runCatching
        }
        val map = mutableMapOf<String, String>()
        for (line in lines) {
            val idx = line.indexOf('=')
            if (idx > 0) {
                val key = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                map[key] = value
            }
        }
        val all = map["all"]
        _customPatchLevel = CustomPatchLevel(
            system = map["system"] ?: all,
            vendor = map["vendor"] ?: all,
            boot = map["boot"] ?: all,
            all = all
        )
    }.onFailure {
        Logger.e("failed to update patch level", it)
    }
}
