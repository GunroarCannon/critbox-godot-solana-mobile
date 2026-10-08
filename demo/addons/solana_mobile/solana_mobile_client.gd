class_name SolanaMobileClient
extends RefCounted
## Mobile Wallet Adapter for GDScript. Every wallet call is awaitable and
## returns a Dictionary: [code]{ok = true, …result}[/code] or
## [code]{ok = false, code, message}[/code].
##
## On Android with the plugin exported it talks to the "SolanaMobile" singleton;
## everywhere else (editor, desktop, headless) it uses [SolanaMobileMock], so the
## same game code and tests run without a phone.
## [codeblock]
## var wallet := SolanaMobileClient.new("My Game", "https://mygame.example", "icon.png", "devnet")
## var r := await wallet.connect_wallet()
## if r.ok:
##     print(wallet.public_key)
## [/codeblock]
## Failure codes: busy, cancelled, no_wallet, declined, unauthorized, not_submitted, timeout,
## connection_failed, cluster_not_supported, too_many_payloads, invalid_payloads,
## invalid_request, rpc_error, error. [method confirm] adds tx_failed and not_confirmed.

## Emitted whenever the connected account changes ("" after a disconnect).
signal account_changed(public_key: String)

const SINGLETON := "SolanaMobile"
const RPC_URLS := {
	"mainnet-beta": "https://api.mainnet-beta.solana.com",
	"devnet": "https://api.devnet.solana.com",
	"testnet": "https://api.testnet.solana.com",
}
const TOKEN_PROGRAM := "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
const TOKEN_2022_PROGRAM := "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"

## The wallet session. Save these to skip the approval prompt next launch.
var auth_token := ""
var public_key := ""
var account_label := ""
## "mainnet-beta", "devnet" or "testnet".
var cluster := "devnet"
## JSON-RPC endpoint for the helpers below. Defaults to the public one for
## [member cluster]; point it at your own (Helius etc.) for anything real.
var rpc_url := ""
## The pretend wallet when not on Android (null on a device).
var mock: SolanaMobileMock

var _backend: Object
var _last_id := -1
var _last_result := {}

signal _finished(id: int)


func _init(identity_name := "Godot dApp", identity_uri := "https://godotengine.org",
		icon_uri := "favicon.ico", cluster_name := "devnet", force_mock := false) -> void:
	cluster = cluster_name
	rpc_url = RPC_URLS.get(cluster, RPC_URLS["devnet"])
	if not force_mock and Engine.has_singleton(SINGLETON):
		_backend = Engine.get_singleton(SINGLETON)
	else:
		mock = SolanaMobileMock.new()
		_backend = mock
	_backend.connect("request_succeeded", _on_succeeded)
	_backend.connect("request_failed", _on_failed)
	var err: String = _backend.configure(JSON.stringify({
		"identity_name": identity_name, "identity_uri": identity_uri,
		"icon_uri": icon_uri, "cluster": cluster,
	}))
	if err != "":
		push_warning("SolanaMobile: configure failed: " + err)


## True when talking to a real wallet through the Android plugin.
func is_native() -> bool:
	return mock == null


func is_connected_wallet() -> bool:
	return public_key != ""


## True if a wallet app that speaks MWA is installed.
func is_wallet_installed() -> bool:
	return _backend.is_wallet_installed()


## [code]{manufacturer, model, brand, sdk, solana_mobile, seeker, seed_vault}[/code].
func device_info() -> Dictionary:
	var parsed: Variant = JSON.parse_string(_backend.device_info())
	return parsed if parsed is Dictionary else {}


func is_busy() -> bool:
	return _backend.busy_id() != -1


## Ends the wallet request in flight as "cancelled".
func cancel() -> void:
	_backend.cancel()


# --- Wallet ---

## Connects (or silently re-connects with a saved [member auth_token]).
func connect_wallet() -> Dictionary:
	return await _request("authorize", {})


## Connect + Sign-In-With-Solana in one prompt. The result's [code]sign_in[/code]
## holds [code]signed_message[/code] and [code]signature[/code] (base64) for a
## server to verify.
func sign_in(domain := "", statement := "") -> Dictionary:
	return await _request("authorize", {"sign_in": {"domain": domain, "statement": statement}})


## Revokes the session in the wallet, and forgets it here either way.
func disconnect_wallet() -> Dictionary:
	var r := await _request("deauthorize", {})
	forget()
	return r


## Forgets the session locally without asking the wallet.
func forget() -> void:
	auth_token = ""
	account_label = ""
	if public_key != "":
		public_key = ""
		account_changed.emit("")


func capabilities() -> Dictionary:
	return await _request("capabilities", {})


