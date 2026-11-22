# API bridge screens in Anytype's style

Status: approved by the user, 2026-11-22 (mock-ups reviewed; "go ahead and implement").

## Why

The consent screen and the Connected apps screen used plain Material3 with the system's dynamic
colors, so they looked like system screens instead of Anytype. The 2026-11-21 design asked for
Anytype's theme; the plan fell back to Material3 without weighing `core-ui`.

## Decisions (the user's)

- The consent screen is an Anytype bottom sheet, not a system-style or centred dialog.
- Access (Read and change / Read only) and Spaces (All spaces, then the space list) are two
  separate, labelled groups, so the two choices do not read as one exclusive list.

## Approaches

1. **Depend on `:core-ui` and use its real components (chosen).** `ButtonPrimary`,
   `ButtonSecondary`, `ButtonWarning`, Anytype's text styles and color resources, `contentWidthDp()`.
   Costs a heavier module graph for `:api-bridge`; the APK is unchanged, since `:app` already holds
   every module `core-ui` needs. `api-bridge-client`, the library other apps use, is not affected.
2. Copy Anytype's tokens into `:api-bridge`. No new dependency, but the copy drifts and the
   buttons are look-alikes. Rejected.

## Design

- **Consent sheet.** The same Compose `Dialog` window as before, so every protection stays as it
  is: `FLAG_SECURE` and `SecureFlagPolicy.SecureOn`, hidden overlays, the obscured-touch filter on
  the dialog layout and decor view, the `pointerInteropFilter` on Allow, and the arming delay. The
  dialog fills the window (`usePlatformDefaultWidth = false`) and its content sits at the bottom:
  a `background_secondary` sheet with 16 dp top corners, capped at `contentWidthDp()`, scrolling
  when the window is short, clear of the navigation bar. Tapping outside still does nothing; Back
  still means Don't allow. No drag handle: the sheet does not drag.
- **Content**, top to bottom: the app icon (56 dp), the title (`HeadlineHeading`, centred), the
  summary (`UxSmallTextRegular`, centred), package and certificate (`Caption1Regular`, secondary),
  an error line if any, "Access" label and group (two rows, a tick on the selected one), "Spaces"
  label and group (the All spaces switch, then a checkbox row per space when it is off), the
  narrowed-access note, Allow (`ButtonPrimary`, Large) above Don't allow (`ButtonSecondary`, Large),
  and "Manage connected apps" as a text link.
- **Connected apps.** Anytype colors and text styles: a centred `Title1` header, a secondary label,
  rows with the icon, name (`PreviewTitle1Medium`), access line and certificate (`Caption1Regular`,
  secondary), and Revoke as `ButtonWarning` (Small). Empty, loading and error states keep their text.
- **Theme.** Colors come from `core-ui` resources, so light and dark follow Anytype's own resources
  instead of the wallpaper.
- Semantics stay the same for tests and accessibility: radio rows, a switch, checkbox rows, the same
  texts, `ICON_TAG` and `revokeTag`.

## Left to the builder

Exact spacing, the space rows without space icons (the consent state only has names), and the
switch colors (`color_accent_80` track, as in `ShareSpaceScreen`).
