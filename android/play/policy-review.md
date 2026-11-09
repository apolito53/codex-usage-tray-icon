# Policy and declarations review worksheet

Prepared **8 November 2026** for **Allowance Meter 0.3.0**, version code **5**,
package **`com.apolito.allowancemeter`**. Recheck the final signed artifact and
current Console questions before submitting. Use this evidence worksheet to
prepare Console answers. Owner declarations, sign-off, and external approvals
remain unrecorded.

## Identity, distribution, and branding

| Item | Prepared position and evidence | Completion |
| --- | --- | --- |
| Product identity | Allowance Meter is an independent weekly allowance utility. Codex appears descriptively in the copy; no OpenAI logo, wordmark, or imitation logo is used. | Confirm final app label and store title agree. |
| Affiliation | Listing and privacy page state that the app is not affiliated with, endorsed by, or sponsored by OpenAI. | Confirm final screenshots and in-app copy retain this distinction. |
| Trademarks | The description attributes OpenAI, ChatGPT, and Codex to OpenAI. The icon is the project's existing numeric mark. | Owner reviews naming/rights; no permission claim is made. |
| Commercial model | Intended release is free, local, and noncommercial. No developer account relay, hosted Codex execution, ads, billing integration, or in-app purchase flow is present. | Owner confirms this remains true in Console and future releases. |
| Integration boundary | The app embeds pinned open-source Codex account libraries and uses its own Android HTTPS/storage boundary. This is distinct from a separately registered Sign in with ChatGPT integration. | Recheck service guidance when changing auth or monetization. |

