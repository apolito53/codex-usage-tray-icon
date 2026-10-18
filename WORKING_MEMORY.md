# Current handoff

Implemented prepaid credit display on Windows (context menu and left-click
balloon) and Ubuntu (menu and --check), separately from free reset credits.
The optional credits object comes from the selected Codex quota bucket.
Unavailable, zero, unlimited, and stale readings remain distinct.

Verification on 2026-10-17: Windows build, 20 parser/formatter cases, and
menu/balloon/offline/recovery checks pass. Ten focused Python tests pass on
Windows using a test-only fcntl import stub; no Linux locking behavior was
tested. Native Ubuntu/GTK is unavailable here. An attempted broader chart
suite fails on pre-existing POSIX strftime formats under Windows; chart code
was not changed. New credit menu tests use mocked widgets.

Live verification confirmed that the legacy and Codex buckets expose the
same credit fields. Display faithfully follows the CLI response; billing UI
reconciliation remains unverified. No credentials or full payloads were saved.
The feature and project documentation are ready for publication together;
existing planning links and planning-file content were preserved.

The updated Windows executable is installed and running through Explorer,
with startup registration preserved. Built and installed SHA-256 match
`8CD7FE5B357CE83FF6D5CD1600DE048A0A052D6E1061CB2A775619C4A41E4A1F`.
The launcher verified the new process is outside the terminal job. The first
copy attempt raced executable release; a bounded retry completed successfully.
Menu and balloon content were checked programmatically, not through a desktop
screenshot. The terminal-visible log is redirected/stale; it is not evidence
of the installed process's latest refresh.

Windows test command after build:
`powershell.exe -NoProfile -ExecutionPolicy Bypass -File tests/test_windows_credits.ps1`

Remaining platform verification is tracked in TODO.md.
