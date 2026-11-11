package app.passengerace

import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.constant.control.WiperSwitch
import ai.pleos.playground.vehicle.listener.DrivingTimeListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.FuelPercentListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.service.media.MediaBrowserService
import android.util.Log
import android.view.KeyEvent
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

private const val TAG = "ACE"
private const val MEDIA_CHANNEL = "media"
private const val ALERT_CHANNEL = "alert"
private const val MEDIA_NOTE = 1

/** Robolectric은 없는 서비스에도 이름 없는 연결을 흉내 내 NaviHelper가 죽고 android.car도 없으므로 시험에서는 끈다. */
internal var useNavi = true
private const val ALERT_NOTE = 2
private const val POPUP_MS = 6_000L
private const val WAIT_NOTE = "안내 끝나면 이어 가요"
// 어른 고전, 아이 옛이야기·우화, 연재 회차 순서로 둔다. 소리 파일이 없는 이야기는 목록에서 빠진다.
private val ORDER = listOf(
    "midas", "three-questions", "hamlet", "christmas-gift", "last-leaf", "necklace", "orpheus", "how-much-land", "twenty-years", "bet",
    "icarus", "pandora",
    "sun-wind", "heungbu", "tortoise-hare", "emperor-clothes", "match-girl", "bremen", "sun-moon", "gyeonu",
    "monte-cristo-1", "monte-cristo-2", "monte-cristo-3", "monte-cristo-4", "monte-cristo-5", "monte-cristo-6",
)

private const val ASK_ACTION = "ask"
const val LISTEN_MS = 6_000L
private const val WIPER_POLL_MS = 3_000L
private const val TURN_CHECK_MS = 5_000L

enum class HelpStep { OFF, LISTENING, ANSWER, ASK_DEFROST }

/** 옆자리 안내 화면이 쓸 상태. heard는 알아들은 말이다. 메모리에만 두고 안내가 끝나면 지운다(저장하지 않는다). */
data class HelpState(val step: HelpStep = HelpStep.OFF, val heard: String? = null, val answer: HelpAnswer? = null)

/** ms는 줄마다의 길이다. 길이 글자와 진행 막대가 쓴다. part는 연재 회차, 단편은 0. */
class StoryMeta(val id: String, val title: String, val source: String, val part: Int, val ms: List<Int>) {
    val label get() = if (part > 0) "$source · 연재 ${part}회" else source
}

/** "2분 38초". 초 아래는 버린다(듣는 중 화면의 2:38과 맞춘다). */
fun length(ms: Int) = (ms / 1000).let { if (it < 60) "${it}초" else "%d분 %02d초".format(it / 60, it % 60) }

class StoryService : MediaBrowserService() {
    inner class Local : Binder() {
        val service get() = this@StoryService
    }