[OpenAI's design guidelines](https://openai.com/brand/) prohibit misleading
endorsement and merging OpenAI marks into a product's own branding. The supplied
title and artwork use the app's own identity.

[Official OpenAI app-server documentation](https://learn.chatgpt.com/docs/app-server#auth-endpoints)
currently permits continued use of app-server authentication by local or
open-source apps, recommends migration to Sign in with ChatGPT, and excludes
commercial/hosted services. That guidance does not certify this Android port or
guarantee service availability. The device-code prerequisites are described in
[OpenAI's authentication documentation](https://learn.chatgpt.com/docs/auth#login-on-headless-devices).

## Data flow established in source

| Data | Where it goes and why | Storage and deletion | Source to recheck |
| --- | --- | --- | --- |
| Device authorization request/code and token exchange | Directly to fixed `auth.openai.com` HTTPS paths to authorize this phone. Password entry happens in the external OpenAI browser page. | Pending login is held in memory; successful account material becomes the encrypted session below. | [`MainActivity.kt`](../app/src/main/java/com/apolito/codexusage/preview/MainActivity.kt), [`AccountPlatform.kt`](../app/src/main/java/com/apolito/codexusage/preview/AccountPlatform.kt) |
| Access/refresh/ID tokens and account information contained in them | Directly to OpenAI as needed for authorization, renewal, allowance requests, and attempted revocation. Requests can include a ChatGPT account ID and regional/account headers. | Session record encrypted with Android Keystore AES-GCM in app-private no-backup storage. Native library uses an in-memory auth store. Sign out attempts record/key removal, reports storage failures, and after successful removal attempts remote revocation. | [`account.rs`](../native/src/account.rs), [`AccountPlatform.kt`](../app/src/main/java/com/apolito/codexusage/preview/AccountPlatform.kt) |
| Weekly allowance, reset/observation times, account-matching digest | OpenAI's allowance response is read on the phone and displayed in the app/notification. No developer upload exists. | App-private cache, replaced by later readings and cleared on sign out. Cache is not the encrypted session vault. Backup/transfer excluded. | [`MeterAccount.kt`](../app/src/main/java/com/apolito/codexusage/preview/MeterAccount.kt), [`account_backup_rules.xml`](../app/src/main/res/xml/account_backup_rules.xml) |
| Ordinary HTTPS connection metadata | OpenAI necessarily receives connection information such as IP address and request headers. No app location permission or location feature is present. | OpenAI's retention is controlled by OpenAI, not this repository. | [`AccountPlatform.kt`](../app/src/main/java/com/apolito/codexusage/preview/AccountPlatform.kt), [OpenAI privacy policy](https://openai.com/policies/privacy-policy/) |
| Local diagnostics | Fixed operation outcomes such as sign-in completed, allowance refreshed, or unavailable go to Android logd. No developer telemetry/crash collector is configured. | Android's rolling local log; the app does not automatically upload it. | [`MeterAccount.kt`](../app/src/main/java/com/apolito/codexusage/preview/MeterAccount.kt), [`MeterRefreshJob.kt`](../app/src/main/java/com/apolito/codexusage/preview/MeterRefreshJob.kt) |
| Demo readings | Synthetic values generated locally. The demo itself does not request an OpenAI account or make account requests. | Local example state only; no live reading is changed. | [`MainActivity.kt`](../app/src/main/java/com/apolito/codexusage/preview/MainActivity.kt) |
| Optional support issue | A user who opens a project issue chooses what to send to GitHub. The privacy page warns that issues are public and requests no credentials or account details. | GitHub hosts the issue under its own policies. | [Project issues](https://github.com/apolito53/codex-usage-tray-icon/issues), [privacy page source](../../docs/privacy/index.html) |

The request allowlist contains only device authorization, token renewal,
revocation, and the passive usage GET. No conversation, prompt, repository,
contact, media, advertising-ID, or payment endpoint is exposed by that boundary.
Inspect the packaged native dependency notices and final code for any new SDK or
transport before relying on these facts.

## Data safety: owner decisions still required

[Google's Data safety guidance](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en)
counts off-device transmissions to third parties as collection. Local-only
processing is outside that definition. Ephemeral processing and user-initiated
sharing have specific conditions; an HTTPS connection alone does not prove an
exemption. Do not turn “no developer server” into “no data collected.”

| Console topic | Evidence-based preparation | Owner review needed |
| --- | --- | --- |
| User IDs / other personal information | Tokens can contain account/user identifiers; the allowance request sends an account ID. Email or plan claims may be present inside the received ID token even though the screen does not show them. | Inspect the pinned token schema and choose all applicable data types. Do not infer absence of email from UI. |
| Collection | Live auth and allowance operations transmit account data directly to OpenAI. | Determine each field's category, collection status, and purposes from actual flow. App functionality/authentication is the observed use. |
| Sharing | OpenAI is an external account/service provider. The user explicitly chooses sign-in, after an in-app explanation. | Assess the user-initiated/disclosure exception against current rules. Do not mark OpenAI a processor acting under the developer's instructions without evidence. |
| Ephemeral processing | The developer cannot establish OpenAI server retention from local source. Local session retention is persistent until sign out. | Do not assert all transfers are ephemeral without a valid basis for the receiving service. |
| Required / optional | Example mode works without sign-in; real weekly readings require account authorization. | Apply Google's exact per-data-type optionality questions. A demo does not automatically make data optional for live functionality. |
| Encryption in transit | Fixed HTTPS URLs, Android trust/hostname verification, no cleartext, redirects refused. | Confirm the release artifact preserves the boundary; do not call ordinary HTTPS end-to-end encryption for exemption purposes. |
| Deletion | Sign out clears local session/cache and attempts OpenAI token revocation. Android Clear storage/uninstall removes app-local data. | Do not label this deletion of the user's OpenAI account or all data held by OpenAI. Complete the deletion questions accurately. |
| Ads/analytics/crash reporting | None configured or automatically uploaded in the reviewed app code. | Recheck the final dependency graph and Console settings; do not imply independent certification. |
| Diagnostics / device data | Local logd outcomes are not uploaded by the app. IP/headers go to OpenAI with live requests. | Review OpenAI's processing and the form's relevant categories; do not invent device-identifier or location behavior. |

## Account creation and deletion

The developer does not create an Allowance Meter account or operate an account
database. The existing ChatGPT account remains with OpenAI. However, Google
includes flows that direct users outside the app to create an app account in its
[account deletion requirements](https://support.google.com/googleplay/android-developer/answer/13327111?hl=en).
Review the actual external OpenAI page and current questions before selecting
“does not offer account creation.” A demo and a local Sign out button do not
settle that classification.

The privacy page describes local removal and links to OpenAI's privacy controls.
It is **not** presented as a developer-hosted request to delete an OpenAI account.
If the Console requires an account-deletion resource beyond those controls,
resolve that requirement before submission. Do not promise a maintainer can
delete an account or service data they cannot access.

## Other Console declarations

| Topic | Source-grounded preparation | Leave to owner / final check |
| --- | --- | --- |
| App access | Some functionality restricted. Built-in no-account demo covers the meter/notification; live authorization remains separate. | Follow [app-access.md](app-access.md); resolve live access if Google requires it. |
| Ads | No ad SDK or ad placement is present. | Confirm “contains ads” answer from final build and distribution plan. |
| Category | Tools is proposed; this is a developer utility displaying allowance, not a bank, payment tool, or financial advice app. | Select category and answer financial-features questions as actually presented. |
| Target audience / age | No demographic is collected and no child-directed features are present. | Owner chooses intended age groups and applicable Families declarations. Do not infer age eligibility from a content rating. |
| Content rating | No hosted/user-generated feed, chat composer, generated content, gambling, violent or sexual content appears in the utility. | Complete the rating questionnaire honestly; no rating is assigned here. |
| App permissions | Internet, network state, persisted-job boot permission, and optional notifications are requested in source. No foreground service, exact alarm, accessibility service, VPN, broad storage, SMS, contacts, camera, microphone, or location feature is declared. | Inspect the merged release manifest. `BIND_JOB_SERVICE` is the service's binding protection, not a user-granted data permission. |
| Background work | Network-constrained JobScheduler refresh requested about every 15 minutes; Android controls timing. Hide/dismiss does not auto-repost the notification. | Verify final build and avoid “real time” or “always visible” claims. |
| Developer identity/contact | Project issues are the technical/privacy inquiry route in the prepared public page. No support email is recorded here. | Owner must supply a support address privately before completing Console, then verify developer identity, organization/personal status, address/phone, trader status, and contact visibility. Do not copy private contact details into this public repository. |
| Availability / pricing / testing | Intended free local noncommercial release. | Owner chooses countries, device availability, testing track and required testing eligibility, pricing, and production rollout. No Console completion is claimed. |
| Licensing | Embedded native notices must match the shipped libraries. | Check release package, notices, dependency rights, export/encryption questions, and signing stewardship. No legal answer or rights attestation is prefilled. |

## Submission prerequisites to record

- [ ] Owner reviewed Data safety classifications and recipient processing.
- [ ] Owner reviewed account-creation/deletion scope and resolved any required path.
- [ ] App access tested on the release build; any required dedicated reviewer
  access supplied privately in Console.
- [ ] Privacy URL returns the actual mobile-readable policy publicly over HTTPS;
  in-app link and Console URL match it.
- [ ] Store images and real screenshots match the uploaded app; example labels
  remain visible.
- [ ] Final permissions, signing/package/version, native compatibility and
  matching notices checked in the release handoff.
- [ ] Owner completed identity, support contact, audience/rating, availability,
  testing, and legal declarations.

Review owner: **Not recorded**

Review date / release artifact SHA-256: **Not recorded**

Submission / approval status: **Not submitted by this worksheet**
