# critbox-godot-solana-mobile: plan

A Godot 4.7 Android plugin that lets a game connect to any Solana wallet on
the phone through **Mobile Wallet Adapter (MWA)**: Phantom, Solflare,
Backpack, or the Seeker's own Seed Vault wallet. It ships with a GDScript
client and a mock wallet, so the same game code runs in the editor and in
headless tests.

Built for the CLOCK IN Solana Mobile hackathon. The first consumer is
Critbox's *Yeet Fleet* (branch `solana-integrated`).

## Why another plugin

The godot-solana-sdk ships a `WalletAdapterAndroid` AAR. It works, but it
**polls** a status getter every frame, pulls in Jetpack Compose to host one
activity, and targets Godot 4.3. This plugin:

- is **signal-driven**: one request id in, one `request_succeeded` /
  `request_failed` signal out. No polling.
- has **no Compose**: a plain transparent `ComponentActivity` hosts the MWA
  session (the "trampoline").
- targets **Godot 4.7** and pins library versions to the 4.7 Android build
  template (Kotlin 2.1.21, AGP 8.6.1, compileSdk 36).
- builds **SOL and SPL token transfers** natively (token accounts, memo), so a
  GDScript game can pay with a token like SKR without a GDExtension.
- reports **device facts** a Seeker game needs: Solana Mobile device, Seeker
  model, Seed Vault present.

## Pattern (GodotPrompter `mobile-development`)

**Picked:** an Android **v2 plugin**: a Kotlin AAR registered through
`org.godotengine.plugin.v2.*` manifest metadata, plus an `EditorExportPlugin`
that adds the AAR and its Maven dependencies.

**Rejected:**
- `JavaClassWrapper` / `create_proxy` (4.7) straight to MWA. MWA needs an
  activity that registers an `ActivityResultLauncher` before it starts, plus
  Kotlin suspend functions. GDScript can drive neither.
- A GDExtension Android plugin (C++). It adds JNI work for no gain, since MWA
  is Kotlin.
- Reusing the SDK's AAR, because of the polling and Compose noted above.

## Pinned versions

| Thing | Version | Why |
|---|---|---|
| Godot | `org.godotengine:godot:4.7.0.stable` (compileOnly) | Matches the template's bundled `godot-lib`; it is the runtime |
| MWA | `com.solanamobile:mobile-wallet-adapter-clientlib-ktx:2.0.8` | Built with Kotlin 2.1.21, core-ktx 1.16 and activity 1.10, the same versions as the Godot 4.7 template. 2.1+ / 2.2 need Kotlin 2.2–2.4 and break the template build |
| web3-solana | 0.3.0-beta4 (transitive from MWA 2.0.8) | `Message.Builder`, `ProgramDerivedAddress`, `MemoProgram` |
| Coroutines | `kotlinx-coroutines-android:1.10.2` | `Dispatchers.Main` for the trampoline |
| AGP / Gradle / JDK | 8.6.1 / 8.11.1 / 17 | Same as the template |

## Architecture

```
GDScript game
  └─ SolanaMobileClient (addons/solana_mobile/solana_mobile_client.gd, RefCounted)
       ├─ native: Engine singleton "SolanaMobile"  ──JNI──▶  SolanaMobilePlugin.kt
       │                                                      │ request(op, json) → id
       │                                                      ▼
       │                                         MwaBridge (one request in flight)
       │                                                      │ startActivity
       │                                                      ▼
       │                              MwaBridgeActivity (transparent ComponentActivity)
       │                                ActivityResultSender(this) in onCreate
       │                                MobileWalletAdapter.transact { … }  ⇄ wallet app
       │                                finish() → MwaBridge.complete(id, outcome)
       │                                                      │
       │   request_succeeded(id, json) / request_failed(id, code, msg)  ◀── emitSignal
       └─ mock: MockWallet (desktop / headless / CI) with the same results and errors
```

### Native surface (singleton `SolanaMobile`)

