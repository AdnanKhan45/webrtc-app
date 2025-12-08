# WebRTC Audio/Video Calling App (Android · Kotlin)

A lightweight but production-grade WebRTC audio/video calling app using Firebase Firestore for signaling and FCM for incoming call notifications.
Supports STUN, TURN, reconnection, ICE restarts, and a complete call UI.

## 🚀 Core Features

- Peer-to-peer audio/video calls
- Camera switch, mic toggle, video toggle
- Local preview (PIP) + remote fullscreen
- STUN (host/srflx candidates)
- TURN fallback (udp/tcp/tls)
- Selected candidate stats logging
- Tested across:
   - Wi-Fi ↔ Mobile data switching
   - Full disconnect & reconnect
   - Host / srflx / relay transitions
- Incoming call push
- Full-screen incoming UI
- Foreground service ringtone
- Reconnection timeout fallback
- Handles remote peer leaving
- Works after app kill (via push)

## 🧩 Project Structure

```
app/
 ├─ webrtc/
 │   ├─ RtcClient.kt              # PeerConnection & ICE logic
 │   ├─ RtcDependencies.kt        # EglBase + PC factory
 │   ├─ LocalPreviewController.kt
 │   ├─ AudioController.kt
 │
 ├─ signaling/
 │   ├─ CallRepository.kt         # Firestore signaling
 │
 ├─ push/
 │   ├─ IncomingPushService.kt
 │   ├─ PushKeys.kt
 │
 ├─ ui/
 │   ├─ CallActivity.kt           # Main call UI
 │   ├─ IncomingCallActivity.kt   # Fullscreen incoming UI
 │   ├─ CallRingingService.kt     # Foreground ringtone service
 │
 ├─ App.kt                        # Notification channels + global deps
 ├─ activity_call.xml             # Call UI

```

## Contributing

This plugin is in early development. Feel free to open issues or submit pull requests on [GitHub](https://github.com/your-repo/flutter_voice_engine)!

## License

MIT License. See [LICENSE](LICENSE) for details.

## Author

Muhammad Adnan ([ak187429@gmail.com](mailto:ak187429@gmail.com))






