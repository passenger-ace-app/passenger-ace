package app.passengerace

import android.content.ComponentName
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Looper
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.time.Duration

/** Robolectric 위에서 실제 앱 클래스를 띄운다. 소리는 시뮬레이터 대역이라 실제 소리가 아니다. 길이는 전부 8초다. */
class Rig {
    val app get() = RuntimeEnvironment.getApplication()
    val prefs get() = app.getSharedPreferences("ace", 0)
    val audio get() = shadowOf(app.getSystemService(AudioManager::class.java))
    lateinit var service: ServiceController<StoryService>
    lateinit var activity: ActivityController<MainActivity>

    val svc get() = service.get()
    val aceLog get() = ShadowLog.getLogsForTag("ACE").map { it.msg }

    init {
        SampleSignals.gear = Gear.P
        SampleSignals.turn = Turn.NONE
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(8_000, 0) }
        useNavi = false
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    fun startService() {
        service = Robolectric.buildService(StoryService::class.java).create()
    }

    fun openActivity(): MainActivity {
        val binder = svc.onBind(Intent(app, StoryService::class.java))
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, StoryService::class.java), binder)
        activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        idle()
        return activity.get()
    }

    fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** 앱이 등록한 실제 소리 권한 처리기를 부른다. */
    fun focus(change: Int) = audio.lastAudioFocusRequest.listener.onAudioFocusChange(change)

    /** Robolectric은 필터 없는 수신기에 전달하지 않아서 실제 수신기를 직접 부른다. */
    fun signal(action: String, fill: Intent.() -> Unit = {}) =
        SampleSignalsReceiver().onReceive(app, Intent(action).apply(fill))
}

/** Robolectric의 세션은 값을 돌려주지 않아서, 앱이 세션에 넣은 값을 적어 둔다. */
@Implements(MediaSession::class)
class RecordingSession {
    @field:RealObject
    private lateinit var real: MediaSession

    @Implementation
    fun setMetadata(metadata: MediaMetadata?) {
        Companion.metadata = metadata
        Shadow.directlyOn<Any?, MediaSession>(real, MediaSession::class.java, "setMetadata", ClassParameter.from(MediaMetadata::class.java, metadata))
    }

    @Implementation
    fun setPlaybackState(state: PlaybackState?) {
        Companion.state = state
        Shadow.directlyOn<Any?, MediaSession>(real, MediaSession::class.java, "setPlaybackState", ClassParameter.from(PlaybackState::class.java, state))
    }

    companion object {
        var metadata: MediaMetadata? = null
        var state: PlaybackState? = null
    }
}
