package app.passengerace.navsim

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log

/** 내비 음성을 흉내 낸다. 소리 권한을 잠깐 얻어 안내를 틀고 놓는다. */
class NavReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val n = intent.getIntExtra("n", 0)
        val pending = goAsync()
        val audio = context.getSystemService(AudioManager::class.java)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .build()
        val player = MediaPlayer()
        player.setAudioAttributes(attrs)
        context.assets.openFd("nav_$n.wav").use { player.setDataSource(it) }
        player.prepare()
        val done = {
            player.release()
            audio.abandonAudioFocusRequest(focus)
            Log.i("ACE", "NAV end $n")
            pending.finish()
        }
        player.setOnCompletionListener { done() }
        player.setOnErrorListener { _, _, _ -> done(); true }
        Log.i("ACE", "NAV start $n focus=${audio.requestAudioFocus(focus)}")
        player.start()
    }
}
