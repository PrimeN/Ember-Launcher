package app.ember.tv.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.math.BigInteger
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * System access (#408): Ember talks to the TV's own debugging service so it can change settings
 * that need more than an app may do (which app is Home, switching Google TV's home off, animation
 * speed), without a computer. Older TVs listen on port 5555 and ask "Allow debugging?" once;
 * Android 11 and later use Wireless debugging, which Ember pairs with once using the six-digit code.
 * After the first connection Ember gives itself permission to change secure settings, so Private
 * DNS and animation speed work without debugging from then on.
 */
object SystemAccess {
    enum class State { IDLE, WORKING, APPROVE_ON_TV, CONNECTED, NEEDS_PAIRING, NOT_AVAILABLE, FAILED }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state
    /** A line for the person about the last attempt, or null. */
    private val _note = MutableStateFlow<String?>(null)
    val note: StateFlow<String?> = _note

    private val lock = Mutex()
    private var manager: EmberAdbManager? = null

    const val WRITE_SECURE = "android.permission.WRITE_SECURE_SETTINGS"

    /** Ember may change secure settings itself (granted once over debugging, or by a computer). */
    fun canWriteSettings(context: Context): Boolean =
        context.checkSelfPermission(WRITE_SECURE) == PackageManager.PERMISSION_GRANTED

    fun developerOptionsOn(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    /** Wireless debugging is the way in (Android 11 and later). */
    val wireless: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** Connects, asking on the TV the first time on older TVs. True when Ember can run commands. */
    suspend fun connect(context: Context): Boolean = withContext(Dispatchers.IO) {
        lock.withLock { connectLocked(context.applicationContext) }
    }

    /** Runs a shell [command] with the TV's debugging rights. Null when Ember could not connect or it failed. */
    suspend fun run(context: Context, command: String): String? = withContext(Dispatchers.IO) {
        lock.withLock {
            val app = context.applicationContext
            repeat(2) {
                if (!connectLocked(app)) return@withLock null
                try {
                    return@withLock shell(command)
                } catch (_: Throwable) {
                    reset()
                }
            }
            null
        }
    }

    /**
     * Pairs with Wireless debugging using the six-digit [code] while "Pair device with pairing code"
     * is open on the TV, then connects. Returns a line to show on the TV or the phone.
     */
    suspend fun pair(context: Context, code: String): String = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val digits = code.filter { it.isDigit() }
        if (digits.length != 6) return@withContext "Type the 6-digit code shown on the TV."
        if (!wireless) return@withContext "This TV doesn't use pairing codes. Choose Connect instead."
        lock.withLock {
            _state.value = State.WORKING
            _note.value = "Looking for the pairing screen…"
            val endpoint = AdbMdns.find(app, AdbMdns.PAIRING, 6_000)
            if (endpoint == null) {
                _state.value = State.NEEDS_PAIRING
                return@withLock "Ember can't see the pairing screen. On the TV, open Wireless debugging › Pair device with pairing code and leave it open.".also { _note.value = it }
            }
            val paired = try {
                manager(app).pair(endpoint.host, endpoint.port, digits)
            } catch (_: Throwable) {
                false
            }
            if (!paired) {
                reset()
                _state.value = State.NEEDS_PAIRING
                return@withLock "That code didn't work. Check the code on the TV and try again.".also { _note.value = it }
            }
            _note.value = "Paired. Connecting…"
            delay(1_000)
            if (connectLocked(app)) "Paired. Ember can now change TV settings.".also { _note.value = it }
            else "Paired. Go back to Ember and choose Connect.".also { _note.value = it }
        }
    }

    /** Closes the connection; the next command connects again. */
    suspend fun release() = withContext(Dispatchers.IO) {
        lock.withLock {
            reset()
            if (_state.value == State.CONNECTED) _state.value = State.IDLE
        }
    }

    // ---------------------------------------------------------------- inside

    private fun manager(context: Context): EmberAdbManager =
        manager ?: EmberAdbManager(File(context.filesDir, "adb")).also { manager = it }

    private fun reset() {
        runCatching { manager?.close() }
        manager = null
    }

    private suspend fun connectLocked(context: Context): Boolean {
        manager?.let { if (runCatching { it.isConnected }.getOrDefault(false)) return true }
        _state.value = State.WORKING
        _note.value = null
        // Older TVs (and Chromecast with Google TV): the debugging port is open with USB debugging on.
        if (portOpen(LEGACY_PORT)) {
            _state.value = State.APPROVE_ON_TV
            _note.value = "If the TV asks to allow debugging, choose Always allow, then OK."
            val ok = try {
                manager(context).apply { setTimeout(60, TimeUnit.SECONDS) }.connect(LOOPBACK, LEGACY_PORT)
            } catch (_: Throwable) {
                false
            }
            if (ok) return connected(context)
            reset()
        }
        if (wireless) {
            var endpoint = AdbMdns.find(context, AdbMdns.CONNECT, 4_000)
            // Wireless debugging turns itself off after a restart or a Wi-Fi change; switch it back on.
            if (endpoint == null && canWriteSettings(context) && developerOptionsOn(context)) {
                runCatching { Settings.Global.putInt(context.contentResolver, ADB_WIFI_ENABLED, 1) }
                endpoint = AdbMdns.find(context, AdbMdns.CONNECT, 8_000)
            }
            if (endpoint != null) {
                val ok = try {
                    manager(context).apply { setTimeout(20, TimeUnit.SECONDS) }.connect(endpoint.host, endpoint.port)
                } catch (_: Throwable) {
                    false
                }
                if (ok) return connected(context)
                reset()
                _state.value = State.NEEDS_PAIRING
                _note.value = "Pair Ember with Wireless debugging once to connect."
                return false
            }
        }
        _state.value = State.NOT_AVAILABLE
        _note.value = when {
            !developerOptionsOn(context) -> "Turn on Developer options first."
            wireless -> "Turn on Wireless debugging (or USB debugging) in Developer options."
            else -> "Turn on USB debugging (or Network debugging) in Developer options."
        }
        return false
    }

    private fun connected(context: Context): Boolean {
        if (!canWriteSettings(context)) runCatching { shell("pm grant ${context.packageName} $WRITE_SECURE") }
        _state.value = State.CONNECTED
        _note.value = null
        return true
    }

    private fun shell(command: String): String {
        val m = manager ?: error("not connected")
        return m.openStream("shell:$command").use { stream ->
            stream.openInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    private fun portOpen(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(LOOPBACK, port), 400); true }
    }.getOrDefault(false)

    private const val LOOPBACK = "127.0.0.1"
    private const val LEGACY_PORT = 5555
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
}

/** Ember's debugging identity: an RSA key and a certificate kept in app storage. */
internal class EmberAdbManager(dir: File) : AbsAdbConnectionManager() {
    private val key: PrivateKey
    private val cert: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        val pair = AdbIdentity.load(dir)
        key = pair.first
        cert = pair.second
    }

    override fun getPrivateKey(): PrivateKey = key
    override fun getCertificate(): Certificate = cert
    override fun getDeviceName(): String = "Ember"
}

