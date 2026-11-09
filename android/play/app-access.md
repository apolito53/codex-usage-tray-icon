# App access instructions

Prepared for Allowance Meter 0.3.0 (`com.apolito.allowancemeter`). Confirm these
labels on the exact uploaded build. This document is a review-access resource;
it is not a claim that Google has accepted demo access for the live account flow.

## Console selection

Select the option indicating that **some functionality is restricted**. Live
account readings require OpenAI sign-in and account eligibility. Do not select
the option claiming that every feature is available without restrictions.

Instruction name: **Demo meter and notification, no account required**

Username/password: **Not required for the built-in demo.** Do not invent a
password or put a personal account, session token, or one-time sign-in code into
the Console.

## Instructions to paste

1. Open Allowance Meter on a fresh installation. Beneath Sign in with ChatGPT,
   tap Try demo. No account, subscription, access code, or network connection is
   needed for the demo.
2. The app displays Demo · example data. The percentage and timestamps are
   synthetic examples, not an account reading. Tap Refresh to switch the local
   example between 72% and 64%; this does not contact OpenAI or change an account.
3. Tap Show meter. On Android 13 or later, allow notification permission if
   requested. The silent notification shows an example weekly percentage in the
   status bar. Expand the notification drawer to inspect its example reading,
   update time, reset time, and Hide meter action. The notification is labeled as
   a demo/example.
4. Tap the notification to return to the app. Test Hide, then Show meter again.
   If a silent icon is hidden by the device, Notification settings opens the
   relevant Android settings.
5. Tap Exit demo to leave the example view. Demo mode does not connect, sign out,
   modify, or delete a real ChatGPT account.

## Coverage and live account access

The demo exposes the core percentage display, local refresh example, theme,
notification permission, status-bar icon, expanded notification, hide/show,
notification settings, and exit behavior. It does **not** exercise OpenAI's live
device authorization, token renewal/revocation, account eligibility, real
allowance responses, or network failures. It is not a demo account at OpenAI.

The live flow starts with Sign in with ChatGPT, opens OpenAI's external HTTPS
device sign-in page, and uses the code displayed by the app. The account needs
Codex access and device-code login enabled; managed workspaces can restrict it.
OpenAI controls the external sign-in page and account availability.

Before submission, the owner must decide whether these demo resources provide
the review access requested by the Console. Google asks for access to all
functionality and, where credentials are used, durable and reusable access.
See [Google's sign-in review requirements](https://support.google.com/googleplay/android-developer/answer/15748846?hl=en).
If Google requires a live account, arrange a dedicated, eligible reviewer account
and an approved, maintainable access method in the Console's private access
fields. Do not provide a personal account, copy an auth cache, publish tokens, or
ask a reviewer to use a personal ChatGPT account. Do not promise a bypass for
OpenAI's one-time code or account security requirements.

Owner completion record: **Not completed.** No reviewer credentials are included
in this repository.