## Signs messages (text Strings or PackedByteArrays). Result: [code]signatures[/code], base64.
func sign_messages(messages: Array) -> Dictionary:
	return await _request("sign_messages", {"messages": _to_b64_list(messages, false)})


## Signs transactions (base64 Strings or PackedByteArrays) without sending them.
func sign_transactions(transactions: Array) -> Dictionary:
	return await _request("sign_transactions", {"transactions": _to_b64_list(transactions, true)})


## The wallet signs and submits. Result: [code]signatures[/code], base58.
func sign_and_send(transactions: Array) -> Dictionary:
	return await _request("sign_and_send", {"transactions": _to_b64_list(transactions, true)})


# --- Transactions ---

## Builds an unsigned transfer. [param params]: [code]to[/code], [code]amount[/code]
## (base units: lamports, or token units; int or String), [code]blockhash[/code],
## and for a token [code]mint[/code], [code]decimals[/code], optional
## [code]token_program[/code], [code]create_ata[/code] (default true), [code]memo[/code].
## [code]payer[/code] defaults to the connected account. Returns {ok, tx}.
func build_transfer(params: Dictionary) -> Dictionary:
	var p := params.duplicate()
	if not p.has("payer"):
		p["payer"] = public_key
	p["amount"] = str(p.get("amount", 0))
	var parsed: Variant = JSON.parse_string(_backend.build_transfer(JSON.stringify(p)))
	if not (parsed is Dictionary) or parsed.has("error"):
		return _fail("invalid_request", parsed.get("error", "bad transfer") if parsed is Dictionary else "bad transfer")
	return {"ok": true, "tx": parsed["tx"]}


## Build, sign and send in one wallet session: pay [param amount] (base units)
## of [param mint] (empty = SOL) to [param to]. A blockhash only lives ~36 s on
## devnet, so the native side refreshes it once the wallet has opened; where
## Android cuts the app's network behind the wallet (Android 16) it uses the one
## fetched here, just before the wallet opens.
func transfer(to: String, amount: Variant, mint := "", decimals := 0, memo := "",
		token_program := TOKEN_PROGRAM) -> Dictionary:
	if public_key == "":
		var c := await connect_wallet()
		if not c.ok:
			return c
	var spec := {
		"payer": public_key, "to": to, "amount": str(amount), "mint": mint,
		"decimals": decimals, "memo": memo, "token_program": token_program,
	}
	# Check the fields here; the native side would only find out mid-session.
	var check := build_transfer(spec.merged({"blockhash": "11111111111111111111111111111111"}))
	if not check.ok:
		return check
	var latest: Variant = await rpc("getLatestBlockhash", [{"commitment": "confirmed"}])
	if latest is Dictionary:
		spec["blockhash"] = latest["value"]["blockhash"]
		spec["slot"] = int(latest["context"]["slot"])
	return await _request("transfer", {"transfer": spec, "rpc_url": rpc_url})


## Waits until [param signature] (base58) lands on chain at [code]confirmed[/code]
## or better. A wallet's "ok" from [method sign_and_send] or [method transfer] only
## means it was submitted: a payment can still fail or never land (no SOL for the
## fee, a dropped broadcast), so confirm before granting anything for it.
## Returns [code]{ok, slot, status}[/code], or fails with [code]tx_failed[/code]
## (landed but errored; [code]err[/code] holds why) or [code]not_confirmed[/code]
## (not seen within [param timeout_sec]; it may still land, so ask again later
## rather than treating it as failed).
func confirm(signature: String, timeout_sec := 45.0) -> Dictionary:
	if mock != null:
		return mock.confirm(signature)
	var tree := Engine.get_main_loop() as SceneTree
	var deadline := Time.get_ticks_msec() + int(timeout_sec * 1000.0)
	while true:
		var r: Variant = await rpc("getSignatureStatuses", [[signature], {"searchTransactionHistory": true}])
		var status: Variant = r["value"][0] if r is Dictionary and r["value"] is Array and r["value"].size() > 0 else null
		if status is Dictionary:
			if status.get("err") != null:
				var failed := _fail("tx_failed", "Transaction failed on chain: " + JSON.stringify(status["err"]))
				failed["err"] = status["err"]
				return failed
			if status.get("confirmationStatus") in ["confirmed", "finalized"]:
				return {"ok": true, "slot": int(status.get("slot", 0)), "status": status["confirmationStatus"]}
		if Time.get_ticks_msec() >= deadline or tree == null:
			return _fail("not_confirmed", "Not confirmed after %d s" % int(timeout_sec))
		await tree.create_timer(2.0, true, false, true).timeout
	return {}


## The associated token account of [param owner] for [param mint].
func token_account(owner: String, mint: String, token_program := TOKEN_PROGRAM) -> String:
	return _backend.token_account(owner, mint, token_program)


