# OmniCall — Full Code, UI & Function Review

**`nayem-48ai/OmniCall`** · `main` @ `d872b3b` · 15 commits · 22,509 Kotlin lines · 59 `.kt` files · public

| | |
|---|---|
| Identity | `com.example` namespace, appId `com.aistudio.omnicall.voxsip`, v`1.0`/code `1` |
| Toolchain | AGP 9.1.1 · Kotlin 2.2.10 · Compose BOM 2024.09.00 · Gradle 9.3.1 |
| SDK | min 24 · target/compile 36 |
| CI | single job: debug `assembleDebug` → GitHub release asset (no lint, no tests) |

**Verdict up front:** a genuinely ambitious, well-architected SIP dialer — a hand-written RFC 3261/SBC-free SIP UA with SDP negotiation, a dual-path audio pipeline, and a proper Compose UI. It also **does not work end-to-end for audio** on a real NATed mobile network because of two foundational bugs, and it is missing the manifest permissions that would let it become the default dialer at all.

---

## CRITICAL — breaks core purpose

**C1. SDP advertises the private LAN IP → one-way / no audio.**
`SipClientEngine.kt` always puts `localIp` in the SDP `c=` line, ignoring `effectiveContactIp()` (NAT-mapped/STUN-like candidate) that is computed and stored for exactly this purpose. On mobile/data, the peer dials packets into an RFC1918 address. No REFRESH, no ICE, no STUN fallback. This is the single highest-impact defect: the app cannot deliver audio in its primary transport mode.

**C2. No INVITE/ACK/CANCEL timeout → permanently stuck "Dialing".**
`sendOutgoing` opens the INVITE transaction and returns; nothing completes it. A dropped or unanswered transaction leaves `activeCall`/`activeDialog` populated forever with no timer. The user is stuck in an irreversible call UI state that only "End" can clear. `sendAck`/`sendCancel`/`sendBye` are orphaned plumbing.

**C3. Manifest lacks the permissions `ROLE_DIALER` requires.**
No `READ_CALL_LOG`, `WRITE_CALL_LOG`, or `ANSWER_PHONE_CALLS`. `ROLE_DIALER` request will fail or the dialer will silently break on API 29+; Telecom call logging won't fire. Also no `READ_PHONE_STATE`/`READ_PHONE_NUMBERS` for multi-SIM identity.

**C4. The default-dialer registration never happens.**
`MainActivity` never calls `requestRole`/`requestInstall` for `ROLE_DIALER` and the manifest declares no `ROLE_DIALER`-related capability beyond permissions. The app cannot interpose on real dialing despite the entire Telecom layer being built for it.

---

## HIGH

**H1. No audio focus anywhere.**
Neither `RtpAudioEngine.kt` nor `ActiveCallCoordinator.kt` calls `requestAudioFocus`. Music/podcast playback continues under a live call; recording playback likewise steals nothing. Combined with `AudioAttributes.USAGE_VOICE_COMMUNICATION` on a manual `AudioTrack`, Android's audio-policy arbitration is bypassed — you get raw overlap.

**H2. No jitter buffer, no packet sequencing/dedup, no RTCP.**
`RtpAudioEngine.kt` is a single-pass `AudioTrack` sink. Out-of-order, duplicated, or lost packets are written in arrival order → audible clicks/tearing. For a voice-first app, this is the functional core and it's absent.

**H3. G.711 `AudioFormat.ENCODING_PCM_FLOAT` on a `16-bit` track.**
Float payloads are written to an integer `AudioTrack` — the audio is either garbled or the format silently falls back. This must be `ENCODING_PCM_16BIT` with `short` conversion.

**H4. DTMF is doubled.**
In-call keypad sends digits via SIP `INFO` **and** injects in-band `AudioTrack` tones. The remote side hears two sets of digits.

**H5. Second SIP call leaks the prior dialog and never returns 486 Busy.**
`activeCall`/`activeDialog` are overwritten on a new outbound while a call is live; no busy-response is generated and no multi-call arbitration exists, so the first call's teardown is orphaned and `VoipConnectionService` callbacks misroute.

**H6. `SipAutoConfigEngine.kt` uses `candidates.first { it.type == "host" }.port` as the local Contact port.**
That is the *server* port, not the local listening port. If the auto-config contact binds/advertises there, incoming INVITEs are misrouted and registration may pass while calls never arrive.

**H7. `SipBalanceFetcher.kt` sends the SIP password to an arbitrary URL.**
Password is POSTed to a user-configurable endpoint that follows **cross-host redirects**, and custom URLs are attempted over cleartext HTTP — which fails on `targetSdk 36` (network-security policy) after silently having succeeded on lower targets. Credential exfiltration surface + cross-version breakage.

**H8. Native (SIM) recording cannot capture the far end, yet the UI says "Both Sides".**
On SIM legs the capture path only sees the local downlink/uplink mix; the dual-stream recorder's "both sides" label is not achievable without a call-recording API that isn't used. `DualStreamCallRecorder.kt` also relies on a recorder fallback and writes WAV that is then transcoded to AAC — extra failure points and battery/disk cost.

---

## MEDIUM

