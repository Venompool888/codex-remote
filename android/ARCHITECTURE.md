# Android feature map

All app-owned UI uses Jetpack Compose. `ui/CodexApp.kt` mounts the feature surfaces;
`MainActivity` calls `setContent` and connects their callbacks to the existing runtime.
No production AndroidView, ComposeView wrapper, native layout, or native dialog is
needed for a feature. System permission prompts, photo/document pickers, share
sheets and notifications remain Android platform services.

## Where to change a feature

Paths below are relative to `app/src/main/java/app/codexremote/android/`.

| Feature | UI | State/actions |
| --- | --- | --- |
| Pairing, settings, connection selection/deletion | `ui/connections/` | `presentation/connections/` |
| Host/folder browsing and project creation | `ui/projects/` | `presentation/projects/` |
| Drawer, project/task search and paging | `ui/sidebar/` | `presentation/sidebar/` |
| Timeline, task header and follow latest | `ui/conversation/ConversationScreen.kt` | `presentation/conversation/` |
| Markdown, links, code/table copying | `ui/conversation/ComposeMarkdown.kt` | Parsed `MarkdownBlock`/`InlineMarkdownNode` |
| Message bubbles and sent attachments | `ui/conversation/MessageBubble.kt` | `TimelineItem` |
| Tool output, progress and file diffs | `ui/conversation/ToolActivityCard.kt`, `FileDiffDialog.kt` | Timeline and conversation controller |
| Input, models, effort, speed, permissions | `ui/composer/`, `compose/ComposerActionBar.kt` | `presentation/composer/` |
| Draft attachment cards | `compose/AttachmentCards.kt` | `AttachmentCardUiState`, composer controller |
| Plugins, skills and connected apps | `ui/catalog/` | `presentation/catalog/` |
| Approvals and interactive forms | `ui/interactions/` | `presentation/interactions/`, `InteractionFormModel` |
| Artifacts, download progress, image viewer | `ui/artifacts/` | `presentation/artifacts/` |
| Colors/theme, generic runtime prompts | `ui/theme/`, `ui/common/RuntimeDialog.kt` | Explicit dialog state/actions |

Start at the feature directory when adding UI. Controllers own observable state and
accept explicit callbacks; composables do not receive MainActivity or construct RPC
messages. Prefer a small feature API over a new global ViewModel or Activity-field
extension. UI controllers currently live for the Activity lifetime; saved drafts,
credentials and navigation remain owned by the existing stores/host state.

## Runtime boundary

MainActivity remains a runtime coordinator for lifecycle, intents, launchers,
per-server clients, workspace migration, task navigation, upload queues and RPC
routing. Completing the UI migration does not mean that this runtime has been
rewritten or that all orchestration has moved out of the Activity.

Keep `RemoteClient`, protocol projections, `DraftStore`, `RemoteProjectStore`,
`TokenStore`, attachment transfers and credential/security APIs stable for UI work.
`ArtifactDownloads` emits transient `ArtifactDownloadEvent` values. Its controller
renders progress/actions; verification, cancellation and scope checks remain in the
downloader. `RemoteImageRepository` owns loading/cache and captured source scope;
`CodexApp` supplies it through composition locals together with the image presenter.

Every asynchronous result must still belong to its captured server, credential,
task and request generation. A late catalog result, folder listing, upload, approval
acknowledgement or image load must never overwrite the newly selected task. Input
uses `TextFieldValue` so selection and IME composition survive recomposition.
Permissions/options come from host metadata, including disabled states.

## Validation

From repository root:

```sh
python3 scripts/check-compose-ui.py
python3 scripts/test_check_compose_ui.py
./android/gradlew -p android :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:lintRelease :app:assembleRelease
```

The Compose gate rejects native production UI and interop wrappers and runs in the
release check/CI path. Unit tests exercise protocol/domain behavior and controller
request ownership. Instrumentation fixtures render real Compose components and
exercise callbacks, accessible actions, narrow/RTL layouts, large fonts, dialogs,
image loading and copy fidelity. MainActivity integration fixtures cover original
request routing, deletion cleanup and callback expiry.

Run destructive device fixtures only through `scripts/emulator-check.py` on an
explicit disposable emulator. ClipboardInputDeviceTest and RemoteSkillSearchDeviceTest
need manual/live-host setup; PublicAuthenticationDeviceTest needs an explicit
endpoint. Do not count their no-op paths as integration validation. Physical updates
must follow root AGENTS.md and preserve the currently pinned package and device data.