    val signals: CarSignals = SampleSignals
    var onChange: (() -> Unit)? = null
    var started = false
        private set
    lateinit var catalog: List<StoryMeta>
        private set
    lateinit var n: Narrator
        private set
    /** 이번 길에서 낸 미리 알림 수, 재탐색이 있었는지, 도착했는지. 길 안내가 새로 시작되면 지운다. */
    var alerts = 0
        private set
    var missed = false
        private set
    var arrived = false
        private set
    /** 옆자리 안내: 듣는 중, 답, 켤까요. 바뀌면 onChange를 부른다. */
    var help = HelpState()
        private set
    lateinit var book: HelpBook
        private set

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setWillPauseWhenDucked(true)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener(::onFocus)
            .build()
    }
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private val notes by lazy { getSystemService(NotificationManager::class.java) }
    private val prefs by lazy { getSharedPreferences("ace", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private val player = MediaPlayer()
    private var onDone: () -> Unit = {}
    private var foreground = false
    private var shown: Act.Status? = null
    private lateinit var session: MediaSession

    private lateinit var route: SampleRoute
    private var policy: AlertPolicy? = null
    private var routeSec = 0
    private val routeTick = Runnable(::tickRoute)

    private val navi by lazy { NaviHelper(this) }
    private var naviReady = false
    private var naviPolicy: AlertPolicy? = null
    private var naviDistance = 0
    private var car: Car? = null

    // 옆자리 안내와 먼저 알림. 이야기와 다른 소리 권한(잠깐 쓰기)을 받아서, 이야기는 내비 안내 때처럼 멈췄다가 끊긴 줄부터 잇는다.
    private val tips = TipPolicy(SystemClock::elapsedRealtime)
    private val voice = MediaPlayer()
    private var voiceDone: () -> Unit = {}
    private var asideStep: (() -> Unit)? = null // 소리를 되찾으면 이 단계를 처음부터 다시 한다
    private var asideHeld = false
    private val asideFocus by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener(::onAsideFocus, handler)
            .build()
    }
    private var hearing: ((String?) -> Unit)? = null
    private val hearTimeout = Runnable { heard(null) }
    private var stt: SpeechToText? = null
    private var vehicle: Vehicle? = null
    private val front = WindowArea(WindowValue.FRONT_WINDSHIELD)
    private var fastestWiper: WiperSwitch? = null
    private var wiperOn = false
    private var night = false
    private var lowBeamOn = false
    private var naviNextM: Int? = null
    private var turnSince: Long? = null
    private val turnCheck = Runnable(::checkTurn)
    private val wiperPoll = Runnable(::readWiper)
    private var fuelPercent: Int? = null
    private val debuggable by lazy { applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 }
    private var lastGear = Gear.P
    private val naviListener = object : NaviHelperEventListener {
        override fun onTBTInfo(list: List<TBTInfo>) {
            handler.post { onTbt(list) }
        }

        override fun onRouteStarted(info: RouteStartInfo) {
            handler.post(::routeStarted)
        }

        // 경로를 벗어나 새로 잡았으면 길을 놓친 것으로 본다.
        override fun onReRouteEnded() {
            handler.post {
                Log.i(TAG, "NAVI reroute")
                missed = true
            }
        }

        override fun onDestinationArrived(info: DestinationArrivedInfo) {
            handler.post(::arrive)
        }
    }

    override fun onCreate() {
        super.onCreate()
        catalog = loadCatalog()
        n = Narrator(catalog.map { Story(it.id, it.ms.size, it.part > 0) }, SystemClock::elapsedRealtime) // 차 시계가 맞춰져도 멈춘 시간이 튀지 않게 한다
        val serial = prefs.getString("serialStory", null)?.let { it to prefs.getInt("serialLine", 0) }
        n.restore(prefs.getString("story", null), prefs.getInt("line", 0), prefs.getStringSet("heard", emptySet())!!, prefs.getBoolean("talkLess", false), serial)
        started = prefs.getBoolean("started", false)
        route = loadRoute()
        n.nextNavIn = ::nextNavIn
        // Pleos 내비가 없는 기기(시험, 일반 에뮬레이터)에서는 실패해도 표본 경로로 돈다.
        if (useNavi) runCatching { navi.initialize({ navi.addListener(naviListener); naviReady = true }, { Log.w(TAG, "NAVI init failed", it) }) }
            .onFailure { Log.w(TAG, "NAVI unavailable", it) }
        if (useNavi) runCatching(::watchGear).onFailure { Log.w(TAG, "GEAR unavailable", it) }
        book = HelpBook.parse(assets.open("help.json").bufferedReader().readText())
        if (useNavi) runCatching(::watchCar).onFailure { Log.w(TAG, "VEHICLE unavailable", it) }
        if (useNavi) runCatching(::startStt).onFailure { Log.w(TAG, "STT unavailable", it) }

        notes.createNotificationChannel(NotificationChannel(MEDIA_CHANNEL, "이야기", NotificationManager.IMPORTANCE_LOW))
        notes.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "미리 알림", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null) // 같은 말을 목소리로 하므로 알림음을 내지 않는다
        })

        player.setOnCompletionListener { onDone() }
        player.setOnErrorListener { _, what, extra ->
            Log.e(TAG, "PLAYER error $what $extra")
            onDone()
            true
        }
        voice.setOnCompletionListener { voiceDone() }
        voice.setOnErrorListener { _, what, extra ->
            Log.e(TAG, "VOICE error $what $extra")
            voiceDone()
            true
        }

        session = MediaSession(this, "ace")
        session.setCallback(sessionCallback, handler)
        session.isActive = true
        sessionToken = session.sessionToken
        showStatus(Act.Status(catalog[n.index].id, n.mode))

        signals.onChange = {
            // 이야기를 듣다가 D로 바뀌면 앱 칸을 내비에 내준다. P로 돌아와도 우리 화면을 다시 올리지 않는다.
            if (signals.gear == Gear.D && lastGear != Gear.D && n.on) {
                Log.i(TAG, "NAVI foreground")
                if (naviReady) runCatching { navi.requestNavigationScreen(NavigationScreenRequest.Foreground) } // NAVI_CUSTOM_MAP 권한이 있어야 한다
                    .onFailure { Log.w(TAG, "NAVI foreground failed", it) }
            }
            if (signals.gear == Gear.D && lastGear != Gear.D) tipLevel(TIP_WINDOW_PARKED, false)
            if (signals.gear == Gear.P && lastGear == Gear.D) readWindows()
            lastGear = signals.gear
            tips.gear(signals.gear)
            checkLights()
            checkTurn()
            showArrival()
            onChange?.invoke()
        }
        SampleSignals.onRoute = ::startRoute
        SampleSignals.onArrive = ::arrive
        SampleSignals.onHear = ::heard
        SampleSignals.onTip = ::tipLevel
        SampleSignals.onNight = ::setNight
        HelpReceiver.onAsk = ::ask
        Log.i(TAG, "SERVICE start story=${catalog[n.index].id} line=${n.line}")
    }

    override fun onBind(intent: Intent): IBinder? =
        if (intent.action == SERVICE_INTERFACE) super.onBind(intent) else Local()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(MEDIA_NOTE, mediaNote(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        if (!foreground) stopForeground(STOP_FOREGROUND_REMOVE) // 시작 요청이 닿기 전에 꺼졌다
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (naviReady) runCatching {
            navi.removeListener(naviListener)
            navi.release()
        }
        signals.onChange = null
        SampleSignals.onRoute = null
        SampleSignals.onArrive = null
        SampleSignals.onHear = null
        SampleSignals.onTip = null
        SampleSignals.onNight = null
        HelpReceiver.onAsk = null
        car?.disconnect()
        runCatching { stt?.release() }
        runCatching { vehicle?.release() }
        audio.abandonAudioFocusRequest(focus)
        audio.abandonAudioFocusRequest(asideFocus)
        player.release()
        voice.release()
        session.release()
        super.onDestroy()
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?) = BrowserRoot("root", null)

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>) {
        result.sendResult(catalog.map {
            val description = MediaDescription.Builder().setMediaId(it.id).setTitle(it.title).setSubtitle(it.label).build()
            MediaBrowser.MediaItem(description, MediaBrowser.MediaItem.FLAG_PLAYABLE)
        }.toMutableList())
    }

    fun act(input: Narrator.() -> List<Act>) = run(n.input())

    fun routeStarted() {
        Log.i(TAG, "NAVI route start")
        alerts = 0
        missed = false
        arrived = false
        onChange?.invoke()
    }

    fun arrive() {
        Log.i(TAG, "NAVI arrived alerts=$alerts missed=$missed")
        arrived = true
        arrivalShown = false
        tips.arrived()
        readWindows()
        showArrival()
        onChange?.invoke()
    }

    private var arrivalShown = false

    // 도착하고 차가 서면(기어 P) 한 번만 우리 화면을 앞으로 부른다. 안드로이드가 뒤에서 화면 열기를 막으면 로그에 남는다.
    private fun showArrival() {
        if (!arrived || arrivalShown || signals.gear != Gear.P) return
        arrivalShown = true
        Log.i(TAG, "ARRIVAL open")
        runCatching { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.w(TAG, "ARRIVAL open failed", it) }
    }

    fun closeArrival() {
        arrived = false
        onChange?.invoke()
    }

    fun setTalkLess(less: Boolean) {
        n.talkLess = less
        save()
        onChange?.invoke()
    }

    private val sessionCallback = object : MediaSession.Callback() {
        override fun onPlay() = act { play() }
        override fun onPause() = act { pause() }
        override fun onStop() = act { pause() }
        override fun onSkipToNext() = act { next() }

        // 왼쪽 차 화면 미디어 위젯의 "물어보기".
        override fun onCustomAction(action: String, extras: Bundle?) {
            if (action == ASK_ACTION) ask()
        }

        override fun onPlayFromMediaId(mediaId: String, extras: Bundle?) {
            act { choose(catalog.indexOfFirst { it.id == mediaId }.coerceAtLeast(0)) }
            act { play() }
        }

        // 끄고 켜기는 Narrator에 맡긴다(기본 처리는 두 번 누름을 기다렸다가 움직인다).
        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
            @Suppress("DEPRECATION") // 새 API는 33부터라 minSdk 29에서 못 쓴다
            val key = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            if (key?.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) return super.onMediaButtonEvent(mediaButtonIntent)
            if (key.action == KeyEvent.ACTION_DOWN) act { toggle() }
            return true
        }
    }

    // shortcut: 소리를 가져간 앱이 내비인지 구별하지 않는다, Pleos에서 안내 음성 신호를 확인하면 "안내 뒤 이어 가요"의 조건을 좁힌다.
    private fun onFocus(change: Int) {
        Log.i(TAG, "FOCUS $change")
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> act { focusGained() }
            AudioManager.AUDIOFOCUS_LOSS -> act { focusLost() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> act { focusLostTransient() }
        }
    }

    private fun run(acts: List<Act>) {
        for (a in acts) when (a) {
            is Act.PlayLine -> {
                Log.i(TAG, "LINE ${a.storyId} ${a.line}")
                sound("stories/${a.storyId}/%02d.mp3".format(a.line + 1)) { act { lineFinished() } }
            }
            is Act.PlayClip -> {
                Log.i(TAG, "CLIP ${a.name}")
                sound("ace/${a.name}.mp3") { act { clipFinished() } }
            }
            Act.StopSound -> {
                Log.i(TAG, "STOP")
                player.reset()
            }
            Act.RequestFocus -> when (val result = audio.requestAudioFocus(focus)) {
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> act { focusGained() }
                AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> Log.i(TAG, "FOCUS delayed")
                else -> {
                    Log.w(TAG, "FOCUS denied $result")
                    act { pause() }
                }
            }
            Act.AbandonFocus -> audio.abandonAudioFocusRequest(focus)
            is Act.Popup -> {
                alerts++
                popup(a.alert)
            }
            Act.AlertCut -> {
                Log.i(TAG, "ALERT cut")
                policy?.cut()
                naviPolicy?.cut()
            }
            is Act.Status -> showStatus(a)
            is Act.WakeIn -> {
                Log.i(TAG, "HOLD ${a.ms}ms")
                handler.postDelayed({ act { holdOver() } }, a.ms)
            }
        }
        save()
        onChange?.invoke()
        tryTip()
    }

    private fun sound(path: String, done: () -> Unit) {
        onDone = done
        player.reset()
        player.setAudioAttributes(attrs)
        assets.openFd(path).use { player.setDataSource(it) }
        player.prepare()
        player.start()
    }

    private fun showStatus(status: Act.Status) {
        shown = status
        val meta = catalog.first { it.id == status.storyId }
        Log.i(TAG, "STATUS ${status.storyId} ${status.mode}")
        // 안내 때문에 잠깐 멈춘 것도 "재생 중"으로 보내 위젯 ⏸·운전대 키·앱 버튼이 모두 "끄기"가 되게 하고, 둘째 줄로 까닭을 보인다.
        val sub = if (status.mode == Mode.WAITING) WAIT_NOTE else meta.title
        session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, meta.title)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, statusText(status.mode))
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, sub)
            .apply { if (status.mode == Mode.WAITING) putString(MediaMetadata.METADATA_KEY_ARTIST, WAIT_NOTE) }
            .build())
        val state = if (status.mode == Mode.OFF) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_PLAY_FROM_MEDIA_ID)
            .addCustomAction(PlaybackState.CustomAction.Builder(ASK_ACTION, "물어보기", android.R.drawable.ic_btn_speak_now).build())
            .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
            .build())
        if (status.mode == Mode.OFF) {
            // 끄면 알림창에 미디어 알림을 남기지 않는다. 서비스는 차 미디어 앱이 붙잡고 있어 세션은 남는다.
            if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
            return
        }
        if (foreground) {
            notes.notify(MEDIA_NOTE, mediaNote())
            return
        }
        started = true
        try {
            startForegroundService(Intent(this, StoryService::class.java))
            foreground = true
        } catch (e: IllegalStateException) { // 뒤에 있을 때 안드로이드 12+가 막는다. 소리는 그대로 나온다.
            Log.w(TAG, "FOREGROUND denied", e)
        }
    }

    private fun statusText(mode: Mode) = if (mode == Mode.OFF) "꺼짐" else "재생 중"

    private fun mediaNote(): Notification {
        val status = shown ?: Act.Status(catalog[n.index].id, n.mode)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, MEDIA_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(catalog.first { it.id == status.storyId }.title)
            .setContentText(if (status.mode == Mode.WAITING) WAIT_NOTE else statusText(status.mode))
            .setContentIntent(open)
            .setOngoing(true)
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken))
            .build()
    }

    private fun popup(alert: Alert) = popup(alert.top, alert.sub)

    // 큰 갈림길 미리 알림과 먼저 알림이 같은 헤드업 카드를 쓴다.
    private fun popup(top: String, sub: String?) {
        Log.i(TAG, "POPUP $top / $sub")
        notes.notify(ALERT_NOTE, Notification.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(top)
            .setContentText(sub)
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setTimeoutAfter(POPUP_MS)
            .build())
    }

    private fun save() {
        prefs.edit()
            .putString("story", catalog[n.index].id)
            .putInt("line", n.line)
            .putStringSet("heard", n.heard)
            .putBoolean("talkLess", n.talkLess)
            .putString("serialStory", n.serialAt?.first)
            .putInt("serialLine", n.serialAt?.second ?: 0)
            .putBoolean("started", started)
            .apply()
    }

    private fun startRoute() {
        Log.i(TAG, "ROUTE start")
        routeStarted()
        handler.removeCallbacks(routeTick)
        policy = AlertPolicy(route.branch)
        routeSec = 0
        tickRoute()
    }

    // shortcut: 표본 시간표로 거리를 만든다, NaviHelper를 쓸 수 있으면 onTBTInfo의 거리를 policy.check에 넣고 이 표본 주행을 지운다.
    private fun tickRoute() {
        val tick = route.tick(routeSec)
        tick.nav.forEach(::speakNav)
        if (policy?.check(tick.distanceM, signals.turn, n.canAlert) == true) {
            Log.i(TAG, "ALERT at ${tick.distanceM}m")
            act { startAlert(Alert.PLAIN) }
        }
        if (routeSec++ < route.branchAt) handler.postDelayed(routeTick, 1000) else Log.i(TAG, "ROUTE end")
    }

    // 목록에서 처음 나오는 큰 오른쪽 분기까지의 거리로 돈다. 거리가 다시 늘면 다음 분기로 넘어간 것이라 정책을 새로 만든다.
    // shortcut: "오른쪽 분기" 녹음만 있어 오른쪽만 알린다, 왼쪽 문구를 녹음하면 LEFT도 넣는다.
    // [미확인] 이동 중 갱신. 거리 단위 m는 에뮬레이터 시험 2번에서 확인했다.
    private fun onTbt(list: List<TBTInfo>) {
        val d = rightBranchDistance(list)
        naviNextM = list.firstOrNull()?.distance?.toInt() // 깜빡이 알림: 다음 회전·분기가 가까우면 일부러 켠 것
        Log.i(TAG, "TBT ${list.firstOrNull()?.type} ${list.firstOrNull()?.distance} n=${list.size} rightBranch=$d")
        if (d == null) {
            naviPolicy = null
            return
        }
        if (naviPolicy == null || d > naviDistance + 100) naviPolicy = AlertPolicy(Turn.RIGHT)
        naviDistance = d
        if (naviPolicy?.check(d, signals.turn, n.canAlert) == true) {
            Log.i(TAG, "ALERT at ${d}m (navi)")
            act { startAlert(Alert.PLAIN) }
        }
    }

    // shortcut: 표본 시간표로 다음 안내까지 시간을 구한다, NaviHelper를 쓸 수 있으면 onTBTInfo의 남은 거리와 차 속도로 어림한다.
    private fun nextNavIn(): Long? =
        if (policy == null) null else route.navAt.map { ((it - routeSec) * 1000).toLong() }.filter { it > 0 }.minOrNull()

    private fun speakNav(i: Int) {
        Log.i(TAG, "ROUTE nav $i")
        sendBroadcast(Intent("app.passengerace.navsim.SPEAK")
            .setComponent(ComponentName("app.passengerace.navsim", "app.passengerace.navsim.NavReceiver"))
            .putExtra("n", i)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)) // 화면 없는 앱이라 한 번도 실행되지 않은 상태로 남아 있다
    }

    // 진짜 기어는 차 속성 GEAR_SELECTION에서 읽는다(CAR_POWERTRAIN은 normal 권한). 등록하면 지금 값이 한 번 온다.
    private fun watchGear() {
        val c = Car.createCar(this)
        car = c
        val props = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        props.registerCallback(object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                val gear = when ((value.value as? Int) ?: return) {
                    VehicleGear.GEAR_UNKNOWN -> return
                    VehicleGear.GEAR_PARK -> Gear.P
                    VehicleGear.GEAR_REVERSE -> Gear.R
                    VehicleGear.GEAR_NEUTRAL -> Gear.N
                    else -> Gear.D // GEAR_DRIVE와 수동 단(1~9단)
                }
                handler.post {
                    Log.i(TAG, "GEAR $gear")
                    SampleSignals.gear = gear
                    signals.onChange?.invoke()
                }
            }

            override fun onErrorEvent(propertyId: Int, areaId: Int) {
                Log.w(TAG, "GEAR error $propertyId")
            }
        }, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        // 밤 신호는 Vehicle SDK에 없어 차 속성으로 읽는다(CAR_EXTERIOR_ENVIRONMENT는 normal 권한).
        runCatching {
            props.registerCallback(object : CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(value: CarPropertyValue<*>) {
                    val on = value.value as? Boolean ?: return
                    handler.post { setNight(on) }
                }

                override fun onErrorEvent(propertyId: Int, areaId: Int) {
                    Log.w(TAG, "NIGHT error $propertyId")
                }
            }, VehiclePropertyIds.NIGHT_MODE, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        }.onFailure { Log.w(TAG, "NIGHT unavailable", it) }
    }

    // ---- 옆자리 안내 ----

    /** 물어보기. topic이 있으면(차 음성 비서) 듣지 않고 바로 답을 고른다. 이미 안내 중이면 처음부터 다시 듣는다. */
    fun ask(topic: String? = null) {
        Log.i(TAG, "HELP ask topic=${topic != null}")
        quiet()
        if (!topic.isNullOrBlank()) return answer(topic)
        setHelp(HelpState(HelpStep.LISTENING))
        step { say("listen") { step { listen(::answer) } } }
    }

    /** 그만 듣기, 이야기 이어 듣기. */
    fun stopHelp() = endAside()

    /** "김 서림 제거 켜기"·"괜찮아요" 단추. */
    fun answerDefrost(yes: Boolean) {
        if (help.step != HelpStep.ASK_DEFROST) return
        quiet()
        if (yes) defrost() else step { say("ok", ::endAside) }
    }

    private fun answer(heard: String?) {
        if (heard.isNullOrBlank()) return step { say("not_heard", ::endAside) }
        val a = book.match(heard)
        Log.i(TAG, "HELP match ${a?.id}")
        setHelp(HelpState(HelpStep.ANSWER, heard, a))
        if (a == null) return step { say("no_answer", ::endAside) }
        step { say(a.id) { if (a.action == "defrost") askDefrost() else endAside() } }
    }

    private fun askDefrost() {
        setHelp(help.copy(step = HelpStep.ASK_DEFROST))
        step { say("ask_defrost") { step { listen { if (book.yes(it)) defrost() else step { say("ok", ::endAside) } } } } }
    }

    // 운전자가 "네" 했을 때만 부른다.
    private fun defrost() {
        val hvac = vehicle?.getHVAC() ?: return step { say("defrost_fail", ::endAside) }
        Log.i(TAG, "DEFROST set")
        val failed: (Throwable) -> Unit = {
            Log.w(TAG, "DEFROST failed", it)
            handler.post { step { say("defrost_fail", ::endAside) } }
        }
        runCatching {
            hvac.setHvacDefroster(front, true, { _, on: Boolean? ->
                handler.post {
                    Log.i(TAG, "DEFROST done $on")
                    step { say(if (on == true) "defrost_on" else "defrost_fail", ::endAside) }
                }
            }, failed)
        }.onFailure(failed)
    }

    private fun setHelp(state: HelpState) {
        help = state
        onChange?.invoke()
    }

    /** 잠깐 쓰는 소리 권한을 받은 뒤 한 단계를 한다. 내비 안내가 끼어들면 멈췄다가 이 단계를 처음부터 다시 한다. */
    private fun step(block: () -> Unit) {
        asideStep = block
        if (asideHeld) return block()
        when (val r = audio.requestAudioFocus(asideFocus)) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                asideHeld = true
                block()
            }
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> Log.i(TAG, "ASIDE delayed")
            else -> {
                Log.w(TAG, "ASIDE denied $r")
                endAside()
            }
        }
    }

    private fun onAsideFocus(change: Int) {
        Log.i(TAG, "ASIDE focus $change")
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                asideHeld = true
                asideStep?.invoke()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                asideHeld = false
                quiet()
            }
            AudioManager.AUDIOFOCUS_LOSS -> endAside()
        }
    }

    private fun say(clip: String, then: () -> Unit) {
        Log.i(TAG, "ASIDE $clip")
        voiceDone = then
        voice.reset()
        voice.setAudioAttributes(attrs)
        assets.openFd("ace/$clip.mp3").use { voice.setDataSource(it) }
        voice.prepare()
        voice.start()
    }

    // 부를 때만 마이크를 켠다. 6초 안에 말이 없으면 못 들은 것으로 본다. 말하는 중에는 기다림을 늘린다.
    private fun listen(then: (String?) -> Unit) {
        Log.i(TAG, "LISTEN")
        hearing = then
        handler.removeCallbacks(hearTimeout)
        handler.postDelayed(hearTimeout, LISTEN_MS)
        runCatching { stt?.request() }.onFailure {
            Log.w(TAG, "STT request failed", it)
            heard(null)
        }
    }

    /** 인식 결과. 시험용 입구도 여기로 들어온다. 들은 말은 답을 고르는 데만 쓰고 저장하지 않는다. */
    private fun heard(text: String?) {
        val then = hearing ?: return
        hearing = null
        handler.removeCallbacks(hearTimeout)
        runCatching { stt?.stop() }
        Log.i(TAG, if (debuggable) "HEARD $text" else "HEARD ${text?.length ?: 0}자") // 릴리스 빌드는 들은 말을 로그에 남기지 않는다
        then(text)
    }

    private fun quiet() {
        voiceDone = {}
        voice.reset()
        if (hearing != null) {
            hearing = null
            handler.removeCallbacks(hearTimeout)
            runCatching { stt?.stop() }
        }
    }

    private fun endAside() {
        quiet()
        asideStep = null
        if (help.step != HelpStep.OFF) setHelp(HelpState())
        tips.next(true)?.let { return sayTip(it) } // 줄 선 먼저 알림은 소리 권한을 쥔 채로 바로 잇는다
        asideHeld = false
        audio.abandonAudioFocusRequest(asideFocus)
    }

    /** 먼저 알림 신호. 차 신호와 시험용 입구가 부른다. */
    private fun tipLevel(id: String, on: Boolean) {
        tips.level(id, on)
        tryTip()
    }

    // 순서: 내비 안내 > 큰 갈림길 미리 알림 > 먼저 알림 > 이야기. 이야기가 WAITING이면 내비나 큰 갈림길 알림이 말하는 중이다.
    // shortcut: 먼저 알림(6초 안팎)이 말하는 동안 온 큰 갈림길 알림은 끝날 때까지 기다린다(그 사이 1000m 안에 들면 못 한다), 실제 주행에서 겹치면 먼저 알림을 끊고 갈림길부터 말하게 바꾼다.
    private fun tryTip() {
        if (asideStep != null || help.step != HelpStep.OFF) return
        tips.next(!(n.on && n.mode == Mode.WAITING))?.let(::sayTip)
    }

    private fun sayTip(id: String) {
        Log.i(TAG, "TIP $id")
        val text = book.tips[id].orEmpty()
        popup(text.substringBefore(". ") + if (". " in text) "." else "", text.substringAfter(". ", "").ifEmpty { null }) // 제목은 첫 문장, 본문은 나머지
        step { say(id, ::endAside) }
    }

    private fun startStt() {
        val s = SpeechToText(this, ai.pleos.playground.stt.constant.Mode.ON_DEVICE)
        stt = s
        s.addListener(object : ResultListener {
            override fun onUpdated(text: String, completed: Boolean) {
                handler.post {
                    if (completed) heard(text) else if (hearing != null) {
                        handler.removeCallbacks(hearTimeout)
                        handler.postDelayed(hearTimeout, LISTEN_MS)
                    }
                }
            }

            override fun onError(type: ErrorType) {
                Log.w(TAG, "STT error $type")
                handler.post { heard(null) }
            }

            override fun onUpdatedEpdData(start: Long, end: Long) {}
            override fun onStartedRecognition() {
                Log.i(TAG, "STT start")
            }

            override fun onEndedRecognition() {
                Log.i(TAG, "STT end")
            }

            override fun onReady() {}
            override fun onUpdatedRms(rms: Double) {}
        })
        s.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                Log.i(TAG, "STT ready")
                s.setLocale(Locale.KOREA)
            }

            override fun onFailure(type: ErrorType) {
                Log.w(TAG, "STT init failed $type")
            }
        })
    }

    // 차 신호로 먼저 알림. 값마다 처음 온 것은 기준으로만 쓴다(TipPolicy).
    private fun watchCar() {
        val v = Vehicle(this)
        vehicle = v
        v.initialize()
        val fail: (Throwable) -> Unit = { Log.w(TAG, "VEHICLE call failed", it) } // 신호마다 따로 받아서 하나가 막혀도 나머지는 돈다
        // 경고등(registerTelltales)은 공개 문서에 없는 PRIVATE_CAR_SAFETY 권한을 요구해(11-08 에뮬레이터 확인) 받지 않는다. 주최 측 규칙: 공개 문서의 API만.
        runCatching {
            v.getFuel().registerFuelPercent(object : FuelPercentListener {
                override fun onFailed(e: Exception) = fail(e)

                override fun onFuelPercentUpdated(percent: Int?) {
                    handler.post {
                        fuelPercent = percent
                        fuelLevel()
                    }
                }
            })
        }.onFailure(fail)
        runCatching {
            v.getEvBattery().registerEvBatteryStateOfCharge(object : EvBatteryStateOfChargeListener {
                override fun onFailed(e: Exception) = fail(e)

                override fun onEvBatteryStateOfChargeUpdated(percent: Int?) {
                    handler.post {
                        Log.i(TAG, "CAR battery $percent")
                        if (percent != null) tipLevel(TIP_BATTERY, percent <= BATTERY_LOW_PERCENT)
                    }
                }
            })
        }.onFailure(fail)
        // 가장 빠른 단계는 차마다 달라서 차가 알려 주는 단계 목록에서 고른다. 와이퍼 상태가 바뀔 때 단계를 읽는다.
        runCatching {
            v.getWiper().checkWiperSwitchCapabilities({ areas ->
                handler.post { fastestWiper = areas.values.flatten().filter { it.name.startsWith("CONTINUOUS") }.maxByOrNull { it.ordinal } }
            }, fail)
        }.onFailure(fail)
        runCatching {
            v.getWiper().registerWiperState(front, object : WiperStateListener {
                override fun onFailed(e: Exception) = fail(e)

                override fun onWiperStateUpdated(state: WiperState?) {
                    handler.post {
                        wiperOn = state == WiperState.ON
                        checkLights()
                        readWiper()
                    }
                }
            })
        }.onFailure(fail)
        runCatching {
            v.getLight().registerLowBeamLightState(object : LightStateListener {
                override fun onFailed(e: Exception) = fail(e)

                override fun onLightStateUpdated(state: LightState?) {
                    handler.post {
                        lowBeamOn = state == LightState.ON
                        checkLights()
                    }
                }
            })
        }.onFailure(fail)
        runCatching {
            v.getTurnSignal().registerTurnSignalState(object : TurnSignalStateListener {
                override fun onFailed(e: Exception) = fail(e)

                override fun onTurnSignalStateUpdated(value: TurnSignalValue?) {
                    handler.post {
                        Log.i(TAG, "CAR turn $value")
                        turnSince = if (value == null || value == TurnSignalValue.OFF) null else turnSince ?: SystemClock.elapsedRealtime()
                        checkTurn()
                    }
                }
            })
        }.onFailure(fail)
        runCatching {
            v.getOdometer().registerTripCurrentDrivingTime(object : DrivingTimeListener {
                override fun onFailed(e: Exception) = fail(e)

                override fun onDrivingTimeUpdated(minutes: Int?) {
                    handler.post {
                        Log.i(TAG, "CAR driving $minutes")
                        if (minutes != null) tipLevel(TIP_REST, minutes >= REST_AFTER_MIN)
                    }
                }
            })
        }.onFailure(fail)
    }

    // 단계(Low→High)가 바뀌어도 와이퍼 상태는 ON 그대로라 알림이 오지 않는다(에뮬레이터 실측). 켜져 있는 동안 3초마다 단계를 읽는다.
    private fun readWiper() {
        handler.removeCallbacks(wiperPoll)
        runCatching {
            vehicle?.getWiper()?.getCurrentWiperSwitch(front, { sw ->
                handler.post {
                    Log.i(TAG, "CAR wiper $sw fastest=$fastestWiper")
                    tipLevel(TIP_RAIN, sw == fastestWiper)
                }
            }, { Log.w(TAG, "VEHICLE call failed", it) })
        }
        if (wiperOn) handler.postDelayed(wiperPoll, WIPER_POLL_MS)
    }

    private fun setNight(on: Boolean) {
        Log.i(TAG, "CAR night $on")
        night = on
        checkLights()
    }

    // 스위치(자동인지)는 받는 알림이 없어서 볼 때마다 읽는다.
    private fun checkLights() {
        val light = vehicle?.getLight() ?: return
        runCatching {
            light.getLowBeamLightSwitch({ sw ->
                handler.post {
                    val auto = sw == LightSwitch.AUTOMATIC
                    Log.i(TAG, "CAR lights on=$lowBeamOn switch=$sw night=$night wiper=$wiperOn")
                    tipLevel(TIP_RAIN_LIGHTS, lightsNeeded(wiperOn, signals.gear, lowBeamOn, auto))
                    tipLevel(TIP_NIGHT_LIGHTS, lightsNeeded(night, signals.gear, lowBeamOn, auto))
                }
            }, { Log.w(TAG, "VEHICLE call failed", it) })
        }
    }

    // 깜빡이가 켜져 있는 동안 5초마다 본다.
    private fun checkTurn() {
        handler.removeCallbacks(turnCheck)
        val since = turnSince
        tipLevel(TIP_TURN_SIGNAL, turnSignalForgotten(since, SystemClock.elapsedRealtime(), signals.gear, naviNextM))
        if (since != null) handler.postDelayed(turnCheck, TURN_CHECK_MS)
    }

    // 차를 세웠을 때(D→P, 도착) 열린 창이 하나라도 있으면 알린다.
    private fun readWindows() {
        val window = vehicle?.getWindow() ?: return
        for (w in listOf(WindowValue.ROW_1_LEFT, WindowValue.ROW_1_RIGHT, WindowValue.ROW_2_LEFT, WindowValue.ROW_2_RIGHT)) runCatching {
            window.getCurrentWindowPosition(WindowArea(w), { pos ->
                handler.post {
                    Log.i(TAG, "CAR window $w $pos")
                    if (pos != null && pos > 0) tipLevel(TIP_WINDOW_PARKED, true)
                }
            }, { Log.w(TAG, "VEHICLE call failed", it) })
        }
    }

    private fun fuelLevel() = tipLevel(TIP_FUEL, (fuelPercent ?: 100) <= FUEL_LOW_PERCENT)

    private fun hasStory(id: String) = try {
        assets.open("stories/$id/index.json").close()
        true
    } catch (e: IOException) {
        false
    }

    private fun storyJson(id: String) = JSONObject(assets.open("stories/$id/index.json").bufferedReader().readText())

    private fun loadCatalog(): List<StoryMeta> {
        return ORDER.filter(::hasStory).map { id ->
            val json = storyJson(id)
            val arr = json.getJSONArray("ms")
            StoryMeta(id, json.getString("title"), json.getString("source"), json.optInt("part"), (0 until arr.length()).map(arr::getInt))
        }
    }

    private fun loadRoute(): SampleRoute {
        val json = JSONObject(assets.open("sample_route.json").bufferedReader().readText())
        val nav = json.getJSONArray("navAt")
        return SampleRoute(
            (0 until nav.length()).map { nav.getDouble(it) },
            json.getInt("branchAt"),
            json.getInt("speedMps"),
            Turn.valueOf(json.getString("branch")),
        )
    }
}
