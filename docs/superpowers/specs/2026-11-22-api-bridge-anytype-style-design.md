# API bridge screens in Anytype's style

Status: approved by the user, 2026-11-22 (mock-ups reviewed; "go ahead and implement").

## Why

The consent screen and the Connected apps screen used plain Material3 with the system's dynamic
colors, so they looked like system screens instead of Anytype. The 2026-11-21 design asked for
Anytype's theme; the plan fell back to Material3 without weighing `core-ui`.

## Decisions (the user's)

- The consent screen is an Anytype bottom sheet, not a system-style or centred dialog.
- Access (Read and change / Read only) and Spaces (All spaces, then the space list) are visibly
  separate groups, so the two choices do not read as one exclusive list.
- Use the same UI primitives as the rest of the app. That rules out a look-alike sheet drawn in a
  Compose Dialog: the consent screen is a real Material3 `ModalBottomSheet`, configured the way the
  app's other sheets are. Tests are rewritten to follow the production code where needed.

## Approaches

1. **Depend on `:core-ui` and use the app's own primitives (chosen).** Costs a heavier module
   graph for `:api-bridge`; the APK is unchanged, since `:app` already holds every module `core-ui`
   needs. `api-bridge-client`, the library other apps use, is not affected.
2. Copy Anytype's tokens into `:api-bridge`. No new dependency, but the copy drifts and the
   components are look-alikes. Rejected.

## Design

- **Consent sheet.** `ModalBottomSheet` as in the app's other sheets: `skipPartiallyExpanded`,
  `sheetMaxWidth = contentWidthDp()`, 16 dp top corners, `background_secondary`,
  `modal_screen_outside_background` scrim, `DragHandle()`. Swipe, Back and a tap on the scrim mean
  Don't allow. Content: the app icon (56 dp), `AlertTitle`, `AlertDescription`, package and
  certificate (`Caption1Regular`, secondary; the account name on its own caption line above,
  only when it is not blank), then `Section("Access")` with two 52 dp `BodyRegular`
  rows (a tick on the selected one) and `Section("Spaces")` with the All spaces switch
  (`color_accent_80` track) and a checkbox row per space when it is off, rows separated by
  `Divider()`; then `ButtonPrimaryLoading` Allow (loading while the approval saves) over
  `ButtonSecondary` Don't allow, and a "Manage connected apps" link.
- **Protections on the sheet's window.** The sheet is its own window (`ModalBottomSheetWindow`):
  `ModalBottomSheetProperties(securePolicy = SecureOn)` keeps it out of screenshots, and the
  obscured-touch filter and accessibility data sensitivity go on the root view of the sheet's
  window (views inherit the sensitivity; the tests check the compose view under it). The
  activity window under it stays `FLAG_SECURE` with overlays hidden. Allow keeps its
  `pointerInteropFilter` and the arming delay.
- **Connected apps.** The space settings pattern: a `Scaffold` with the 48 dp top bar
  (`ic_default_top_back`) and a centred `Title1`, `Section("Apps with access")`, one bordered card
  per app (0.5 dp `shape_primary`, 16 dp radius) with the icon, name (`PreviewTitle1Regular`),
  access line and certificate, and Revoke as `ButtonWarningLoading` (loading while it runs). Empty and error states use
  `EmptyState`. The revoke-retrying notice sits above the list so it survives an emptied list;
  it describes the latest revoke that finished, so a later complete revoke clears it.
- **Theme.** Colors come from `core-ui` resources. Both screens are `AppCompatActivity`, so
  Anytype's in-app Light/Dark setting (`AppCompatDelegate.setDefaultNightMode`) reaches them:
  Connected apps uses `AppTheme`, the consent screen `ApiBridgeConsentTheme` (`AppTheme` on a
  translucent, undimmed window with no title, like the `Theme.Translucent.NoTitleBar` it
  replaces), so the requesting app stays visible under the sheet's scrim.

## Follow-up decisions (2026-11-23)

- **Strings** live in `:localization` (`api_bridge_*`); `ConsentState` holds no text, only what
  the screen chooses strings from (`reach`, `limit`).
- **App labels** are the other app's text: one helper (`InstalledApps.kt`) reads them through
  `TextUtils.makeSafeForPresentation` (first line, trimmed, bounded length) for both screens.
  As for the system's own labels, the label is read as HTML: a raw newline is whitespace, `<br>`
  ends it.
- **Consent state.** The content is null until it has loaded; the answer being saved is separate.
  The choices are saved across a recreation whenever the content has loaded, also while Allow
  saves.
- **Revokes outlive the screen.** Connected apps runs its revoke (the controller call and the
  no-controller fallback) in `withContext(NonCancellable)`, so Back or a rotation right after
  the tap no longer skips it.
- **Tests** find the sheet window through Robolectric's `ShadowDialog` (Material3 1.3 shows the
  sheet in a dialog), so the activity has no test-only hook for it.
