package com.example.directlink

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Один APK на оба телефона.
 *  Приёмник: создаёт группу Wi-Fi Direct, ждёт подключения, показывает и сохраняет кадры.
 *  Отправитель: находит приёмник, подключается и шлёт новые фото из Pictures/TripodCamera.
 */
@SuppressLint("MissingPermission")
class MainActivity : Activity() {

    private enum class Role { SENDER, RECEIVER }

    private data class Img(val id: Long, val name: String, val uri: Uri)

    companion object {
        const val PORT = 8988
        private const val FOLDER_SRC = "Pictures/TripodCamera%"
    }

    private lateinit var p2p: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private lateinit var status: TextView
    private lateinit var peersBox: LinearLayout
    private lateinit var preview: ImageView

    private var role: Role? = null
    private var pendingRole: Role? = null
    @Volatile private var running = false
    @Volatile private var senderStarted = false
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var clientSocket: Socket? = null
    @Volatile private var lastSentId = -1L
    private var sentCount = 0
    private var receivedCount = 0

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val st = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (st == WifiP2pManager.WIFI_P2P_STATE_DISABLED) {
                        setStatus("Wi-Fi Direct выключен. Включите Wi-Fi на телефоне.")
                    }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (role == Role.SENDER) {
                        p2p.requestPeers(channel) { list -> showPeers(list.deviceList) }
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    p2p.requestConnectionInfo(channel) { info -> onConnectionInfo(info) }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        root.addView(Button(this).apply {
            text = "Я приёмник (смотрю кадры)"
            setOnClickListener { choose(Role.RECEIVER) }
        })
        root.addView(Button(this).apply {
            text = "Я отправитель (телефон на штативе)"
            setOnClickListener { choose(Role.SENDER) }
        })
        root.addView(Button(this).apply {
            text = "Стоп"
            setOnClickListener { stopAll("Остановлено") }
        })
        status = TextView(this).apply {
            text = "Выберите роль. На обоих телефонах включите Wi-Fi."
            textSize = 15f
            setPadding(0, pad, 0, pad)
        }
        root.addView(status)
        peersBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(peersBox)
        preview = ImageView(this).apply { adjustViewBounds = true }
        root.addView(preview)
        setContentView(ScrollView(this).apply { addView(root) })

        p2p = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = p2p.initialize(this, mainLooper, null)

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(p2pReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(p2pReceiver, filter)
        }
    }

    override fun onDestroy() {
        running = false
        try { clientSocket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        try { unregisterReceiver(p2pReceiver) } catch (_: Exception) {}
        try { p2p.removeGroup(channel, null) } catch (_: Exception) {}
        super.onDestroy()
    }

    // ---------- разрешения и выбор роли ----------

    private fun permissionsFor(r: Role): List<String> {
        val l = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            l += Manifest.permission.NEARBY_WIFI_DEVICES
            if (r == Role.SENDER) l += Manifest.permission.READ_MEDIA_IMAGES
        } else {
            l += Manifest.permission.ACCESS_FINE_LOCATION
            if (r == Role.SENDER) l += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return l
    }

    private fun choose(r: Role) {
        if (running) {
            setStatus("Сначала нажмите «Стоп».")
            return
        }
        val missing = permissionsFor(r).filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            pendingRole = r
            requestPermissions(missing.toTypedArray(), 1)
            return
        }
        begin(r)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val r = pendingRole ?: return
        pendingRole = null
        if (permissionsFor(r).all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            begin(r)
        } else {
            setStatus("Нужны разрешения, без них Wi-Fi Direct не работает.")
        }
    }

    private fun begin(r: Role) {
        role = r
        running = true
        if (r == Role.RECEIVER) startReceiver() else startSenderDiscovery()
    }

