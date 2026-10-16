# Technical reference

Detailed usage, build, and implementation notes for Voice SIP Agent. See the
[README](README.md) for installation, SIP server configuration, supported devices, and native dependency build instructions.

- [Call access and takeover](#call-access-and-takeover)
- [Audio formats and routing](#audio-formats-and-routing)
- [Native dependencies](README.md#native-dependencies)
- [Native build details](#native-build-details)

## Call access and takeover

Open the app, tap **Grant Shizuku** and allow access. The app then runs its setup automatically (appops,
Telecom companion override where applicable, Doze allowlist, runtime permissions) and repeats it after upgrades.
A health check runs every 60 s and retries setup while idle if takeover is enabled and companion call access is unavailable.

On Android 11, turn **Take over calls** on (or tap **Enable takeover**) to grant default-dialer call access.
With Shizuku connected, this saves your previous dialer and changes the role directly, without the
system role dialog. Changes wait for current calls to end and can be cancelled while waiting.
Without Shizuku, the explicit switch-on action opens Android's role-selection dialog.
Startup, setup and health checks never acquire the role. On newer versions, Automatic call access keeps companion
integration unless the app already holds the dialer role; Advanced settings can select either mode explicitly.

**Take over calls** is the master switch. In default-dialer mode, switching it on also saves your previous
dialer and acquires the role. Switching off waits for current calls and bridge cleanup, then stops bridging
and companion integration and restores your previous dialer through Shizuku.
If that dialer is unavailable, the app attempts the system fallback. **Choose dialer in Settings**
remains available in Advanced settings and appears on restoration failure for manual recovery.
**Keep default-dialer role when gateway is off** in Advanced disables automatic restoration on the next
switch-off. Independent **Become default dialer** and **Restore previous dialer** actions are also in Advanced.
Role changes made in Settings leave takeover unchanged; a pending
request will not replace a different dialer selected while it was waiting. The saved return dialer
survives process restarts, but pending role changes are never replayed automatically after restart.
Toggling takeover again cancels the pending handoff; existing calls keep their bridge.

On Android 12+, Automatic returns to companion mode after restoration. Select **Default dialer** in Advanced
to acquire the role on each switch-on.
In companion mode, switching takeover off also stops the foreground service and removes its notification;
idle gateway standby keeps both running. In dialer mode, the service runs while this app is the default
dialer. Owned calls finish cleanup before the service stops.

Advanced settings are collapsed by default. They contain call-access and cellular-audio choices, dialer-role
controls and retention, automatic handset suppression when supported, loopback, debug logging, and diagnostics.
The call log remains visible outside Advanced.

## Audio formats and routing

The default pjsua2 SIP stack prefers mono Opus (16 kHz PCM, 20 ms frames, 20 kbit/s target), with G.711
(PCMU/PCMA) fallback. Android capture/injection and the bridge use 16 kHz mono PCM for pjsua2, and 8 kHz
for the built-in Kotlin/G.711 stack. Each 20 ms socket packet is respectively 640 or 320 bytes. PJMEDIA
resamples between the 16 kHz bridge and G.711. There is no automatic 16-to-8 kHz fallback: Android
open/start/read/write failures are reported and terminate the tap. A 16 kHz stream can still contain
upsampled narrowband audio, depending on the cellular call and device. Opus uses the standard
`opus/48000/2` SDP mapping with `maxplaybackrate=16000` and `sprop-maxcapturerate=16000`. G.722 is disabled.

Opus VAD/DTX is enabled by default to reduce cellular-to-SIP silence payload bandwidth; SDP advertises
`usedtx=1` to request DTX from the SIP peer too. The peer's negotiated DTX preference is honored.
PCMU/PCMA keep VAD disabled and transmit continuously. PCM capture and bridging continue every 20 ms.

Quiet speech, call-progress tones, hold music and prolonged silence with PBX/NAT timeouts still need
device testing. Acoustic echo cancellation belongs at the microphone endpoint.
The 16 kHz tap and Opus calls have not yet been verified on a device.

Direct telephony audio skips the modern redirection helpers and uses voice-call stream mute for handset output,
including on API 30–32; supported redirection taps retain mode-based suppression. The helper restores its
changes when the tap closes and preserves an already-muted stream. Device policy/HAL support and both
audio directions still need testing with suppression enabled. If the helper is forcibly killed, stream
mute can remain active; raising the call volume can unmute it.

For local listening, turn **Mute local call output** off in **Advanced settings** before the call.
Downlink output follows the phone's current call audio route (earpiece, speaker, or headset) and volume.
The handset microphone stays muted while bridging, so local listening does not enable local speech.

### Potential optimization: PJSIP audio switchboard

PJSIP's [audio switchboard](https://docs.pjsip.org/en/latest/specific-guides/audio/switchboard.html)
(`PJMEDIA_CONF_USE_SWITCH_BOARD`) may be an optimization for this gateway. It remains disabled because
G.711's 8 kHz PCM needs conversion to the fixed 16 kHz bridge; enabling it would require explicit resampling
or coordinated per-call tap, media-port and clock rates.

## Native build details

See [building from source](README.md#building-from-source) and [native dependencies](README.md#native-dependencies)
for build commands, ABI selection, and dependency rebuild behavior.

Native C/C++ code uses ThinLTO, hidden visibility and section garbage collection. The final export map
exposes only PJSUA2 JNI entry points; Mbed TLS, Opus and PJSIP symbols are local. The build checks exports
and writes `pjsua2.exports.txt` under the ABI work directory. Exported JNI wrappers and the TLS backend's
cipher tables still retain reachable code; Java shrinking alone does not remove native wrappers.

`native/pjsua2-java-profile.i` trims the Java/JNI surface by feature group while keeping upstream
`pjsua2.i`: video, buddies, file playback/recording, AI media ports, tone generators and JSON persistence
are excluded. `Endpoint` uses its native default callbacks; `Account`, `Call`, `AudioMediaPort` and
`LogWriter` retain Java directors, including their default callbacks. SIP/TLS/SRTP configuration, codec
controls, `AudDevManager.setNullDev()` and bulk PCM copies remain available. Restore an excluded group
in that file if needed, or the `Endpoint` director if subclassing it. On PJSIP upgrades, check the
exclusions against upstream names, rebuild the bindings and app (including release), and verify SIP
callbacks and audio on a device. This profile
reduces JNI reachability; it does not remove all underlying native implementations.

Release builds enable R8 with consumer rules preserving SWIG bindings/director callbacks and Shizuku's
reflectively loaded service constructor. Other app and dependency code can be optimized. Validate a release
APK's Shizuku startup, SIP callbacks and TLS/SRTP audio before relying on new shrinking rules.

Release builds are signed only when `vsa.signing.storeFile`, `vsa.signing.storePassword`, `vsa.signing.keyAlias`
and `vsa.signing.keyPassword` are set in `~/.gradle/gradle.properties`; otherwise `assembleRelease` produces an
unsigned APK.
