# VirtualDisplay feasibility without MediaProjection

## Question

Can a normal APK render an app-owned automotive UI—and possibly a third-party Activity—directly into a MediaCodec encoder Surface without MediaProjection?

## Platform result

### Own Activity: API exists, but this stock phone denies placement

`VERIFIED_FROM_OFFICIAL_DOC`: `DisplayManager.createVirtualDisplay()` renders a logical display into an application-supplied `Surface`. `VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY` prevents fallback mirroring of display 0. Combining it with `VIRTUAL_DISPLAY_FLAG_PUBLIC` makes a public display that contains only windows explicitly launched onto it. This avoids the privileged auto-mirroring path.

`VERIFIED_FROM_OFFICIAL_DOC`: `ActivityOptions.setLaunchDisplayId()` can target an eligible display on devices that advertise `FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS`. `ActivityManager.isActivityStartAllowedOnDisplay()` provides a preflight decision.

`OBSERVED_IN_LAB`: the connected Android 16 phone advertises both `android.software.activities_on_secondary_displays` and `android.hardware.usb.accessory`, but returned `false` for our own Activity on both public and private app-created VirtualDisplays. The app did not bypass that policy.

Candidate pipeline implemented in the lab APK:

```text
MediaCodec AVC encoder
    → createInputSurface()
    → DisplayManager.createVirtualDisplay(
          PUBLIC | OWN_CONTENT_ONLY | PRESENTATION,
          encoderSurface)
    → ask whether our resizeable SecondaryLabActivity may launch
    → when denied, show an app-owned Presentation on the display
    → Presentation draws animated GE13 LAB scene
    → MediaCodec output
    → Annex-B normalization + CSD cache
    → file and optional QDLink packetizer
```

The manifest requests no MediaProjection capability and the flow invokes no `MediaProjectionManager` API. The Presentation fallback is public Android API and proved sufficient for the custom-rendered UI.

### Third-party Activity: conditional and commonly restricted

`VERIFIED_FROM_OFFICIAL_DOC`: public display status alone does not guarantee cross-package Activity placement. AOSP's Android 10+ multi-display launch policy restricts an ordinary app from launching another package on its app-owned virtual display unless the caller has the relevant embedding privilege and the target allows embedding. `isActivityStartAllowedOnDisplay()` is the decisive runtime check.

Consequences:

- Our Activity is the reliable, zero-consent architecture.
- Waze may be rejected before launch, redirected, or run in a compatibility mode.
- Launching Waze is not the same as receiving a reusable Waze Surface.
- If it launches, protected/secure surfaces may be blank because the virtual display is not secure.
- A successful launch on one phone/Android build is not a cross-device guarantee.

The connected lab phone has Waze installed (`OBSERVED_IN_LAB`). On a public `OWN_CONTENT_ONLY` display, `isActivityStartAllowedOnDisplay()` returned `false` for `com.waze`; Waze was not launched and no navigation was started (`OBSERVED_IN_LAB`).

Sources:

- [DisplayManager virtual-display flags](https://developer.android.com/reference/android/hardware/display/DisplayManager)
- [ActivityOptions.setLaunchDisplayId](https://developer.android.com/reference/android/app/ActivityOptions)
- [AOSP activity launch policy](https://source.android.com/docs/core/display/multi_display/activity-launch)
- [AOSP multi-display overview](https://source.android.com/docs/core/display/multi_display)
- [Display.FLAG_SECURE](https://developer.android.com/reference/android/view/Display#FLAG_SECURE)

## Input and focus

`VERIFIED_FROM_OFFICIAL_DOC`: Android can route an Activity's normal input on a logical display. Shell/lab input can explicitly name a display ID. For product-side reverse control, API 30+ adds `GestureDescription.Builder.setDisplayId(displayId)`, allowing an enabled AccessibilityService to target a logical display rather than display 0.

Limits:

- the user must explicitly enable AccessibilityService;
- `dispatchGesture()` requires `canPerformGestures`;
- focus/IME behavior is OEM-dependent, and regular phones commonly have only one globally focused window;
- secure/system surfaces may reject interaction;
- the lab must first map GE13 touch coordinates to the VirtualDisplay viewport.

The lab APK includes a display-aware AccessibilityService but does not enable it or change secure settings. Dynamic gesture testing remains user-consent-gated.

## Lifecycle and lock state

`VERIFIED_FROM_OFFICIAL_DOC`: removing a virtual display removes/destroys its windows; the display is also released when the creating process terminates. Device implementations supporting secondary Activities must securely hide content on secure lock.

`OBSERVED_IN_LAB`:

- the private Presentation rendered to `c2.mtk.avc.encoder` at 1280x720 without MediaProjection;
- a five-second run produced 89 frames, 140,043 Annex-B bytes, and 30 bytes of CSD;
- a run spanning two seconds with display 0 asleep still produced 86 frames and 138,001 bytes, then the phone was restored to `Awake`;
- a later capture contained NAL types 7 (SPS), 8 (PPS), 5 (six IDRs), and 1 (106 non-IDR slices);
- shell input explicitly targeted at the logical display did not reach the Presentation's touch handler on this phone.

`UNKNOWN`: behavior under a secure lock for a long session, thermal behavior, OEM background limits over hours, and display-aware Accessibility injection after explicit user enablement.

## Decision

**Own custom UI: `YES` in this lab using Presentation/direct app-owned rendering; Activity placement itself is not portable.**

**Third-party UI such as Waze: `NO` on the tested stock Android 16 phone via this VirtualDisplay path, and not a product dependency.** Cross-package display security blocked launch before secure-content and lifecycle concerns. MediaProjection remains the general-purpose capture API and carries recurring consent.

**Overall Phase 0.5 third-party-app conclusion: D. NOT VIABLE WITH PUBLIC APIs on the tested stock phone.** App-owned content remains viable through `Presentation`/direct rendering, but that is not a mechanism for hosting Waze or obtaining its pixels.
