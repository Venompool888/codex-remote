# Remote skill discovery and search correction

2026-09-10: searching `xr` could show `data-analytics:index` while the expected
`xray-reverse` skill was absent. Two independent causes were verified.

## Search field boundary

The Android filter concatenated name and description without a separator.
`data-analytics:index` + `Route ...` contains `xR`, creating an accidental
case-insensitive `xr` match. `matchesCapabilityQuery` keeps field separation,
normalizes hyphens/underscores and case, and requires each whitespace-delimited
query term. Both `xray reverse` and `xray-reverse` match the same skill.

## Service account catalog

The requested skill existed only in the administrator's skill directories.
The public Remote Host runs under its own service user with ProtectHome enabled;
its App Server cannot discover the administrator's private home. This is an
account boundary, not evidence that the App Server returned the wrong catalog.

Provisioned only the requested skill and its bundled templates (16 files) into
that service user's `$HOME/.codex/skills/xray-reverse`. Source/destination hashes
match; templates were checked for placeholder credential fields and symlinks.
No administrator config, credentials, unrestricted home access or root execution
was granted. No server restart or server protocol change was required. Refresh
uses the existing App Server forceReload path. This is a selected installation,
not automatic synchronization of every administrator skill. Future skill updates
must also be provisioned to the account actually running the Remote Host.

## Evidence and limits

- 172 JVM tests pass, including the cross-field false match, spaced/hyphenated
  names, case, multiple query terms, empty query and non-Latin descriptions.
- Debug and instrumentation APKs built; updated Debug installed on emulator-5554.
- `RemoteSkillSearchDeviceTest` refreshes the real paired host's catalog, checks
  `xray-reverse` is enabled, searches `xr` and `xray reverse`, and asserts the
  unrelated `data-analytics:index` row is absent. Passed against the actual
  public connection: `/tmp/codex-remote-skill-search`.
- Screenshot outside Git: `/tmp/codex-remote-xray-search.png`.
- The first test fixture incorrectly searched for a Compose button as a native
  child View. Corrected to traverse its accessibility action parent; the final
  passing test supersedes that fixture crash.

This live-host test is deliberately excluded from the default unpaired suite.
It reads the catalog and edits only the capability search query; it does not
select a capability, send a message, or execute Xray commands. Availability and
search are verified, not real network reconfiguration or skill execution.
