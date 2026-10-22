package xyz.linplayer.app.ui.player.liuying

import android.app.Activity
import android.app.KeyguardManager
import android.content.*
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.PowerManager
import android.view.WindowManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import xyz.linplayer.app.data.LocalApp

/** 同时观察导航与Activity；亮屏仍须重新核验解锁，备用槽从不申请焦点。 */
@Composable
internal fun rememberLiuyingSession(): LiuyingPlaybackSession {
    val app = LocalApp.current
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val identity by app.session.collectAsState()
    val space by app.spaceRevision.collectAsState()
    val session = remember(identity, space) { LiuyingPlaybackSession(app, LiuyingMediaSlots(context.applicationContext)) }
    DisposableEffect(session, owner, context) {
        var current: Context? = context
        while (current is ContextWrapper && current !is Activity) current = current.baseContext
        val activity = current as? Activity
        val activityLifecycle = (activity as? LifecycleOwner)?.lifecycle
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val audio = context.getSystemService(AudioManager::class.java)
        var held = false
        var disposed = false
        val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
            if (!disposed) when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> { held = true; session.focusChanged(true) }
                AudioManager.AUDIOFOCUS_LOSS -> { held = false; session.focusChanged(false, permanent = true) }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                    { held = false; session.focusChanged(false) }
            }
        }
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setOnAudioFocusChangeListener(focusListener).setWillPauseWhenDucked(true).build()
        fun abandon() {
            audio.abandonAudioFocusRequest(focusRequest)
            held = false; session.focusChanged(false)
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        fun environment() {
            val resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                (activityLifecycle == null || activityLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            val unlocked = power.isInteractive && !keyguard.isKeyguardLocked && app.privateSpace.value
            session.environment(resumed, unlocked)
            if (!resumed || !unlocked) abandon()
        }
        session.onNeedFocus = {
            if (!disposed && session.foreground && session.unlocked) {
                if (!held) held = audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                session.focusChanged(held)
            }
        }
        session.onPlayingChanged = { playing ->
            if (playing) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                    session.focusChanged(false, permanent = true); abandon()
                } else if (intent.action == Intent.ACTION_SCREEN_OFF) {
                    session.environment(false, false); abandon()
                } else environment()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT); addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                session.environment(false, false); abandon()
            } else environment()
        }
        owner.lifecycle.addObserver(observer)
        if (activityLifecycle !== owner.lifecycle) activityLifecycle?.addObserver(observer)
        val memory = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            override fun onLowMemory() { session.trimMemory() }
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) session.trimMemory()
            }
        }
        context.applicationContext.registerComponentCallbacks(memory)
        environment()
        onDispose {
            disposed = true
            session.onNeedFocus = {}; session.onPlayingChanged = {}
            owner.lifecycle.removeObserver(observer)
            if (activityLifecycle !== owner.lifecycle) activityLifecycle?.removeObserver(observer)
            context.unregisterReceiver(receiver)
            context.applicationContext.unregisterComponentCallbacks(memory)
            abandon(); session.close()
        }
    }
    LaunchedEffect(session) {
        while (true) { session.tick(); delay(250) }
    }
    return session
}