# --- RPC helpers ---

## One JSON-RPC call to [member rpc_url]. Returns the "result", or null.
func rpc(method: String, params: Array = []) -> Variant:
	var tree := Engine.get_main_loop() as SceneTree
	if tree == null:
		return null
	var http := HTTPRequest.new()
	http.timeout = 15.0
	tree.root.add_child.call_deferred(http)
	await tree.process_frame
	var body := JSON.stringify({"jsonrpc": "2.0", "id": 1, "method": method, "params": params})
	if http.request(rpc_url, ["Content-Type: application/json"], HTTPClient.METHOD_POST, body) != OK:
		push_warning("SolanaMobile rpc %s could not start (%s)" % [method, rpc_url])
		http.queue_free()
		return null
	var res: Array = await http.request_completed
	http.queue_free()
	if res[0] != HTTPRequest.RESULT_SUCCESS or res[1] != 200:
		push_warning("SolanaMobile rpc %s failed: result %d, HTTP %d (%s)" % [method, res[0], res[1], rpc_url])
		return null
	var parsed: Variant = JSON.parse_string((res[3] as PackedByteArray).get_string_from_utf8())
	if not (parsed is Dictionary) or parsed.has("error"):
		push_warning("SolanaMobile rpc %s error: %s" % [method, parsed.get("error") if parsed is Dictionary else "bad JSON"])
		return null
	return parsed.get("result")


func latest_blockhash() -> String:
	var r: Variant = await rpc("getLatestBlockhash", [{"commitment": "confirmed"}])
	return r["value"]["blockhash"] if r is Dictionary else ""


## Lamports held by [param owner] (default: the connected account), or -1.
func balance(owner := "") -> int:
	var r: Variant = await rpc("getBalance", [owner if owner != "" else public_key, {"commitment": "confirmed"}])
	return int(r["value"]) if r is Dictionary else -1


## [code]{amount: String (base units), ui: float, decimals: int}[/code] of [param mint]
## held by [param owner], summed over its token accounts; empty on RPC failure.
func token_balance(mint: String, owner := "") -> Dictionary:
	var r: Variant = await rpc("getTokenAccountsByOwner", [owner if owner != "" else public_key,
			{"mint": mint}, {"encoding": "jsonParsed", "commitment": "confirmed"}])
	if not (r is Dictionary):
		return {}
	var total := 0
	var decimals := 0
	for acc in r["value"]:
		var amt: Dictionary = acc["account"]["data"]["parsed"]["info"]["tokenAmount"]
		total += int(amt["amount"])
		decimals = int(amt["decimals"])
	return {"amount": str(total), "ui": total / pow(10.0, decimals), "decimals": decimals}


# --- Plumbing ---

func _request(op: String, params: Dictionary) -> Dictionary:
	if auth_token != "" and not params.has("auth_token"):
		params["auth_token"] = auth_token
	var id: int = _backend.request(op, JSON.stringify(params))
	if id == -1:
		return _fail("busy", "Another wallet request is still open")
	while true:
		var done: int = await _finished
		if done == id:
			return _last_result
	return {}


func _on_succeeded(id: int, json: String) -> void:
	var parsed: Variant = JSON.parse_string(json)
	var result: Dictionary = parsed if parsed is Dictionary else {}
	if result.get("auth_token", "") != "":
		auth_token = result["auth_token"]
		account_label = result.get("account_label", "")
		var key: String = result.get("public_key", "")
		if key != "" and key != public_key:
			public_key = key
			account_changed.emit(key)
	result["ok"] = true
	_finish(id, result)


func _on_failed(id: int, code: String, message: String) -> void:
	# A wallet that no longer honours the saved session: drop it so the next
	# connect asks afresh instead of failing forever. A plain "declined" (the user
	# said no to one request) keeps the session.
	if code == "unauthorized" and auth_token != "":
		auth_token = ""
	_finish(id, _fail(code, message))


func _finish(id: int, result: Dictionary) -> void:
	_last_id = id
	_last_result = result
	_finished.emit(id)


func _fail(code: String, message: String) -> Dictionary:
	return {"ok": false, "code": code, "message": message}


## PackedByteArrays are raw bytes. Strings are base64 when [param strings_are_b64]
## (transactions), otherwise UTF-8 text (messages).
func _to_b64_list(items: Array, strings_are_b64: bool) -> Array:
	var out: Array = []
	for item in items:
		if item is PackedByteArray:
			out.append(Marshalls.raw_to_base64(item))
		elif strings_are_b64:
			out.append(str(item))
		else:
			out.append(Marshalls.utf8_to_base64(str(item)))
	return out