    private fun stopAll(message: String) {
        running = false
        senderStarted = false
        try { clientSocket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        p2p.stopPeerDiscovery(channel, null)
        p2p.cancelConnect(channel, null)
        p2p.removeGroup(channel, null)
        peersBox.removeAllViews()
        role = null
        setStatus(message)
    }

    private fun setStatus(text: String) {
        runOnUiThread { status.text = text }
    }

    // ---------- приёмник ----------

    private fun startReceiver() {
        receivedCount = 0
        setStatus("Включаю режим приёма…")
        startServerThread()
        p2p.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { createGroup() }
            override fun onFailure(reason: Int) { createGroup() }
        })
    }

    private fun createGroup() {
        p2p.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                p2p.discoverPeers(channel, null)
                setStatus(
                    "Приёмник готов. На втором телефоне выберите «Я отправитель», " +
                        "нажмите на имя этого телефона и подтвердите приглашение здесь."
                )
            }

            override fun onFailure(reason: Int) {
                setStatus("Не удалось создать группу (код $reason). Включите Wi-Fi, нажмите «Стоп» и повторите.")
            }
        })
    }

    private fun startServerThread() {
        if (serverSocket != null) return
        Thread {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(PORT))
                serverSocket = ss
                while (running) {
                    val client = ss.accept()
                    clientSocket = client
                    try {
                        receiveFrom(client)
                    } catch (e: Exception) {
                        if (running) setStatus("Отправитель отключился, жду подключения…")
                    } finally {
                        try { client.close() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                if (running) setStatus("Ошибка приёма: ${e.message}")
            } finally {
                serverSocket = null
            }
        }.start()
    }

    private fun receiveFrom(client: Socket) {
        val inp = DataInputStream(BufferedInputStream(client.getInputStream()))
        val out = client.getOutputStream()
        while (running) {
            val name = inp.readUTF()
            val size = inp.readLong()
            if (size <= 0L || size > 100_000_000L) throw IOException("Неверный размер файла")
            val bytes = ByteArray(size.toInt())
            inp.readFully(bytes)
            saveToGallery(name, bytes)
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            receivedCount++
            val n = receivedCount
            runOnUiThread {
                if (bmp != null) preview.setImageBitmap(bmp)
                status.text = "Принято кадров: $n. Последний: $name\nСохраняются в галерею: Pictures/TripodLink"
            }
            out.write(1)
            out.flush()
        }
    }

    private fun saveToGallery(name: String, bytes: ByteArray) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TripodLink")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            setStatus("Не удалось сохранить кадр: ${e.message}")
        }
    }

    // ---------- отправитель ----------

    private fun startSenderDiscovery() {
        senderStarted = false
        sentCount = 0
        peersBox.removeAllViews()
        setStatus("Ищу приёмник… На втором телефоне должно быть выбрано «Я приёмник».")
        p2p.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {
                setStatus("Поиск не запустился (код $reason). Проверьте, что Wi-Fi включён.")
            }
        })
    }

    private fun showPeers(devices: Collection<WifiP2pDevice>) {
        runOnUiThread {
            peersBox.removeAllViews()
            for (d in devices) {
                peersBox.addView(Button(this).apply {
                    text = if (d.deviceName.isNullOrEmpty()) d.deviceAddress else d.deviceName
                    setOnClickListener { connectTo(d) }
                })
            }
        }
    }

    private fun connectTo(d: WifiP2pDevice) {
        val cfg = WifiP2pConfig().apply {
            deviceAddress = d.deviceAddress
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 0
        }
        setStatus("Подключаюсь к ${d.deviceName}… Подтвердите приглашение на втором телефоне.")
        p2p.connect(channel, cfg, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {
                setStatus("Не удалось подключиться (код $reason). Нажмите «Стоп» и повторите.")
            }
        })
    }

    private fun onConnectionInfo(info: WifiP2pInfo) {
        if (!info.groupFormed) return
        when (role) {
            Role.SENDER -> {
                if (info.isGroupOwner) {
                    setStatus("Роли Wi-Fi Direct распределились наоборот. Нажмите «Стоп» на обоих телефонах и повторите.")
                } else {
                    val host = info.groupOwnerAddress?.hostAddress
                    if (host != null) startSending(host)
                }
            }
            Role.RECEIVER -> setStatus("Отправитель подключён, жду кадры…")
            null -> {}
        }
    }

    private fun startSending(host: String) {
        if (senderStarted) return
        senderStarted = true
        if (lastSentId < 0L) lastSentId = queryMaxId()
        Thread {
            while (running) {
                try {
                    val s = Socket()
                    try {
                        s.connect(InetSocketAddress(host, PORT), 3000)
                    } catch (e: Exception) {
                        s.close()
                        throw e
                    }
                    clientSocket = s
                    s.use { sock ->
                        val out = DataOutputStream(BufferedOutputStream(sock.getOutputStream()))
                        val inp = DataInputStream(BufferedInputStream(sock.getInputStream()))
                        setStatus("Соединено. Отправляю новые кадры из Pictures/TripodCamera…")
                        while (running) {
                            for (img in queryNewImages(lastSentId)) {
                                val bytes = contentResolver.openInputStream(img.uri)?.use { ins -> ins.readBytes() }
                                    ?: continue
                                out.writeUTF(img.name)
                                out.writeLong(bytes.size.toLong())
                                out.write(bytes)
                                out.flush()
                                inp.readByte() // подтверждение от приёмника
                                lastSentId = img.id
                                sentCount++
                                setStatus("Отправлено кадров: $sentCount. Последний: ${img.name}")
                            }
                            Thread.sleep(2000)
                        }
                    }
                } catch (e: Exception) {
                    if (running) {
                        setStatus("Нет связи с приёмником, повторяю…")
                        Thread.sleep(3000)
                    }
                }
            }
            senderStarted = false
        }.start()
    }

    private fun queryMaxId(): Long {
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?",
            arrayOf(FOLDER_SRC),
            MediaStore.Images.Media._ID + " DESC"
        )?.use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        return 0L
    }

    private fun queryNewImages(afterId: Long): List<Img> {
        val list = ArrayList<Img>()
        val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        contentResolver.query(
            base,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME),
            MediaStore.Images.Media.RELATIVE_PATH + " LIKE ? AND " + MediaStore.Images.Media._ID + " > ?",
            arrayOf(FOLDER_SRC, afterId.toString()),
            MediaStore.Images.Media._ID + " ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(1) ?: ("photo_" + id + ".jpg")
                list.add(Img(id, name, ContentUris.withAppendedId(base, id)))
            }
        }
        return list
    }
}