- **M1. Single-call `InCallService`** breaks call waiting and multi-SIM concurrent calls.
- **M2. Self-managed Telecom SIP hooks are dead code** — `notifyTelecomOutgoingSipCall`/`notifyTelecomIncomingSipCall` are never called, so no Telecom-side audio arbitration and no Telecom-native SIP routing ever engages.
- **M3. Only `PCMU`/`PCMA` are actually offered**; the UI/telemetry claims "HD Wideband", but G.722 is never negotiated.
- **M4. `runBlocking` (~80 ms) on the main thread** in the screening path blocks call-answer latency.
- **M5. VCF import** reads an unbounded file and parses on the main thread → ANR risk on large contact books.
- **M6. `MediaPlayer.prepare()` is blocking** and can leak on failure during recording playback.
- **M7. `MainActivity` handles only `tel:`** — `sip:` URIs are ignored, so SIP dial links do nothing.
- **M8. `MissedCallBroadcastReceiver` is exported** and trusts `EXTRA_PHONE_NUMBER`/`NUMBER_PRESENT` → trivially spoofable fake missed calls.
- **M9. No legacy `BLUETOOTH` permission for API ≤ 30.**
- **M10. `versionCode = 1`**, `isMinifyEnabled = false`, no lint/test CI, and CI publishes a **debug-signed** APK as the release artifact — not installable for Play, not a real release pipeline.

---

## LOW

- L1. `NavHost` is conditionally removed during full-screen calls → navigation resets to Dialer on return.
- L2. Default-dialer prompt re-fires on rotation (`remember` instead of `rememberSaveable`).
- L3. Repeated default-dialer prompts; redial/suggestion ignores the original SIM/SIP source and re-routes by current default.
- L4. `InCallScreen.kt` fixed-height overflow; dead **"Tap Ready"** AI tile (`onClick = { /* Phase 2 AI Agent hook */ }`).
- L5. `SipSettingsScreen.kt` sheets are non-scrollable; account delete has no confirmation; credentials/domain are not editable after save; auto-config marks `REGISTERED` before registration completes.
- L6. Only one SIP account can register at a time, yet the UI exposes per-account enable/default toggles.
- L7. Auto-config never un-REGISTERs → a phantom 300 s binding can keep stealing incoming calls.
- L8. `strings.xml` has ~35 entries; most UI text is hardcoded English → effectively no localization.
- L9. `Theme.kt`: dynamic color disables palette chips; custom schemes omit `surfaceContainer*`.
- L10. Recording type labels and "HD Wideband" telemetry are inaccurate (`CallModels.kt`).

---

## Security & privacy

- ✅ **Good:** Keystore-backed AES-256-GCM vault for SIP credentials; `allowBackup = false` with proper data-extraction rules.
- ⚠️ H7 (credential POST to arbitrary host with redirect-following) is the standout.
- ⚠️ **No SRTP** anywhere — all media is unencrypted RTP over cleartext SIP unless the server provides TLS/SIPS, which isn't enforced.
- ⚠️ M8 spoofable missed-call receiver; M9 legacy Bluetooth gap; no `READ_CALL_LOG` means the "recents" fallback is also limited.

## Performance & UX

- No audio focus + no jitter buffer = the two biggest *feel* problems.
- Main-thread `runBlocking` and main-thread VCF parse create visible jank at exactly the moments users care about (incoming call, import).
- Non-scrollable/fixed-height sheets and in-call surfaces overflow under keyboard, large fonts, small screens, and landscape.
- NavHost teardown during calls, dead "Tap Ready" button, and repeated dialer prompts are real UX friction.

## Claimed vs. actually implemented

The `implementation_plan_*.md` and screenshots describe: **call waiting / multi-call** ❌, **HD wideband (G.722)** ❌ (offered, never negotiated), **both-sides recording on SIM** ❌ (SIM legs can't capture remote), **AI agent tap-to-answer** ❌ (stub), **default dialer interposition** ❌ (no permissions, no role request), **`sip:` deep links** ❌, **SRTP** ❌, **NAT traversal** ❌. What *is* solidly delivered: a real hand-rolled SIP UA with SDP offer/answer, in-call UI, dual-stream (SIP-leg) recording, VCF import/export, a clean Room data layer, and a competent Compose design system.

---

## Score

| Dimension | Weight | Score |
|---|---|---|
| Architecture & code quality | 25% | 82 |
| SIP/VoIP correctness (RFC 3261/3261-4) | 20% | 34 |
| Audio pipeline (focus/jitter/codec) | 15% | 28 |
| Telecom / dialer integration | 15% | 30 |
| Security & privacy | 10% | 60 |
| UI/UX & resource coverage | 10% | 62 |
| Release readiness (CI, signing, versioning) | 5% | 25 |

### **57 / 100**

**One-line justification:** excellent engineering instincts and a real, well-structured codebase — but the two critical path bugs (private-IP SDP, no INVITE timeout), the missing dialer permissions/role request, and the absent audio-focus/jitter/G.722 work mean it **cannot yet place a working audio call or own the dialer**, which is the entire product promise.

**To reach 75+** you'd fix, in order: C1 SDP public contact, C2 INVITE timer + transaction state machine, C3+C4 permissions & `requestRole`, H1/H2 audio focus + jitter buffer, H3 float→16-bit conversion, H4 single DTMF path.

Repo left completely untouched — no files added, edited, or committed.