| Method | Returns | Notes |
|---|---|---|
| `configure(json)` | `String` (`""` ok, else error) | `identity_name`, `identity_uri`, `icon_uri` (relative), `cluster` (`mainnet-beta`/`devnet`/`testnet`) |
| `request(op, json)` | `int` id (`-1` = busy) | ops: `authorize` (optional `sign_in` {domain, statement}), `deauthorize`, `sign_messages`, `sign_transactions`, `sign_and_send`, `capabilities`. `auth_token` in json reuses a session |
| `cancel()` | — | Fails the request in flight with `cancelled` |
| `is_wallet_installed()` | `bool` | Resolves the `solana-wallet:` intent (needs `<queries>`, shipped in the AAR manifest) |
| `device_info()` | `String` JSON | `manufacturer`, `model`, `solana_mobile`, `seeker`, `seed_vault` |
| `request("transfer", {transfer, rpc_url})` | id | Builds, signs and sends in one MWA session. A blockhash lives 150 blocks (measured ~36 s on devnet at ~240 ms/slot), and a slow chooser + wallet start-up used it up (`BlockhashNotFound`), so it is refreshed **inside** the session. Android 16 blocks a background app's network (`blocked=APP_BACKGROUND`) while the wallet is in front, so GDScript also prefetches one (`blockhash`, `slot`) as the fallback. Passes `minContextSlot` + `confirmed` to the wallet. RPC failure → `rpc_error` |
| `build_transfer(json)` | `String` JSON | `{tx}` base64 unsigned legacy tx, or `{error}`. SOL, or SPL with `mint`, `decimals`, `token_program`, `create_ata`, `memo`, `blockhash` |

Bytes always cross as **base64 inside JSON**, which keeps the JNI surface to
plain strings and ints. Signals:
`request_succeeded(id: int, json: String)` and
`request_failed(id: int, code: String, message: String)`.

Every successful result carries `auth_token`, `public_key` (base58),
`account_label` and `wallet_uri_base`, so the client always holds the latest
session. Error codes: `busy`, `cancelled`, `no_wallet`, `declined`
(MWA -3), `unauthorized` (-1), `not_submitted` (-4), `cluster_not_supported` (-7),
`too_many_payloads` (-6), `invalid_payloads` (-2), `timeout`, `error`.

### GDScript client (`SolanaMobileClient`)

Every call is `await`-able and returns a `Dictionary` with `ok: bool`, plus a
`code` and `message` when it fails:

```gdscript
var w := SolanaMobileClient.new("Yeet Fleet", "https://critbox.games", "icon.png", "devnet")
var r := await w.connect_wallet()            # or await w.sign_in("critbox.games", "Pilot login")
if r.ok: print(w.public_key)
var tx := w.build_transfer({"to": dest, "mint": SKR, "decimals": 6, "amount": "5000000",
		"memo": "order:abc", "blockhash": await w.latest_blockhash()})
var sent := await w.sign_and_send([tx])      # sent.signatures[0] (base58)
```

On desktop or headless it uses `MockWallet`. Its behaviour can be scripted
(`next_error`, `delay_sec`), so a game's tests cover the cancel, decline and
no-wallet paths without a phone. A small RPC helper (`latest_blockhash`,
`balance`, `token_balance`) uses `HTTPRequest` against the configured RPC URL.

## Repo layout

```
plugin/      Kotlin Android library → AARs, copied into demo/addons/solana_mobile/bin/
demo/        Godot 4.7 project: the addon + a button-per-call demo screen + headless check
docs/        this plan, decision log
.github/     CI: builds the AARs, runs the JVM unit tests and the headless check
```

## Milestones

| # | Done when | Verified by |
|---|---|---|
| P0 | `./gradlew build` makes both AARs. The demo APK installs. Connect opens Phantom, the public key comes back, and dismissing the wallet comes back as `cancelled` within a second | JVM tests; `adb install` on the phone **and** on the API 36 emulator |
| P1 | Sign-In-With-Solana, sign message, reconnect with a cached token, deauthorize, capabilities, `device_info`, `is_wallet_installed` | Demo buttons on the phone; logcat |
| P2 | `build_transfer` + `sign_and_send` move devnet SOL and a devnet SPL token (with a memo) | Explorer link for each signature; JVM tests check the instruction bytes against `@solana/web3.js` vectors |
| P3 | Public release: README with a quick start, MIT license, CI green, a release zip of `demo/addons/solana_mobile`, an Asset Library-ready layout | GitHub Actions run; fresh-clone build |

## Risks

- **The wallet returns to a fresh activity.** If Android kills the
  trampoline, the request ends as `cancelled`; it never hangs. The activity
  sets `configChanges` so rotation doesn't recreate it.
- **Godot's JNI type mapping.** Avoided by sending only String and int.
- **Old Phantom builds lack Sign-In-With-Solana.** `authorize` without
  `sign_in` is the fallback, and the client reports
  `capabilities.supported_optional_features`.