internal object AdbIdentity {
    fun load(dir: File): Pair<PrivateKey, Certificate> {
        val keyFile = File(dir, "key.pk8")
        val certFile = File(dir, "cert.der")
        if (keyFile.exists() && certFile.exists()) {
            runCatching {
                val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                val cert = CertificateFactory.getInstance("X.509").generateCertificate(certFile.inputStream())
                return key to cert
            }
        }
        dir.mkdirs()
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val cert = selfSigned(pair, now)
        keyFile.writeBytes(pair.private.encoded)
        certFile.writeBytes(cert.encoded)
        return pair.private to cert
    }

    /** A self-signed X.509 certificate for the key, built from its parts (adb only needs one to exist). */
    private fun selfSigned(pair: java.security.KeyPair, now: Long): Certificate {
        val bc = org.bouncycastle.asn1.x509.AlgorithmIdentifier(
            org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers.sha256WithRSAEncryption, org.bouncycastle.asn1.DERNull.INSTANCE,
        )
        val name = org.bouncycastle.asn1.x500.X500Name("CN=Ember")
        val tbs = org.bouncycastle.asn1.x509.V3TBSCertificateGenerator().apply {
            setSerialNumber(org.bouncycastle.asn1.ASN1Integer(now))
            setIssuer(name)
            setSubject(name)
            setStartDate(org.bouncycastle.asn1.x509.Time(Date(now - DAY)))
            setEndDate(org.bouncycastle.asn1.x509.Time(Date(now + 30 * 365 * DAY)))
            setSignature(bc)
            setSubjectPublicKeyInfo(org.bouncycastle.asn1.x509.SubjectPublicKeyInfo.getInstance(pair.public.encoded))
        }.generateTBSCertificate()
        val signature = java.security.Signature.getInstance("SHA256withRSA").run {
            initSign(pair.private)
            update(tbs.getEncoded(org.bouncycastle.asn1.ASN1Encoding.DER))
            sign()
        }
        val der = org.bouncycastle.asn1.DERSequence(
            org.bouncycastle.asn1.ASN1EncodableVector().apply { add(tbs); add(bc); add(org.bouncycastle.asn1.DERBitString(signature)) },
        ).getEncoded(org.bouncycastle.asn1.ASN1Encoding.DER)
        return CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream())
    }

    private const val DAY = 86_400_000L
}

/** Finds this TV's own Wireless debugging services on the network (the TV advertises them by mDNS). */
internal object AdbMdns {
    const val PAIRING = "_adb-tls-pairing._tcp"
    const val CONNECT = "_adb-tls-connect._tcp"

    data class Endpoint(val host: String, val port: Int)

    suspend fun find(context: Context, type: String, timeoutMillis: Long): Endpoint? {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return null
        val own = ownAddresses()
        val found = CompletableDeferred<Endpoint?>()
        val queue = ConcurrentLinkedQueue<NsdServiceInfo>()
        val resolving = AtomicBoolean(false)

        fun resolveNext() {
            if (found.isCompleted || !resolving.compareAndSet(false, true)) return
            val next = queue.poll()
            if (next == null) {
                resolving.set(false)
                return
            }
            @Suppress("DEPRECATION")
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    resolving.set(false)
                    resolveNext()
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    val host = info.host
                    val address = host?.hostAddress
                    if (host != null && address != null && (host.isLoopbackAddress || address in own)) {
                        found.complete(Endpoint(if (host is Inet4Address) address else LOOPBACK, info.port))
                    }
                    resolving.set(false)
                    resolveNext()
                }
            })
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { found.complete(null) }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                queue.add(info)
                resolveNext()
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
        }
        return try {
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
            withTimeoutOrNull(timeoutMillis) { found.await() }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    private fun ownAddresses(): Set<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.mapNotNull { it.hostAddress }.toSet()
    }.getOrDefault(emptySet())

    private const val LOOPBACK = "127.0.0.1"
}
